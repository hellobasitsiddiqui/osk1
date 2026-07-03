// onboarding.simulation.cjs — headless Node simulation of the OSK-82 first-login
// onboarding wiring (web/onboarding.js), which ties the OSK-105 product tour to the
// OSK-69 server-side onboarding flag.
//
// WHY this exists: a LIVE run needs a signed-in Firebase user + a real backend, both
// human-gated behind the Firebase apiKey (OSK-92) — so, exactly like
// history.simulation.cjs / auth-guard.simulation.cjs, we assert the module's behaviour
// headlessly. onboarding.js is written dual-use (pure helpers exported for Node; the
// browser bootstrap skipped when `window` is absent), so this drives:
//
//   1. THE PURE HELPERS directly with a stubbed fetch + getToken:
//        - patchLifecycle: PATCH /api/v1/me/lifecycle carries the Bearer token, the
//          Content-Type, and the exact body { onboardingCompleted: true }; classifies
//          ok / 401 / http-error / network-error / no-session and NEVER throws,
//        - fetchState: GET /api/v1/me reads onboardingCompleted (missing => false),
//        - computePlan: auto-start is SUPPRESSED when the server flag is true, ALLOWED
//          once when it is false, and every fallback branch (signed out / server unknown /
//          pending completion / resume / idle / passive) is asserted,
//        - recordCompletion: records when it can, else queues a pending-sync marker.
//
//   2. THE REAL BROWSER BOOTSTRAP under a FAKE DOM (a tiny window/document/localStorage +
//      stubbed OSKAuth/OSKTour/fetch): completing the tour (dispatching the
//      `osk:tour:finished` event tour.js fires) PATCHes { onboardingCompleted: true } with
//      the Bearer token; auto-start is suppressed when the server says completed and fires
//      exactly once when it says not; a 401 / network error is tolerated (queued, never
//      thrown); and on a page with no auth the completion is queued for the next load.
//
// Plain assertion harness (no framework, no deps): prints each check, exits non-zero on the
// first failure. A `.cjs` file OUTSIDE web/e2e/tests/, so Playwright never runs it as a spec.
//
// Run:  node web/e2e/onboarding.simulation.cjs

"use strict";

const path = require("node:path");

// The REAL modules under test. Both are browser scripts that also export their pure
// helpers when required from Node (their DOM bootstrap is skipped because `window` is
// undefined at require-time here), so this asserts the SAME code the pages run.
const ONB_PATH = path.resolve(__dirname, "..", "onboarding.js");
const onb = require(ONB_PATH);
const tour = require(path.resolve(__dirname, "..", "tour.js"));

let passed = 0;
let failed = 0;

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
    actual === expected,
  );
}

// ---------------------------------------------------------------------------
// Test doubles.
// ---------------------------------------------------------------------------

// A fetch Response shim (same shape as history.simulation.cjs): .status, .ok, .json().
function fakeResponse(status, jsonBody) {
  return {
    status,
    ok: status >= 200 && status < 300,
    json: () => Promise.resolve(jsonBody),
  };
}

// A localStorage shim: Map-backed, with the getItem/setItem/removeItem the module uses.
function fakeStorage() {
  const map = new Map();
  return {
    getItem: (k) => (map.has(k) ? map.get(k) : null),
    setItem: (k, v) => map.set(k, String(v)),
    removeItem: (k) => map.delete(k),
    _dump: () => Object.fromEntries(map),
  };
}

// ===========================================================================
// 1) patchLifecycle — request shape + outcome classification (STUBBED fetch/getToken).
// ===========================================================================
async function testPatchLifecycle() {
  console.log("\npatchLifecycle() (stubbed fetch + getToken):");

  // (a) No token -> no-session, and fetch is never called.
  {
    let called = false;
    const r = await onb.patchLifecycle({
      getToken: () => Promise.resolve(null),
      apiBaseUrl: "http://127.0.0.1:8080",
      fetchImpl: () => { called = true; return Promise.resolve(fakeResponse(200, {})); },
    });
    assertEqual("no token -> no-session", r.status, "no-session");
    assertEqual("no token -> no fetch", called, false);
  }

  // (b) Success -> ok; the request is a PATCH to /api/v1/me/lifecycle carrying the Bearer
  //     token, JSON Content-Type, and the exact { onboardingCompleted: true } body. The
  //     trailing slash on the base URL is trimmed (no "//api").
  {
    let seen = {};
    const r = await onb.patchLifecycle({
      getToken: () => Promise.resolve("TOKEN123"),
      apiBaseUrl: "http://127.0.0.1:8080/",
      fetchImpl: (url, opts) => { seen = { url, opts }; return Promise.resolve(fakeResponse(204, {})); },
    });
    assertEqual("ok status", r.status, "ok");
    assertEqual("hits /me/lifecycle with no double slash", seen.url, "http://127.0.0.1:8080/api/v1/me/lifecycle");
    assertEqual("method is PATCH", seen.opts.method, "PATCH");
    assertEqual("sends Bearer token", seen.opts.headers.Authorization, "Bearer TOKEN123");
    assertEqual("sends JSON Content-Type", seen.opts.headers["Content-Type"], "application/json");
    assertEqual("body is exactly {onboardingCompleted:true}", seen.opts.body, '{"onboardingCompleted":true}');
  }

  // (c) 401 -> unauthorized (tolerated, not thrown).
  {
    const r = await onb.patchLifecycle({
      getToken: () => Promise.resolve("T"),
      apiBaseUrl: "http://x",
      fetchImpl: () => Promise.resolve(fakeResponse(401, {})),
    });
    assertEqual("401 -> unauthorized", r.status, "unauthorized");
  }

  // (d) 500 -> http-error with the status code.
  {
    const r = await onb.patchLifecycle({
      getToken: () => Promise.resolve("T"),
      apiBaseUrl: "http://x",
      fetchImpl: () => Promise.resolve(fakeResponse(500, {})),
    });
    assertEqual("500 -> http-error", r.status, "http-error");
    assertEqual("http-error carries the code", r.httpStatus, 500);
  }

  // (e) fetch throws (offline/CORS) -> network-error (never throws out).
  {
    const r = await onb.patchLifecycle({
      getToken: () => Promise.resolve("T"),
      apiBaseUrl: "http://x",
      fetchImpl: () => Promise.reject(new Error("offline")),
    });
    assertEqual("thrown fetch -> network-error", r.status, "network-error");
  }

  // (f) A caller-supplied body is passed through verbatim.
  {
    let sentBody = null;
    await onb.patchLifecycle({
      getToken: () => Promise.resolve("T"),
      apiBaseUrl: "http://x",
      body: { onboardingCompleted: true, extra: 1 },
      fetchImpl: (u, o) => { sentBody = o.body; return Promise.resolve(fakeResponse(200, {})); },
    });
    assertEqual("custom body passed through", sentBody, '{"onboardingCompleted":true,"extra":1}');
  }
}

// ===========================================================================
// 2) fetchState — GET /api/v1/me + onboardingCompleted read.
// ===========================================================================
async function testFetchState() {
  console.log("\nfetchState() (GET /me, stubbed fetch + getToken):");

  // (a) No token -> no-session.
  {
    const r = await onb.fetchState({ getToken: () => Promise.resolve(null), apiBaseUrl: "http://x", fetchImpl: () => Promise.resolve(fakeResponse(200, {})) });
    assertEqual("no token -> no-session", r.status, "no-session");
  }

  // (b) 200 with onboardingCompleted:true -> ok + true; GET /api/v1/me with Bearer.
  {
    let seen = {};
    const r = await onb.fetchState({
      getToken: () => Promise.resolve("TOK"),
      apiBaseUrl: "http://127.0.0.1:8080",
      fetchImpl: (url, opts) => { seen = { url, opts }; return Promise.resolve(fakeResponse(200, { onboardingCompleted: true })); },
    });
    assertEqual("ok status", r.status, "ok");
    assertEqual("reads onboardingCompleted true", r.onboardingCompleted, true);
    assertEqual("hits /api/v1/me", seen.url, "http://127.0.0.1:8080/api/v1/me");
    assertEqual("GET sends Bearer token", seen.opts.headers.Authorization, "Bearer TOK");
  }

  // (c) 200 with onboardingCompleted:false -> false.
  {
    const r = await onb.fetchState({ getToken: () => Promise.resolve("T"), apiBaseUrl: "http://x", fetchImpl: () => Promise.resolve(fakeResponse(200, { onboardingCompleted: false })) });
    assertEqual("reads onboardingCompleted false", r.onboardingCompleted, false);
  }

  // (d) 200 with the flag MISSING -> defaults to false (safe: unknown => not onboarded).
  {
    const r = await onb.fetchState({ getToken: () => Promise.resolve("T"), apiBaseUrl: "http://x", fetchImpl: () => Promise.resolve(fakeResponse(200, { email: "a@b.c" })) });
    assertEqual("missing flag defaults to false", r.onboardingCompleted, false);
  }

  // (e) 401 / 500 / throw -> classified, never thrown.
  {
    const un = await onb.fetchState({ getToken: () => Promise.resolve("T"), apiBaseUrl: "http://x", fetchImpl: () => Promise.resolve(fakeResponse(401, {})) });
    assertEqual("401 -> unauthorized", un.status, "unauthorized");
    const he = await onb.fetchState({ getToken: () => Promise.resolve("T"), apiBaseUrl: "http://x", fetchImpl: () => Promise.resolve(fakeResponse(503, {})) });
    assertEqual("503 -> http-error", he.status, "http-error");
    const ne = await onb.fetchState({ getToken: () => Promise.resolve("T"), apiBaseUrl: "http://x", fetchImpl: () => Promise.reject(new Error("x")) });
    assertEqual("thrown -> network-error", ne.status, "network-error");
  }
}

// ===========================================================================
// 3) computePlan — the on-load decision (the heart of "suppress when true, allow once
//    when false"). Every branch asserted.
// ===========================================================================
function testComputePlan() {
  console.log("\ncomputePlan() (auto-start decision, all branches):");

  // Not our page to drive -> passive, nothing happens (tour.js keeps its localStorage flow).
  {
    const p = onb.computePlan({ owns: false, signedIn: true, serverKnown: true, serverCompleted: false, pendingCompletion: false, tourAction: "autostart" });
    assertEqual("!owns -> passive", p.mode, "passive");
    assertEqual("!owns -> no auto-start", p.autoStart, false);
  }

  // Signed OUT -> fall back to tour.js's own localStorage decision (we suppressed it, so we
  // replicate it): autostart action -> auto-start; idle -> nothing.
  {
    const a = onb.computePlan({ owns: true, signedIn: false, tourAction: "autostart" });
    assertEqual("signed-out + tour autostart -> auto-start", a.autoStart, true);
    assertEqual("signed-out mode", a.mode, "signed-out-fallback");
    const b = onb.computePlan({ owns: true, signedIn: false, tourAction: "idle" });
    assertEqual("signed-out + tour idle -> no auto-start", b.autoStart, false);
  }

  // Signed in but GET /me failed -> best-effort local fallback (don't gamble on the flag).
  {
    const p = onb.computePlan({ owns: true, signedIn: true, serverKnown: false, tourAction: "autostart" });
    assertEqual("server-unknown mode", p.mode, "server-unknown-fallback");
    assertEqual("server-unknown + tour autostart -> auto-start", p.autoStart, true);
  }

  // A queued completion -> flush it (record), mark local done, never auto-start.
  {
    const p = onb.computePlan({ owns: true, signedIn: true, serverKnown: true, serverCompleted: false, pendingCompletion: true, tourAction: "autostart" });
    assertEqual("pending mode", p.mode, "sync-pending");
    assertEqual("pending -> record completion", p.recordCompleted, true);
    assertEqual("pending -> mark local done", p.markLocalDone, true);
    assertEqual("pending -> no auto-start", p.autoStart, false);
  }

  // Server says DONE -> NEVER auto-start; mark local done so a later sign-out won't re-nag.
  {
    const p = onb.computePlan({ owns: true, signedIn: true, serverKnown: true, serverCompleted: true, pendingCompletion: false, tourAction: "autostart" });
    assertEqual("already-onboarded mode", p.mode, "already-onboarded");
    assertEqual("server true -> auto-start SUPPRESSED", p.autoStart, false);
    assertEqual("server true -> mark local done", p.markLocalDone, true);
    assertEqual("server true -> nothing to record", p.recordCompleted, false);
  }

  // Server says NOT done + a fresh first run -> auto-start ONCE, mark local done.
  {
    const p = onb.computePlan({ owns: true, signedIn: true, serverKnown: true, serverCompleted: false, pendingCompletion: false, tourAction: "autostart" });
    assertEqual("autostart mode", p.mode, "autostart");
    assertEqual("server false + fresh -> auto-start ALLOWED once", p.autoStart, true);
    assertEqual("auto-start -> mark local done", p.markLocalDone, true);
  }

  // Server false but tour.js is already RESUMING a cross-page walkthrough -> leave it be.
  {
    const p = onb.computePlan({ owns: true, signedIn: true, serverKnown: true, serverCompleted: false, pendingCompletion: false, tourAction: "resume" });
    assertEqual("resuming mode", p.mode, "resuming");
    assertEqual("resume -> no double auto-start", p.autoStart, false);
  }

  // Server false but tour.js is idle (already ran once on this browser / paused) -> no re-nag.
  {
    const p = onb.computePlan({ owns: true, signedIn: true, serverKnown: true, serverCompleted: false, pendingCompletion: false, tourAction: "idle" });
    assertEqual("idle mode", p.mode, "idle");
    assertEqual("idle -> no auto-start", p.autoStart, false);
  }
}

// ===========================================================================
// 4) recordCompletion — record now, else queue a pending-sync marker.
// ===========================================================================
async function testRecordCompletion() {
  console.log("\nrecordCompletion() (record vs queue):");

  // Not configured -> skipped (no backend/user to record against).
  {
    const r = await onb.recordCompletion({ configured: false, hasAuth: true, getToken: () => Promise.resolve("T"), apiBaseUrl: "http://x", fetchImpl: () => Promise.resolve(fakeResponse(200, {})) });
    assertEqual("not configured -> skipped", r.skipped, true);
    assertEqual("not configured -> not pending", r.pending, false);
  }

  // Configured, auth here, signed in, 200 -> patched; and the request is the PATCH we expect.
  {
    let seen = {};
    const r = await onb.recordCompletion({
      configured: true, hasAuth: true,
      getToken: () => Promise.resolve("TOKZ"),
      apiBaseUrl: "http://x",
      fetchImpl: (u, o) => { seen = { u, o }; return Promise.resolve(fakeResponse(200, {})); },
    });
    assertEqual("configured+auth+200 -> patched", r.patched, true);
    assertEqual("patched -> not pending", r.pending, false);
    assertEqual("recorded via PATCH", seen.o.method, "PATCH");
    assertEqual("recorded body", seen.o.body, '{"onboardingCompleted":true}');
    assertEqual("recorded with Bearer", seen.o.headers.Authorization, "Bearer TOKZ");
  }

  // Configured, no auth on THIS page -> queue for the next auth-capable load (no fetch).
  {
    let called = false;
    const r = await onb.recordCompletion({ configured: true, hasAuth: false, getToken: () => Promise.resolve("T"), apiBaseUrl: "http://x", fetchImpl: () => { called = true; return Promise.resolve(fakeResponse(200, {})); } });
    assertEqual("no auth -> pending", r.pending, true);
    assertEqual("no auth -> no fetch", called, false);
  }

  // Configured, auth, but signed OUT (null token) -> pending (retry next load), no PATCH sent.
  {
    let called = false;
    const r = await onb.recordCompletion({ configured: true, hasAuth: true, getToken: () => Promise.resolve(null), apiBaseUrl: "http://x", fetchImpl: () => { called = true; return Promise.resolve(fakeResponse(200, {})); } });
    assertEqual("signed-out -> pending", r.pending, true);
    assertEqual("signed-out -> no PATCH sent", called, false);
  }

  // Configured, auth, 401 -> pending (tolerated, retry next load).
  {
    const r = await onb.recordCompletion({ configured: true, hasAuth: true, getToken: () => Promise.resolve("T"), apiBaseUrl: "http://x", fetchImpl: () => Promise.resolve(fakeResponse(401, {})) });
    assertEqual("401 -> pending", r.pending, true);
    assertEqual("401 -> not patched", r.patched, false);
  }

  // Configured, auth, network throw -> pending (never throws out).
  {
    const r = await onb.recordCompletion({ configured: true, hasAuth: true, getToken: () => Promise.resolve("T"), apiBaseUrl: "http://x", fetchImpl: () => Promise.reject(new Error("down")) });
    assertEqual("network error -> pending", r.pending, true);
  }
}

// ===========================================================================
// 5) isConfigured / readCompleted — the tiny pure guards.
// ===========================================================================
function testGuards() {
  console.log("\nisConfigured() + readCompleted() (pure guards):");
  assertEqual("empty apiKey -> not configured", onb.isConfigured({ apiKey: "" }), false);
  assertEqual("whitespace apiKey -> not configured", onb.isConfigured({ apiKey: "   " }), false);
  assertEqual("missing config -> not configured", onb.isConfigured(undefined), false);
  assertEqual("real apiKey -> configured", onb.isConfigured({ apiKey: "AIzaXYZ" }), true);
  assertEqual("readCompleted true", onb.readCompleted({ onboardingCompleted: true }), true);
  assertEqual("readCompleted missing -> false", onb.readCompleted({}), false);
  assertEqual("readCompleted non-bool -> false", onb.readCompleted({ onboardingCompleted: "yes" }), false);
}

// ===========================================================================
// 6) FAKE-DOM INTEGRATION — load onboarding.js's REAL browser bootstrap under a tiny
//    window/document, driving OSKAuth/OSKTour/fetch stubs. This exercises the actual
//    event wiring + auto-start path the pages run.
// ===========================================================================

// A minimal window event target + the browser globals onboarding.js touches. Returns a
// handle to inspect fetch calls, storage, startTour calls, and to dispatch the finish event.
function makeBrowser(opts) {
  const o = opts || {};
  const listeners = {};
  const storage = fakeStorage();
  const fetchCalls = [];
  const startCalls = [];

  // fetch stub: routes by URL/method. GET /api/v1/me -> the configured "me" body; PATCH
  // /api/v1/me/lifecycle -> the configured lifecycle status. Records every call.
  function fetchImpl(url, init) {
    const method = (init && init.method) || "GET";
    fetchCalls.push({ url, method, init: init || {} });
    if (/\/api\/v1\/me\/lifecycle$/.test(url)) {
      return Promise.resolve(fakeResponse(o.lifecycleStatus || 204, {}));
    }
    if (/\/api\/v1\/me$/.test(url)) {
      return Promise.resolve(fakeResponse(o.meStatus || 200, { onboardingCompleted: o.serverCompleted === true }));
    }
    return Promise.resolve(fakeResponse(200, {}));
  }

  // The pure tour API is what the real browser tour.js exposes as window.OSKTour, plus the
  // imperative surface (startTour/markFirstRunDone/isFirstRunDone/loadProgress/currentPageKey)
  // it attaches in the browser — we bind those to the fake storage + a startTour spy.
  const OSKTour = Object.assign({}, tour, {
    startTour: (pageKey, walk) => { startCalls.push({ pageKey, walk }); },
    markFirstRunDone: () => storage.setItem(tour.STORAGE.firstRun, "1"),
    isFirstRunDone: () => storage.getItem(tour.STORAGE.firstRun) === "1",
    loadProgress: () => tour.parseProgress(storage.getItem(tour.STORAGE.progress)),
    currentPageKey: () => o.pageKey || "app",
  });

  const OSKAuth = o.hasAuth === false ? undefined : {
    ready: Promise.resolve(),
    getIdToken: () => Promise.resolve(o.token === undefined ? "IDTOKEN" : o.token),
  };

  const win = {
    __APP_CONFIG__: {
      apiBaseUrl: o.apiBaseUrl || "http://127.0.0.1:8080",
      firebase: { apiKey: o.configured === false ? "" : "AIzaTESTKEY" },
      tour: { enabled: true, autoStartFirstRun: true },
    },
    OSKAuth,
    OSKTour,
    localStorage: storage,
    fetch: fetchImpl,
    console: { debug: () => {} },
    addEventListener: (name, cb) => { (listeners[name] = listeners[name] || []).push(cb); },
    removeEventListener: () => {},
    dispatchEvent: (ev) => { (listeners[ev.type] || []).forEach((cb) => cb(ev)); return true; },
  };
  // Seed any pre-existing localStorage (e.g. a queued pending-sync marker).
  if (o.seed) { Object.keys(o.seed).forEach((k) => storage.setItem(k, o.seed[k])); }

  return { win, storage, fetchCalls, startCalls, dispatchFinish: () => win.dispatchEvent({ type: "osk:tour:finished" }) };
}

// Load onboarding.js's browser bootstrap against a fresh fake browser. We set the globals
// it reads (window/document/navigator), clear the require cache, and re-require so its IIFE
// runs. `webdriver` toggles the automation guard.
function loadOnboardingInBrowser(browser, webdriver) {
  global.window = browser.win;
  global.document = { createEvent: undefined }; // onboarding.js only `typeof document` checks
  // Node >=21 pre-defines a read-only `navigator` global, so plain assignment throws —
  // define it (configurable, so each scenario can redefine) to control navigator.webdriver.
  Object.defineProperty(global, "navigator", {
    value: { webdriver: webdriver === true },
    configurable: true,
    writable: true,
  });
  delete require.cache[require.resolve(ONB_PATH)];
  require(ONB_PATH); // runs the browser IIFE as a side effect
}

function teardownBrowser() {
  delete global.window;
  delete global.document;
  // Leave `navigator` defined (configurable) — the next scenario redefines it.
  delete require.cache[require.resolve(ONB_PATH)];
}

// Let chained promises settle.
const flush = () => new Promise((r) => setImmediate(r));
async function settle() { for (let i = 0; i < 6; i++) { await flush(); } }

async function testBrowserIntegration() {
  console.log("\nfake-DOM integration (real browser bootstrap):");

  // (a) Signed in + server says NOT completed + fresh browser -> auto-start fires ONCE,
  //     and it does NOT PATCH on load (nothing to record yet).
  {
    const b = makeBrowser({ serverCompleted: false, pageKey: "app" });
    loadOnboardingInBrowser(b, false);
    await settle();
    assertEqual("suppress flag set so tour.js yields", b.win.__oskSuppressAutoStart, true);
    assertEqual("server false -> auto-start fired once", b.startCalls.length, 1);
    assertEqual("auto-start on the right page as a walkthrough", b.startCalls[0].pageKey, "app");
    const patched = b.fetchCalls.filter((c) => /lifecycle/.test(c.url));
    assertEqual("no PATCH on load (nothing completed yet)", patched.length, 0);
    teardownBrowser();
  }

  // (b) Signed in + server says COMPLETED -> auto-start SUPPRESSED; local first-run marked.
  {
    const b = makeBrowser({ serverCompleted: true, pageKey: "app" });
    loadOnboardingInBrowser(b, false);
    await settle();
    assertEqual("server true -> no auto-start", b.startCalls.length, 0);
    assertEqual("server true -> local first-run reconciled", b.storage.getItem(tour.STORAGE.firstRun), "1");
    teardownBrowser();
  }

  // (c) Completing the tour on a signed-in auth page PATCHes { onboardingCompleted:true }
  //     with the Bearer token.
  {
    const b = makeBrowser({ serverCompleted: true, pageKey: "app", lifecycleStatus: 204 });
    loadOnboardingInBrowser(b, false);
    await settle();
    b.dispatchFinish();
    await settle();
    const patched = b.fetchCalls.filter((c) => /lifecycle/.test(c.url));
    assertEqual("completion -> exactly one PATCH", patched.length, 1);
    assertEqual("PATCH method", patched[0].method, "PATCH");
    assertEqual("PATCH body", patched[0].init.body, '{"onboardingCompleted":true}');
    assertEqual("PATCH Bearer", patched[0].init.headers.Authorization, "Bearer IDTOKEN");
    assertEqual("no pending marker left after success", b.storage.getItem(onb.STORAGE.pendingSync), null);
    teardownBrowser();
  }

  // (d) A 401 on the completion PATCH is TOLERATED: no throw, a pending marker is queued.
  {
    const b = makeBrowser({ serverCompleted: true, pageKey: "app", lifecycleStatus: 401 });
    loadOnboardingInBrowser(b, false);
    await settle();
    b.dispatchFinish();
    await settle();
    assertEqual("401 on record -> pending queued", b.storage.getItem(onb.STORAGE.pendingSync), "1");
    teardownBrowser();
  }

  // (e) Tour finishes on a page with NO auth (e.g. /help): completion is queued, no fetch.
  {
    const b = makeBrowser({ hasAuth: false, pageKey: "help" });
    loadOnboardingInBrowser(b, false);
    await settle();
    assertEqual("no auth -> we do not own auto-start", b.win.__oskSuppressAutoStart, undefined);
    b.dispatchFinish();
    await settle();
    assertEqual("no-auth completion -> pending queued", b.storage.getItem(onb.STORAGE.pendingSync), "1");
    assertEqual("no-auth completion -> no fetch attempted", b.fetchCalls.length, 0);
    teardownBrowser();
  }

  // (f) A queued pending marker is FLUSHED on the next signed-in auth load (retry-on-load).
  {
    const b = makeBrowser({ serverCompleted: false, pageKey: "app", seed: { [onb.STORAGE.pendingSync]: "1" } });
    loadOnboardingInBrowser(b, false);
    await settle();
    const patched = b.fetchCalls.filter((c) => /lifecycle/.test(c.url));
    assertEqual("pending on load -> flushed via PATCH", patched.length, 1);
    assertEqual("pending marker cleared after flush", b.storage.getItem(onb.STORAGE.pendingSync), null);
    assertEqual("pending flush -> no auto-start (they already finished)", b.startCalls.length, 0);
    teardownBrowser();
  }

  // (g) Automated browser (navigator.webdriver) -> never auto-pops (mirrors tour.js's guard).
  {
    const b = makeBrowser({ serverCompleted: false, pageKey: "app" });
    loadOnboardingInBrowser(b, true);
    await settle();
    assertEqual("webdriver -> no auto-start", b.startCalls.length, 0);
    teardownBrowser();
  }

  // (h) Not configured (committed default apiKey="") -> we don't own auto-start; a completion
  //     records nothing (tour.js's localStorage flow stands).
  {
    const b = makeBrowser({ configured: false, pageKey: "app" });
    loadOnboardingInBrowser(b, false);
    await settle();
    assertEqual("not configured -> no ownership", b.win.__oskSuppressAutoStart, undefined);
    b.dispatchFinish();
    await settle();
    assertEqual("not configured -> no fetch on completion", b.fetchCalls.filter((c) => /lifecycle/.test(c.url)).length, 0);
    assertEqual("not configured -> no pending marker", b.storage.getItem(onb.STORAGE.pendingSync), null);
    teardownBrowser();
  }
}

// ---------------------------------------------------------------------------
(async function main() {
  await testPatchLifecycle();
  await testFetchState();
  testComputePlan();
  await testRecordCompletion();
  testGuards();
  await testBrowserIntegration();

  console.log("\n----------------------------------------");
  console.log("onboarding simulation: " + passed + " passed, " + failed + " failed");
  if (failed > 0) { process.exitCode = 1; }
})();
