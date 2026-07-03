// deep-link.simulation.cjs — headless Node simulation of the OSK-156 push deep-link
// router (web/deep-link.js): notification TAP -> in-app NAVIGATION.
//
// WHY this exists: the real thing needs a Capacitor Android device with FCM enabled
// (google-services.json — the OSK-165 human gate) and a human tapping a notification, none
// of which exists in CI. But every routing decision in deep-link.js is a pure, dependency-
// injected helper the module exports for exactly this reason. This script require()s those
// exports and drives them with a FAKE localStorage + a FAKE navigate + a FAKE location,
// asserting the whole behaviour spec of the ticket:
//   - resolveRoute() maps a KNOWN route (app/status/ops/help/admin/profile/history/index and
//     the "/", "home" aliases) to the correct same-origin "/x.html" page path,
//   - resolveRoute() REJECTS (null) every unknown screen AND every hostile/open-redirect
//     vector: javascript:, http(s)://, data:, protocol-relative //host, backslash tricks,
//     and drops query/hash so no attacker text is forwarded,
//   - a TAP while the app is RUNNING (isReady=true) navigates immediately to the mapped path,
//   - a TAP during a COLD START (isReady=false) PERSISTS the pending route instead of
//     navigating, and consumePendingRoute() on the next load reads+clears it and navigates,
//   - a no-route / unknown / hostile tap is a SAFE no-op (no navigation, no persistence),
//   - being already on the target page is idempotent (no self-reload),
//   - and it round-trips through the REAL push client (web/push-client.js): wiring the
//     client's onRoute seam to the router and firing pushNotificationActionPerformed with a
//     data.route actually navigates — proving the OSK-150 seam + OSK-166 contract + OSK-156
//     router fit together.
//
// It is a plain assertion harness (no test framework, no deps): it prints each check and
// exits non-zero on any failure, so it doubles as a CI-friendly gate. It is a `.cjs` file
// OUTSIDE web/e2e/tests/, so Playwright (testDir ./tests) never picks it up.
//
// Run:  node web/e2e/deep-link.simulation.cjs

"use strict";

const path = require("node:path");
// Require the REAL modules under test. Both are classic browser scripts that also export
// their pure helpers when require()d from Node (the browser bootstrap is skipped because
// `window` is undefined here), so this asserts the SAME code the device runs.
const deepLink = require(path.resolve(__dirname, "..", "deep-link.js"));
const push = require(path.resolve(__dirname, "..", "push-client.js"));

let passed = 0;
let failed = 0;

// Minimal assert: record + print pass/fail, never throw (so we see ALL failures).
function assert(label, cond) {
  if (cond) {
    passed++;
    console.log("  ok  - " + label);
  } else {
    failed++;
    console.log("  FAIL- " + label);
  }
}
function assertEqual(label, actual, expected) {
  assert(
    label + " (expected " + JSON.stringify(expected) + ", got " + JSON.stringify(actual) + ")",
    actual === expected
  );
}

// A promise that resolves after the current microtask/timeout queue, so the push client's
// async registration flow settles before we fire a tap through it.
function flush() {
  return new Promise(function (resolve) {
    setTimeout(resolve, 0);
  });
}

// ---------------------------------------------------------------------------
// Fakes.
// ---------------------------------------------------------------------------

// An in-memory localStorage-like store that records nothing beyond the values it holds.
function fakeStorage(initial) {
  const map = Object.assign({}, initial || {});
  return {
    getItem: function (k) {
      return Object.prototype.hasOwnProperty.call(map, k) ? map[k] : null;
    },
    setItem: function (k, v) {
      map[k] = String(v);
    },
    removeItem: function (k) {
      delete map[k];
    },
    // test-only peek
    _map: map,
  };
}

// A navigate spy: records every path it is asked to navigate to and exposes a fake
// "current location" that the caller can move to simulate having navigated.
function fakeNav(startPath) {
  const state = { calls: [], current: startPath || "/app.html" };
  return {
    navigate: function (p) {
      state.calls.push(p);
      state.current = p; // emulate the resulting page load
    },
    currentPath: function () {
      return state.current;
    },
    state: state,
  };
}

// A router wired with injected fakes. `ready` controls isReady() (true = app running).
function makeRouter(opts) {
  opts = opts || {};
  const storage = opts.storage || fakeStorage();
  const nav = opts.nav || fakeNav(opts.startPath || "/app.html");
  const router = deepLink.createDeepLinkRouter({
    storage: storage,
    navigate: nav.navigate,
    currentPath: nav.currentPath,
    isReady: function () {
      return opts.ready !== false; // default ready=true
    },
  });
  return { router: router, storage: storage, nav: nav };
}

const STORAGE_KEY = deepLink.OSK_DEEPLINK_STORAGE_KEY;

async function main() {
  // -------------------------------------------------------------------------
  console.log("resolveRoute — KNOWN routes map to same-origin page paths:");
  // -------------------------------------------------------------------------
  assertEqual("/status -> /status.html", deepLink.resolveRoute("/status"), "/status.html");
  assertEqual("status (no slash) -> /status.html", deepLink.resolveRoute("status"), "/status.html");
  assertEqual("/ops -> /ops.html", deepLink.resolveRoute("/ops"), "/ops.html");
  assertEqual("/help -> /help.html", deepLink.resolveRoute("/help"), "/help.html");
  assertEqual("/admin -> /admin.html", deepLink.resolveRoute("/admin"), "/admin.html");
  assertEqual("/profile -> /profile.html", deepLink.resolveRoute("/profile"), "/profile.html");
  assertEqual("/history -> /history.html", deepLink.resolveRoute("/history"), "/history.html");
  assertEqual("/app -> /app.html", deepLink.resolveRoute("/app"), "/app.html");
  assertEqual("/index -> /index.html", deepLink.resolveRoute("/index"), "/index.html");
  assertEqual("home alias -> /app.html", deepLink.resolveRoute("home"), "/app.html");
  assertEqual("root '/' -> app home", deepLink.resolveRoute("/"), "/app.html");
  // Query/hash are dropped: we navigate ONLY to the page path we control.
  assertEqual("query dropped: /status?next=x -> /status.html", deepLink.resolveRoute("/status?next=x"), "/status.html");
  assertEqual("hash dropped: /ops#frag -> /ops.html", deepLink.resolveRoute("/ops#frag"), "/ops.html");
  // A sub-path on a known screen still keys on the screen (per-record deep-links are future
  // work — the allowlist maps to the SCREEN, never forwarding the tail).
  assertEqual("known sub-path keys on screen: /history/42 -> /history.html", deepLink.resolveRoute("/history/42"), "/history.html");

  // -------------------------------------------------------------------------
  console.log("resolveRoute — UNKNOWN + HOSTILE routes are REJECTED (open-redirect defence):");
  // -------------------------------------------------------------------------
  assertEqual("unknown screen -> null", deepLink.resolveRoute("/messages/42"), null);
  assertEqual("unknown bare word -> null", deepLink.resolveRoute("dashboard"), null);
  assertEqual("javascript: scheme -> null", deepLink.resolveRoute("javascript:alert(1)"), null);
  assertEqual("http:// off-origin -> null", deepLink.resolveRoute("http://evil.example/x"), null);
  assertEqual("https:// off-origin -> null", deepLink.resolveRoute("https://evil.example"), null);
  assertEqual("data: URL -> null", deepLink.resolveRoute("data:text/html,<script>"), null);
  assertEqual("protocol-relative //host -> null", deepLink.resolveRoute("//evil.example/status"), null);
  assertEqual("backslash trick /\\evil -> null", deepLink.resolveRoute("/\\evil.example"), null);
  assertEqual("mixed \\/\\/ -> null", deepLink.resolveRoute("\\/\\/evil.example"), null);
  assertEqual("mailto: scheme -> null", deepLink.resolveRoute("mailto:x@y.z"), null);
  assertEqual("empty string -> null", deepLink.resolveRoute(""), null);
  assertEqual("blank string -> null", deepLink.resolveRoute("   "), null);
  assertEqual("non-string -> null", deepLink.resolveRoute(42), null);
  assertEqual("null -> null", deepLink.resolveRoute(null), null);
  // Even a hostile screen name that superficially contains a known word is rejected unless
  // its FIRST segment is exactly an allowlisted key.
  assertEqual("statusfoo (not 'status') -> null", deepLink.resolveRoute("/statusfoo"), null);

  // -------------------------------------------------------------------------
  console.log("handleTapRoute — app RUNNING navigates IMMEDIATELY:");
  // -------------------------------------------------------------------------
  {
    const h = makeRouter({ ready: true, startPath: "/app.html" });
    const ret = h.router.handleTapRoute("/history");
    assertEqual("returns resolved path", ret, "/history.html");
    assertEqual("navigated once", h.nav.state.calls.length, 1);
    assertEqual("navigated to mapped path", h.nav.state.calls[0], "/history.html");
    assertEqual("nothing persisted (immediate nav)", h.storage.getItem(STORAGE_KEY), null);
  }

  // -------------------------------------------------------------------------
  console.log("handleTapRoute — already ON target page is idempotent (no reload):");
  // -------------------------------------------------------------------------
  {
    const h = makeRouter({ ready: true, startPath: "/history.html" });
    const ret = h.router.handleTapRoute("/history");
    assertEqual("still returns the resolved path", ret, "/history.html");
    assertEqual("did NOT navigate (already there)", h.nav.state.calls.length, 0);
  }
  {
    // clean-path current location ("/history") is recognised as the same page as
    // "/history.html" so no needless reload happens on the live Hosting site either.
    const h = makeRouter({ ready: true, startPath: "/history" });
    h.router.handleTapRoute("/history");
    assertEqual("clean-path current == .html target: no nav", h.nav.state.calls.length, 0);
  }

  // -------------------------------------------------------------------------
  console.log("handleTapRoute — no-route / hostile tap is a SAFE no-op:");
  // -------------------------------------------------------------------------
  {
    const h = makeRouter({ ready: true });
    assertEqual("unknown route returns null", h.router.handleTapRoute("/messages/42"), null);
    assertEqual("hostile route returns null", h.router.handleTapRoute("https://evil.example"), null);
    assertEqual("null route returns null", h.router.handleTapRoute(null), null);
    assert("no navigation on any of them", h.nav.state.calls.length === 0);
    assert("nothing persisted", h.storage.getItem(STORAGE_KEY) === null);
  }

  // -------------------------------------------------------------------------
  console.log("COLD START — tap during boot PERSISTS, next load CONSUMES + navigates:");
  // -------------------------------------------------------------------------
  {
    // 1) Tap arrives before the first page can navigate (isReady=false): persist, no nav.
    const storage = fakeStorage();
    const boot = makeRouter({ ready: false, startPath: "/app.html", storage: storage });
    const ret = boot.router.handleTapRoute("/status");
    assertEqual("cold tap returns resolved path", ret, "/status.html");
    assertEqual("cold tap did NOT navigate yet", boot.nav.state.calls.length, 0);
    assertEqual("cold tap persisted the pending route", storage.getItem(STORAGE_KEY), "/status.html");

    // 2) Next app load: a NEW router (ready) shares the same storage and consumes it.
    const load = makeRouter({ ready: true, startPath: "/app.html", storage: storage });
    const consumed = load.router.consumePendingRoute();
    assertEqual("consume returns the pending path", consumed, "/status.html");
    assertEqual("consume navigated to it", load.nav.state.calls[0], "/status.html");
    assertEqual("pending route CLEARED after consume (one-shot)", storage.getItem(STORAGE_KEY), null);

    // 3) A SECOND load finds nothing pending -> no navigation (not sticky/looping).
    const load2 = makeRouter({ ready: true, startPath: "/status.html", storage: storage });
    assertEqual("second load: nothing to consume", load2.router.consumePendingRoute(), null);
    assertEqual("second load: no navigation", load2.nav.state.calls.length, 0);
  }
  {
    // Cold-start consume when we ALREADY landed on the target page: clears, no self-reload.
    const storage = fakeStorage();
    storage.setItem(STORAGE_KEY, "/ops.html");
    const load = makeRouter({ ready: true, startPath: "/ops.html", storage: storage });
    assertEqual("consume returns path even when already there", load.router.consumePendingRoute(), "/ops.html");
    assertEqual("no self-reload when already on target", load.nav.state.calls.length, 0);
    assertEqual("pending still cleared", storage.getItem(STORAGE_KEY), null);
  }
  {
    // Defence in depth: a tampered/garbage persisted value is discarded, never navigated to.
    const storage = fakeStorage();
    storage.setItem(STORAGE_KEY, "https://evil.example");
    const load = makeRouter({ ready: true, storage: storage });
    assertEqual("garbage pending route discarded", load.router.consumePendingRoute(), null);
    assertEqual("no navigation to garbage", load.nav.state.calls.length, 0);
    assertEqual("garbage cleared from storage", storage.getItem(STORAGE_KEY), null);
  }

  // -------------------------------------------------------------------------
  console.log("storage degrades gracefully (private mode / no storage):");
  // -------------------------------------------------------------------------
  {
    // No storage injected at all: a cold tap can't persist but must not throw, and consume
    // is a safe null.
    const nav = fakeNav("/app.html");
    const router = deepLink.createDeepLinkRouter({
      navigate: nav.navigate,
      currentPath: nav.currentPath,
      isReady: function () { return false; },
    });
    assertEqual("cold tap with no storage still resolves", router.handleTapRoute("/help"), "/help.html");
    assert("cold tap with no storage did not navigate", nav.state.calls.length === 0);
    assertEqual("consume with no storage is null", router.consumePendingRoute(), null);
  }

  // -------------------------------------------------------------------------
  console.log("extractRoute — pulls data.route out of a notification (OSK-166 shape):");
  // -------------------------------------------------------------------------
  assertEqual("route key is the wire contract", deepLink.OSK_DEEPLINK_ROUTE_KEY, "route");
  assertEqual("ActionPerformed shape", deepLink.extractRoute({ notification: { data: { route: "/ops" } } }), "/ops");
  assertEqual("raw notification shape", deepLink.extractRoute({ data: { route: "/status" } }), "/status");
  assertEqual("missing route -> null", deepLink.extractRoute({ data: {} }), null);
  assertEqual("non-object -> null", deepLink.extractRoute(null), null);

  // -------------------------------------------------------------------------
  console.log("INTEGRATION — push client (OSK-150) tap -> deep-link router navigation:");
  // -------------------------------------------------------------------------
  {
    // Build the real push client with its onRoute seam wired straight into the router — the
    // exact wiring web/push-client.js's browser bootstrap does via window.OSKDeepLink.
    const nav = fakeNav("/app.html");
    const router = deepLink.createDeepLinkRouter({
      storage: fakeStorage(),
      navigate: nav.navigate,
      currentPath: nav.currentPath,
      isReady: function () { return true; },
    });

    // Minimal native Capacitor stub that captures the tap listener so we can fire it.
    const listeners = {};
    const cap = {
      isNativePlatform: function () { return true; },
      getPlatform: function () { return "android"; },
      Plugins: {
        PushNotifications: {
          requestPermissions: function () { return Promise.resolve({ receive: "granted" }); },
          register: function () { return Promise.resolve(); },
          addListener: function (event, handler) {
            listeners[event] = handler;
            return Promise.resolve({ remove: function () { return Promise.resolve(); } });
          },
          removeAllListeners: function () { return Promise.resolve(); },
        },
      },
    };

    const client = push.createPushClient({
      capacitor: cap,
      getToken: function () { return Promise.resolve("id-tok"); },
      apiBaseUrl: "https://api.example.test",
      fetchImpl: function () { return Promise.resolve({ status: 200, ok: true }); },
      // THE SEAM: forward taps to the deep-link router (as the browser bootstrap does).
      onRoute: function (route) { router.handleTapRoute(route); },
    });

    await client.register();
    await flush();
    assert("tap listener attached", typeof listeners.pushNotificationActionPerformed === "function");

    // The user taps a notification carrying a KNOWN deep-link route.
    listeners.pushNotificationActionPerformed({ notification: { data: { route: "/history" } } });
    assertEqual("push tap navigated to mapped page", nav.state.calls[0], "/history.html");

    // A tap carrying a HOSTILE route is swallowed by the router's allowlist — no navigation.
    const before = nav.state.calls.length;
    listeners.pushNotificationActionPerformed({ notification: { data: { route: "javascript:alert(1)" } } });
    assertEqual("hostile push tap did NOT navigate", nav.state.calls.length, before);

    // A tap with NO route is a safe no-op.
    listeners.pushNotificationActionPerformed({ notification: { data: {} } });
    assertEqual("no-route push tap did NOT navigate", nav.state.calls.length, before);
  }

  // -------------------------------------------------------------------------
  console.log("\n" + passed + " passed, " + failed + " failed");
  if (failed > 0) {
    process.exit(1);
  }
}

main().catch(function (e) {
  console.error("SIMULATION CRASHED:", e);
  process.exit(1);
});
