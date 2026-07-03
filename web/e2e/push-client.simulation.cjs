// push-client.simulation.cjs — headless Node simulation of the OSK-150 push
// notification CLIENT (web/push-client.js).
//
// WHY this exists: the real thing needs a Capacitor Android device with FCM enabled
// (google-services.json — the OSK-165 human gate) and a human tapping "Allow" on the OS
// notification prompt — none of which exists in CI, and none of which is even wired yet.
// But every bit of decision logic in push-client.js is a pure, dependency-injected
// helper that the module exports for exactly this reason. This script require()s those
// exports and drives them with a STUBBED PushNotifications plugin and a STUBBED fetch,
// asserting:
//   - detectMode picks NATIVE / NONE from the injected environment (native-only, guards
//     a browser JS shim and a shell built before `cap sync`),
//   - platformFor maps the Capacitor platform id to the backend Platform enum value,
//   - normalizePermissionState maps Capacitor 'receive' strings to granted/denied/prompt,
//   - extractRoute pulls the OSK-166 `data.route` deep-link out of a tapped notification
//     (the OSK-156 seam) and returns null for anything missing/blank,
//   - registerWithBackend POSTs { token, platform } to /api/v1/me/devices with a Bearer
//     token and classifies ok / no-session / unauthorized / http-error / network-error,
//   - deregisterWithBackend DELETEs /api/v1/me/devices/{token} (URL-encoded),
//   - the NATIVE controller runs the whole flow: request permission → register() →
//     the `registration` event delivers the FCM token → it is POSTed to the backend;
//     a tap fires the deep-link seam WITHOUT navigating; unregister() DELETEs the token
//     and tears the listeners down,
//   - a DENIED permission short-circuits: register() reports denied and NEVER calls the
//     plugin's register(),
//   - the NONE path (plain browser) is a graceful no-op: register()/unregister() resolve
//     `unsupported` and the plugin is never touched.
//
// It is a plain assertion harness (no test framework, no deps): it prints each check and
// exits non-zero on any failure, so it doubles as a CI-friendly gate. It is a `.cjs`
// file OUTSIDE web/e2e/tests/, so Playwright (testDir ./tests) never picks it up.
//
// Run:  node web/e2e/push-client.simulation.cjs

"use strict";

const path = require("node:path");
// Require the REAL module under test. push-client.js is a classic browser script that
// also exports its pure helpers when required from Node (its browser bootstrap is
// skipped because `window` is undefined here), so this asserts the SAME code the device
// runs.
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

// A promise that resolves after all currently-queued microtasks/timeouts, so we can
// await async flows (the registration event -> backend POST) settling before asserting.
function flush() {
  return new Promise(function (resolve) {
    setTimeout(resolve, 0);
  });
}

// ---------------------------------------------------------------------------
// Fakes.
// ---------------------------------------------------------------------------

// A stubbed Capacitor runtime whose PushNotifications plugin records calls and captures
// the listener callbacks by event name so the test can FIRE them like the OS would.
//   opts.permission — what requestPermissions() resolves { receive } to (default granted)
//   opts.platform   — what getPlatform() returns (default "android")
function fakeCapacitor(opts) {
  opts = opts || {};
  const calls = { requestPermissions: 0, register: 0, removed: 0 };
  const listeners = {}; // event name -> handler
  return {
    calls: calls,
    listeners: listeners,
    // Fire a captured native listener as the OS/plugin would.
    fire: function (event, payload) {
      if (listeners[event]) {
        return listeners[event](payload);
      }
    },
    capacitor: {
      isNativePlatform: function () {
        return true;
      },
      getPlatform: function () {
        return opts.platform || "android";
      },
      Plugins: {
        PushNotifications: {
          requestPermissions: function () {
            calls.requestPermissions++;
            return Promise.resolve({ receive: opts.permission || "granted" });
          },
          register: function () {
            calls.register++;
            return Promise.resolve();
          },
          addListener: function (event, handler) {
            listeners[event] = handler;
            // Real plugin resolves a PluginListenerHandle; mirror that (with .remove()).
            return Promise.resolve({
              remove: function () {
                calls.removed++;
                return Promise.resolve();
              },
            });
          },
          removeAllListeners: function () {
            return Promise.resolve();
          },
        },
      },
    },
  };
}

// A stubbed fetch that records the last request and returns a scripted Response-like
// object. `status` defaults to 200; `ok` is derived. If `throwErr` is set it rejects
// (to exercise the network-error path).
function fakeFetch(script) {
  script = script || {};
  const record = { calls: [] };
  const fn = function (url, init) {
    record.calls.push({ url: url, init: init || {} });
    record.lastUrl = url;
    record.lastInit = init || {};
    if (script.throwErr) {
      return Promise.reject(new Error("network down"));
    }
    const status = typeof script.status === "number" ? script.status : 200;
    return Promise.resolve({
      status: status,
      ok: status >= 200 && status < 300,
    });
  };
  fn.record = record;
  return fn;
}

// A getToken stub: resolves the given id token (or null to simulate signed-out).
function fakeGetToken(token) {
  return function () {
    return Promise.resolve(token);
  };
}

const API = "https://api.example.test";

async function main() {
  const M = push.OSK_PUSH_MODE;
  const PLAT = push.OSK_PUSH_PLATFORM;

  // -------------------------------------------------------------------------
  console.log("detectMode:");
  // -------------------------------------------------------------------------
  assertEqual("native env -> NATIVE", push.detectMode({ capacitor: fakeCapacitor().capacitor }), M.NATIVE);
  assertEqual("empty env -> NONE", push.detectMode({}), M.NONE);
  assertEqual("undefined env -> NONE", push.detectMode(), M.NONE);
  assertEqual(
    "capacitor not-native (browser shim) -> NONE",
    push.detectMode({
      capacitor: { isNativePlatform: function () { return false; }, Plugins: { PushNotifications: {} } },
    }),
    M.NONE
  );
  assertEqual(
    "native but no plugin (pre-cap-sync) -> NONE",
    push.detectMode({ capacitor: { isNativePlatform: function () { return true; }, Plugins: {} } }),
    M.NONE
  );

  // -------------------------------------------------------------------------
  console.log("platformFor:");
  // -------------------------------------------------------------------------
  assertEqual("android -> ANDROID", push.platformFor({ getPlatform: function () { return "android"; } }), PLAT.ANDROID);
  assertEqual("ios -> IOS", push.platformFor({ getPlatform: function () { return "ios"; } }), PLAT.IOS);
  assertEqual("web -> WEB", push.platformFor({ getPlatform: function () { return "web"; } }), PLAT.WEB);
  assertEqual("unknown -> ANDROID (default)", push.platformFor({ getPlatform: function () { return "tizen"; } }), PLAT.ANDROID);
  assertEqual("no getPlatform -> ANDROID", push.platformFor({}), PLAT.ANDROID);

  // -------------------------------------------------------------------------
  console.log("normalizePermissionState:");
  // -------------------------------------------------------------------------
  assertEqual("granted", push.normalizePermissionState("granted"), "granted");
  assertEqual("denied", push.normalizePermissionState("denied"), "denied");
  assertEqual("prompt", push.normalizePermissionState("prompt"), "prompt");
  assertEqual("prompt-with-rationale -> prompt", push.normalizePermissionState("prompt-with-rationale"), "prompt");
  assertEqual("unknown -> prompt", push.normalizePermissionState("banana"), "prompt");

  // -------------------------------------------------------------------------
  console.log("extractRoute (OSK-156 deep-link seam):");
  // -------------------------------------------------------------------------
  assertEqual("route from ActionPerformed", push.extractRoute({ notification: { data: { route: "/messages/42" } } }), "/messages/42");
  assertEqual("route from raw notification", push.extractRoute({ data: { route: "/home" } }), "/home");
  assertEqual("blank route -> null", push.extractRoute({ data: { route: "   " } }), null);
  assertEqual("missing route -> null", push.extractRoute({ data: { other: "x" } }), null);
  assertEqual("no data -> null", push.extractRoute({ notification: {} }), null);
  assertEqual("null input -> null", push.extractRoute(null), null);
  assertEqual("non-string route -> null", push.extractRoute({ data: { route: 42 } }), null);
  assertEqual("route key matches the wire contract", push.OSK_PUSH_ROUTE_KEY, "route");

  // -------------------------------------------------------------------------
  console.log("registerWithBackend (POST /api/v1/me/devices):");
  // -------------------------------------------------------------------------
  {
    const f = fakeFetch({ status: 200 });
    const res = await push.registerWithBackend({
      token: "fcm-abc:123", platform: PLAT.ANDROID, getToken: fakeGetToken("id-tok"), apiBaseUrl: API, fetchImpl: f,
    });
    assertEqual("status ok", res.status, "ok");
    assertEqual("POST method", f.record.lastInit.method, "POST");
    assertEqual("correct URL", f.record.lastUrl, API + "/api/v1/me/devices");
    assertEqual("Bearer header", f.record.lastInit.headers.Authorization, "Bearer id-tok");
    assertEqual("Content-Type json", f.record.lastInit.headers["Content-Type"], "application/json");
    const body = JSON.parse(f.record.lastInit.body);
    assertEqual("body token", body.token, "fcm-abc:123");
    assertEqual("body platform", body.platform, "ANDROID");
  }
  {
    const f = fakeFetch({ status: 200 });
    const res = await push.registerWithBackend({ token: "t", getToken: fakeGetToken(null), apiBaseUrl: API, fetchImpl: f });
    assertEqual("no id token -> no-session", res.status, "no-session");
    assert("fetch NOT called when signed out", f.record.calls.length === 0);
  }
  {
    const f = fakeFetch({ status: 401 });
    const res = await push.registerWithBackend({ token: "t", getToken: fakeGetToken("x"), apiBaseUrl: API, fetchImpl: f });
    assertEqual("401 -> unauthorized", res.status, "unauthorized");
  }
  {
    const f = fakeFetch({ status: 500 });
    const res = await push.registerWithBackend({ token: "t", getToken: fakeGetToken("x"), apiBaseUrl: API, fetchImpl: f });
    assertEqual("500 -> http-error", res.status, "http-error");
    assertEqual("carries httpStatus", res.httpStatus, 500);
  }
  {
    const f = fakeFetch({ throwErr: true });
    const res = await push.registerWithBackend({ token: "t", getToken: fakeGetToken("x"), apiBaseUrl: API, fetchImpl: f });
    assertEqual("throw -> network-error", res.status, "network-error");
  }
  {
    const res = await push.registerWithBackend({ token: "   ", getToken: fakeGetToken("x"), apiBaseUrl: API, fetchImpl: fakeFetch() });
    assertEqual("blank push token -> invalid", res.status, "invalid");
  }

  // -------------------------------------------------------------------------
  console.log("deregisterWithBackend (DELETE /api/v1/me/devices/{token}):");
  // -------------------------------------------------------------------------
  {
    const f = fakeFetch({ status: 204 });
    const res = await push.deregisterWithBackend({ token: "fcm-abc:123", getToken: fakeGetToken("id-tok"), apiBaseUrl: API, fetchImpl: f });
    assertEqual("204 -> ok", res.status, "ok");
    assertEqual("DELETE method", f.record.lastInit.method, "DELETE");
    // Token is URL-encoded in the path (the ':' becomes %3A).
    assertEqual("URL-encoded token in path", f.record.lastUrl, API + "/api/v1/me/devices/fcm-abc%3A123");
    assertEqual("Bearer header", f.record.lastInit.headers.Authorization, "Bearer id-tok");
  }
  {
    const f = fakeFetch({ status: 200 });
    const res = await push.deregisterWithBackend({ token: "", getToken: fakeGetToken("x"), apiBaseUrl: API, fetchImpl: f });
    assertEqual("empty token -> invalid (nothing attempted)", res.status, "invalid");
    assert("fetch NOT called for empty token", f.record.calls.length === 0);
  }

  // -------------------------------------------------------------------------
  console.log("controller — NATIVE full flow (permission -> token -> backend):");
  // -------------------------------------------------------------------------
  {
    const cap = fakeCapacitor({ permission: "granted", platform: "android" });
    const f = fakeFetch({ status: 200 });
    let registered = null;
    let routed = null;
    let received = null;
    const ctrl = push.createPushClient({
      capacitor: cap.capacitor,
      getToken: fakeGetToken("id-tok"),
      apiBaseUrl: API,
      fetchImpl: f,
      onRegistration: function (r) { registered = r; },
      onRoute: function (route) { routed = route; },
      onReceived: function (n) { received = n; },
    });

    assertEqual("mode() == native", ctrl.mode(), M.NATIVE);
    assertEqual("platform() == ANDROID", ctrl.platform(), PLAT.ANDROID);

    const reg = await ctrl.register();
    assertEqual("register() -> registering", reg.status, "registering");
    assert("requestPermissions was called", cap.calls.requestPermissions === 1);
    assert("plugin.register() was called", cap.calls.register === 1);
    assert("registration listener attached", typeof cap.listeners.registration === "function");
    assert("registrationError listener attached", typeof cap.listeners.registrationError === "function");
    assert("actionPerformed listener attached", typeof cap.listeners.pushNotificationActionPerformed === "function");

    // The OS/FCM now delivers the token via the `registration` event.
    cap.fire("registration", { value: "fcm-token-xyz" });
    await flush();
    assert("token POSTed to backend", f.record.calls.length === 1);
    assertEqual("POST hit devices endpoint", f.record.lastUrl, API + "/api/v1/me/devices");
    assertEqual("registered token remembered", ctrl.lastToken(), "fcm-token-xyz");
    assert("onRegistration observer fired", registered !== null);
    assertEqual("onRegistration result ok", registered.result.status, "ok");
    assertEqual("onRegistration carries token", registered.token, "fcm-token-xyz");

    // A push arrives in the foreground.
    cap.fire("pushNotificationReceived", { title: "hi", data: {} });
    assert("onReceived observer fired", received !== null);

    // The user TAPS a notification carrying a deep-link route — the seam fires, but this
    // module never navigates (OSK-156 owns that).
    cap.fire("pushNotificationActionPerformed", { notification: { data: { route: "/messages/7" } } });
    assertEqual("onRoute seam got the route", routed, "/messages/7");

    // Sign-out: DELETE the token + tear down listeners.
    const un = await ctrl.unregister();
    assertEqual("unregister -> ok", un.status, "ok");
    assert("DELETE was sent", f.record.calls.length === 2);
    assertEqual("DELETE hit token path", f.record.lastUrl, API + "/api/v1/me/devices/fcm-token-xyz");
    assert("listeners removed on unregister", cap.calls.removed === 4);
    assertEqual("lastToken cleared after unregister", ctrl.lastToken(), null);
  }

  // -------------------------------------------------------------------------
  console.log("controller — NATIVE permission DENIED (no registration):");
  // -------------------------------------------------------------------------
  {
    const cap = fakeCapacitor({ permission: "denied" });
    const f = fakeFetch({ status: 200 });
    const ctrl = push.createPushClient({ capacitor: cap.capacitor, getToken: fakeGetToken("x"), apiBaseUrl: API, fetchImpl: f });
    const reg = await ctrl.register();
    assertEqual("register() -> denied", reg.status, "denied");
    assert("plugin.register() NOT called after denial", cap.calls.register === 0);
    assert("no backend call after denial", f.record.calls.length === 0);
    assertEqual("requestPermission -> denied", await ctrl.requestPermission(), "denied");
  }

  // -------------------------------------------------------------------------
  console.log("controller — NONE (plain browser: graceful no-op):");
  // -------------------------------------------------------------------------
  {
    const f = fakeFetch({ status: 200 });
    const ctrl = push.createPushClient({ capacitor: undefined, getToken: fakeGetToken("x"), apiBaseUrl: API, fetchImpl: f });
    assertEqual("mode() == none", ctrl.mode(), M.NONE);
    assertEqual("requestPermission -> unavailable", await ctrl.requestPermission(), "unavailable");
    assertEqual("register() -> unsupported", (await ctrl.register()).status, "unsupported");
    assertEqual("unregister() -> unsupported", (await ctrl.unregister()).status, "unsupported");
    assert("browser path makes ZERO network calls", f.record.calls.length === 0);
  }

  // -------------------------------------------------------------------------
  console.log("controller — iOS platform maps to IOS enum:");
  // -------------------------------------------------------------------------
  {
    const cap = fakeCapacitor({ permission: "granted", platform: "ios" });
    const f = fakeFetch({ status: 200 });
    const ctrl = push.createPushClient({ capacitor: cap.capacitor, getToken: fakeGetToken("id"), apiBaseUrl: API, fetchImpl: f });
    await ctrl.register();
    cap.fire("registration", { value: "apns-fcm-tok" });
    await flush();
    const body = JSON.parse(f.record.lastInit.body);
    assertEqual("iOS device registers platform IOS", body.platform, "IOS");
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
