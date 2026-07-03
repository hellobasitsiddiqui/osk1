// diagnostics.simulation.cjs — headless Node simulation of the OSK-169 QA diagnostics
// screen controller (web/diagnostics.js).
//
// WHY this exists: the real screen only shows its interesting values on a Capacitor Android
// DEVICE — native platform detection, loaded plugins, a GPS fix behind an OS permission
// prompt, and an FCM token that needs FCM configured (google-services.json, the OSK-165
// human gate) — none of which exists in CI. But every bit of decision + render logic in
// diagnostics.js is a pure, dependency-injected helper the module exports for exactly this
// reason. This script require()s those exports and drives them with a FAKE DOM + STUBBED
// geo/push/clipboard/capacitor, asserting:
//   - detectPlatform picks native-vs-web + the platform id from the injected bridge,
//   - pluginStatus reports each EXPECTED plugin present/absent,
//   - formatCoords / permissionLabel / geoErrorMessage / fcmState render the right strings,
//   - the controller paints the platform + plugin rows (native-all-present AND web-absent),
//   - GPS refresh renders coordinates on success, the denied copy on a denial, and the
//     "unavailable" state with no read attempted off-device,
//   - FCM refresh renders the token when present (and the copy button enables), the
//     "FCM not configured (OSK-165)" state when NO token arrives, the denied state, and the
//     "not available" state in a plain browser,
//   - copy-to-clipboard writes the token (and degrades when there's nothing to copy / no
//     clipboard),
//   - every render is XSS-safe: a poisoned innerHTML setter must NEVER fire, and a hostile
//     platform id / token reaches the DOM only via textContent.
//
// It is a plain assertion harness (no test framework, no deps): it prints each check and
// exits non-zero on any failure, so it doubles as a CI-friendly gate. It is a `.cjs` file
// OUTSIDE web/e2e/tests/, so Playwright (testDir ./tests) never picks it up.
//
// Run:  node web/e2e/diagnostics.simulation.cjs

"use strict";

const path = require("node:path");
// Require the REAL module under test. diagnostics.js is a classic browser script that also
// exports its pure helpers when required from Node (its browser bootstrap is skipped because
// `window` is undefined here), so this asserts the SAME code the device runs.
const diag = require(path.resolve(__dirname, "..", "diagnostics.js"));

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

// A promise that resolves after all currently-queued microtasks/timeouts, so we can await
// the controller's async render (permission -> position / token) settling before asserting.
function flush() {
  return new Promise(function (resolve) {
    setTimeout(resolve, 0);
  });
}

// ---------------------------------------------------------------------------
// Fake DOM. Elements track textContent + className + disabled + a POISONED innerHTML setter
// (so the textContent-only, XSS-safe contract is machine-checked) + click listeners. The
// FakeDoc lazily makes an element for any id the controller asks for.
// ---------------------------------------------------------------------------
const domState = { innerHTMLUsed: false };

class FakeElement {
  constructor(id) {
    this.id = id;
    this.tagName = "DIV";
    this.textContent = "";
    this.className = "";
    this.disabled = false;
    this.hidden = false;
    this._listeners = {};
  }
  addEventListener(type, fn) {
    (this._listeners[type] = this._listeners[type] || []).push(fn);
  }
  dispatch(type) {
    (this._listeners[type] || []).forEach(function (fn) {
      fn();
    });
  }
  set innerHTML(_v) {
    domState.innerHTMLUsed = true;
  }
  get innerHTML() {
    return "";
  }
}

class FakeDoc {
  constructor() {
    this._els = {};
  }
  getElementById(id) {
    if (!this._els[id]) {
      this._els[id] = new FakeElement(id);
    }
    return this._els[id];
  }
}

// ---------------------------------------------------------------------------
// Stubbed device wrappers (same shapes as window.OSKGeolocation / window.OSKPush).
// ---------------------------------------------------------------------------
function geoError(reason) {
  const e = new Error(reason);
  e.reason = reason;
  return e;
}

// Fake OSK-152 geolocation controller.
//   opts.mode       — "native" | "web" | "none"
//   opts.permission — what requestPermission() resolves to
//   opts.pos        — normalized position getCurrentPosition() resolves to
//   opts.error      — a reason string to reject getCurrentPosition() with
function fakeGeo(opts) {
  opts = opts || {};
  return {
    mode: function () {
      return opts.mode || "none";
    },
    requestPermission: function () {
      return Promise.resolve(opts.permission || "granted");
    },
    getCurrentPosition: function () {
      if (opts.error) {
        return Promise.reject(geoError(opts.error));
      }
      return Promise.resolve(
        opts.pos || { latitude: 51.5074, longitude: -0.1278, accuracy: 12, source: "native" }
      );
    },
  };
}

// Fake OSK-150 push controller.
//   opts.mode       — "native" | "none"
//   opts.permission — what requestPermission() resolves to
//   opts.token      — what lastToken() returns (null => never a token: the OSK-165 case)
function fakePush(opts) {
  opts = opts || {};
  const calls = { requestPermission: 0, register: 0 };
  return {
    calls: calls,
    mode: function () {
      return opts.mode || "none";
    },
    requestPermission: function () {
      calls.requestPermission++;
      return Promise.resolve(opts.permission || "granted");
    },
    register: function () {
      calls.register++;
      return Promise.resolve({ status: "registering" });
    },
    lastToken: function () {
      return opts.token != null ? opts.token : null;
    },
  };
}

// Fake Capacitor bridge.
//   opts.native   — isNativePlatform()
//   opts.platform — getPlatform()
//   opts.plugins  — the Plugins map (presence = truthy value under the plugin name)
function fakeCapacitor(opts) {
  opts = opts || {};
  return {
    isNativePlatform: function () {
      return !!opts.native;
    },
    getPlatform: function () {
      return opts.platform || "web";
    },
    Plugins: opts.plugins || {},
  };
}

// Fake clipboard. Records what was written; can be made to fail.
function fakeClipboard(opts) {
  opts = opts || {};
  const record = { written: null, calls: 0 };
  return {
    record: record,
    writeText: function (text) {
      record.calls++;
      record.written = text;
      return opts.fail ? Promise.reject(new Error("denied")) : Promise.resolve();
    },
  };
}

// Build a controller with instant token polling so the sim is deterministic + fast.
function makeCtrl(extra) {
  const doc = new FakeDoc();
  const deps = Object.assign(
    {
      doc: doc,
      wait: function () {
        return Promise.resolve();
      },
      fcmTokenRetries: 2,
      fcmTokenIntervalMs: 0,
    },
    extra || {}
  );
  const ctrl = diag.createDiagnostics(deps);
  return { ctrl: ctrl, doc: doc };
}
function text(doc, id) {
  return doc.getElementById(id).textContent;
}
function cls(doc, id) {
  return doc.getElementById(id).className;
}

const ALL_PLUGINS = { SplashScreen: {}, Geolocation: {}, PushNotifications: {} };

async function main() {
  // -------------------------------------------------------------------------
  console.log("detectPlatform:");
  // -------------------------------------------------------------------------
  {
    const native = diag.detectPlatform({ capacitor: fakeCapacitor({ native: true, platform: "android" }) });
    assertEqual("native flag true", native.native, true);
    assertEqual("platform android", native.platform, "android");
    assertEqual("hasCapacitor true", native.hasCapacitor, true);

    const web = diag.detectPlatform({ capacitor: undefined });
    assertEqual("no bridge -> not native", web.native, false);
    assertEqual("no bridge -> platform web", web.platform, "web");
    assertEqual("no bridge -> hasCapacitor false", web.hasCapacitor, false);

    // A browser JS shim (bridge present but NOT native) must not read as native.
    const shim = diag.detectPlatform({ capacitor: fakeCapacitor({ native: false, platform: "web" }) });
    assertEqual("shim -> not native", shim.native, false);
    assertEqual("shim -> hasCapacitor true", shim.hasCapacitor, true);
  }

  // -------------------------------------------------------------------------
  console.log("pluginStatus:");
  // -------------------------------------------------------------------------
  {
    const all = diag.pluginStatus({ capacitor: fakeCapacitor({ native: true, plugins: ALL_PLUGINS }) });
    assertEqual("three expected plugins", all.length, 3);
    assertEqual("SplashScreen present", all[0].present, true);
    assertEqual("Geolocation present", all[1].present, true);
    assertEqual("PushNotifications present", all[2].present, true);
    assertEqual("order: SplashScreen first", all[0].name, "SplashScreen");

    // Native shell missing the Geolocation plugin (e.g. pre `cap sync`).
    const partial = diag.pluginStatus({
      capacitor: fakeCapacitor({ native: true, plugins: { SplashScreen: {}, PushNotifications: {} } }),
    });
    assertEqual("missing plugin reported absent", partial[1].present, false);

    // Plain browser: no bridge -> all absent.
    const none = diag.pluginStatus({ capacitor: undefined });
    assertEqual("no bridge -> SplashScreen absent", none[0].present, false);
    assertEqual("no bridge -> PushNotifications absent", none[2].present, false);
  }

  // -------------------------------------------------------------------------
  console.log("formatCoords / permissionLabel / geoErrorMessage:");
  // -------------------------------------------------------------------------
  assertEqual("coords 5dp", diag.formatCoords({ latitude: 51.5074, longitude: -0.1278 }), "51.50740, -0.12780");
  assertEqual("coords em-dash for null", diag.formatCoords(null), "—");
  assertEqual("perm granted", diag.permissionLabel("granted"), "Granted");
  assertEqual("perm denied", diag.permissionLabel("denied"), "Denied");
  assertEqual("perm prompt", diag.permissionLabel("prompt"), "Not yet requested");
  assertEqual("perm unavailable", diag.permissionLabel("unavailable"), "Unavailable");
  assertEqual("perm unknown", diag.permissionLabel("banana"), "Unknown");
  assertEqual("geo err denied", diag.geoErrorMessage("permission-denied"), "Location permission denied");
  assertEqual("geo err unsupported", diag.geoErrorMessage("unsupported"), "Location unavailable on this device");

  // -------------------------------------------------------------------------
  console.log("fcmState (pure state deriver, incl. the OSK-165 gate):");
  // -------------------------------------------------------------------------
  {
    const web = diag.fcmState({ mode: "none" });
    assertEqual("web -> unavailable", web.phase, "unavailable");
    assertEqual("web -> no token", web.token, null);

    const denied = diag.fcmState({ mode: "native", permission: "denied" });
    assertEqual("denied -> denied", denied.phase, "denied");

    const gated = diag.fcmState({ mode: "native", permission: "granted", token: null });
    assertEqual("granted + no token -> no-token", gated.phase, "no-token");
    assert("no-token message names OSK-165", /OSK-165/.test(gated.message));

    const blank = diag.fcmState({ mode: "native", permission: "granted", token: "   " });
    assertEqual("blank token -> no-token", blank.phase, "no-token");

    const ok = diag.fcmState({ mode: "native", permission: "granted", token: "fcm-diag-token-abc123" });
    assertEqual("granted + token -> ok", ok.phase, "ok");
    assertEqual("ok carries trimmed token", ok.token, "fcm-diag-token-abc123");
  }

  // -------------------------------------------------------------------------
  console.log("controller — platform + plugins render (NATIVE, all present):");
  // -------------------------------------------------------------------------
  {
    domState.innerHTMLUsed = false;
    const cap = fakeCapacitor({ native: true, platform: "android", plugins: ALL_PLUGINS });
    const { ctrl, doc } = makeCtrl({ capacitor: cap, geo: fakeGeo({ mode: "native" }), push: fakePush({ mode: "native" }) });
    ctrl.renderPlatform();
    ctrl.renderPlugins();
    assertEqual("platform value shows native + android", text(doc, "diag-platform-value"), "Native (Capacitor) — android");
    assertEqual("platform dot ok", cls(doc, "diag-platform-dot"), "dot dot--ok");
    assertEqual("SplashScreen available", text(doc, "diag-plugin-SplashScreen"), "Available");
    assertEqual("SplashScreen dot ok", cls(doc, "diag-plugin-SplashScreen-dot"), "dot dot--ok");
    assertEqual("PushNotifications available", text(doc, "diag-plugin-PushNotifications"), "Available");
    assert("innerHTML never used (XSS-safe)", domState.innerHTMLUsed === false);
  }

  // -------------------------------------------------------------------------
  console.log("controller — platform + plugins render (WEB, all absent):");
  // -------------------------------------------------------------------------
  {
    const { ctrl, doc } = makeCtrl({ capacitor: undefined, geo: fakeGeo({ mode: "web" }), push: fakePush({ mode: "none" }) });
    ctrl.renderPlatform();
    ctrl.renderPlugins();
    assertEqual("platform value shows web browser", text(doc, "diag-platform-value"), "Web browser — web");
    assertEqual("platform dot warn (not native)", cls(doc, "diag-platform-dot"), "dot dot--warn");
    assertEqual("Geolocation not loaded", text(doc, "diag-plugin-Geolocation"), "Not loaded");
    assertEqual("Geolocation dot warn", cls(doc, "diag-plugin-Geolocation-dot"), "dot dot--warn");
  }

  // -------------------------------------------------------------------------
  console.log("controller — GPS success (NATIVE granted):");
  // -------------------------------------------------------------------------
  {
    const { ctrl, doc } = makeCtrl({
      capacitor: fakeCapacitor({ native: true, platform: "android", plugins: ALL_PLUGINS }),
      geo: fakeGeo({ mode: "native", permission: "granted", pos: { latitude: 51.5074, longitude: -0.1278, accuracy: 8 } }),
      push: fakePush({ mode: "native" }),
    });
    await ctrl.refreshGps();
    assertEqual("gps coords rendered", text(doc, "diag-gps-coords"), "51.50740, -0.12780");
    assertEqual("gps permission granted", text(doc, "diag-gps-permission"), "Granted");
    assertEqual("gps accuracy rendered", text(doc, "diag-gps-accuracy"), "±8 m");
    assertEqual("gps status phase ok", text(doc, "diag-gps-status"), "ok");
    assertEqual("gps dot ok", cls(doc, "diag-gps-dot"), "dot dot--ok");
  }

  // -------------------------------------------------------------------------
  console.log("controller — GPS denied (NATIVE):");
  // -------------------------------------------------------------------------
  {
    const { ctrl, doc } = makeCtrl({
      capacitor: fakeCapacitor({ native: true, plugins: ALL_PLUGINS }),
      geo: fakeGeo({ mode: "native", permission: "denied", error: "permission-denied" }),
      push: fakePush({ mode: "native" }),
    });
    await ctrl.refreshGps();
    assertEqual("gps shows denied copy", text(doc, "diag-gps-coords"), "Location permission denied");
    assertEqual("gps permission denied", text(doc, "diag-gps-permission"), "Denied");
    assertEqual("gps status phase error", text(doc, "diag-gps-status"), "error");
    assertEqual("gps dot down", cls(doc, "diag-gps-dot"), "dot dot--down");
  }

  // -------------------------------------------------------------------------
  console.log("controller — GPS unsupported (NONE, no read attempted):");
  // -------------------------------------------------------------------------
  {
    let read = 0;
    const geo = fakeGeo({ mode: "none" });
    const orig = geo.getCurrentPosition;
    geo.getCurrentPosition = function () {
      read++;
      return orig();
    };
    const { ctrl, doc } = makeCtrl({ capacitor: undefined, geo: geo, push: fakePush({ mode: "none" }) });
    await ctrl.refreshGps();
    assertEqual("gps shows unavailable", text(doc, "diag-gps-coords"), "Location unavailable on this device");
    assertEqual("gps status unsupported", text(doc, "diag-gps-status"), "unsupported");
    assert("no position read attempted off-device", read === 0);
  }

  // -------------------------------------------------------------------------
  console.log("controller — FCM token present (NATIVE granted) + copy:");
  // -------------------------------------------------------------------------
  {
    domState.innerHTMLUsed = false;
    const clip = fakeClipboard();
    const push = fakePush({ mode: "native", permission: "granted", token: "fcm-diag-token-abc123" });
    const { ctrl, doc } = makeCtrl({
      capacitor: fakeCapacitor({ native: true, plugins: ALL_PLUGINS }),
      geo: fakeGeo({ mode: "native" }),
      push: push,
      clipboard: clip,
    });
    await ctrl.refreshFcm();
    assertEqual("fcm token rendered", text(doc, "diag-fcm-token"), "fcm-diag-token-abc123");
    assertEqual("fcm permission granted", text(doc, "diag-fcm-permission"), "Granted");
    assertEqual("fcm status ok", text(doc, "diag-fcm-status"), "ok");
    assertEqual("fcm dot ok", cls(doc, "diag-fcm-dot"), "dot dot--ok");
    assertEqual("copy button enabled", doc.getElementById("diag-fcm-copy").disabled, false);
    assert("register() was called", push.calls.register === 1);
    assertEqual("lastFcmToken remembered", ctrl.lastFcmToken(), "fcm-diag-token-abc123");
    // Copy it.
    const copied = await ctrl.copyToken();
    assertEqual("copy returns true", copied, true);
    assertEqual("clipboard got the token", clip.record.written, "fcm-diag-token-abc123");
    assertEqual("copy status Copied!", text(doc, "diag-fcm-copy-status"), "Copied!");
    assert("innerHTML never used (XSS-safe)", domState.innerHTMLUsed === false);
  }

  // -------------------------------------------------------------------------
  console.log("controller — FCM no token: FCM not configured (OSK-165):");
  // -------------------------------------------------------------------------
  {
    const push = fakePush({ mode: "native", permission: "granted", token: null });
    const { ctrl, doc } = makeCtrl({
      capacitor: fakeCapacitor({ native: true, plugins: ALL_PLUGINS }),
      geo: fakeGeo({ mode: "native" }),
      push: push,
    });
    await ctrl.refreshFcm();
    assert("fcm token area names OSK-165", /OSK-165/.test(text(doc, "diag-fcm-token")));
    assertEqual("fcm status no-token", text(doc, "diag-fcm-status"), "no-token");
    assertEqual("fcm dot warn", cls(doc, "diag-fcm-dot"), "dot dot--warn");
    assertEqual("copy disabled (no token)", doc.getElementById("diag-fcm-copy").disabled, true);
    assert("register() still attempted (permission granted)", push.calls.register === 1);
  }

  // -------------------------------------------------------------------------
  console.log("controller — FCM permission denied (NATIVE, no register):");
  // -------------------------------------------------------------------------
  {
    const push = fakePush({ mode: "native", permission: "denied", token: null });
    const { ctrl, doc } = makeCtrl({
      capacitor: fakeCapacitor({ native: true, plugins: ALL_PLUGINS }),
      geo: fakeGeo({ mode: "native" }),
      push: push,
    });
    await ctrl.refreshFcm();
    assertEqual("fcm shows denied", text(doc, "diag-fcm-token"), "Notification permission denied");
    assertEqual("fcm permission denied", text(doc, "diag-fcm-permission"), "Denied");
    assertEqual("fcm status denied", text(doc, "diag-fcm-status"), "denied");
    assertEqual("fcm dot down", cls(doc, "diag-fcm-dot"), "dot dot--down");
    assert("register() NOT called after denial", push.calls.register === 0);
  }

  // -------------------------------------------------------------------------
  console.log("controller — FCM not available (WEB / plain browser):");
  // -------------------------------------------------------------------------
  {
    const push = fakePush({ mode: "none" });
    const { ctrl, doc } = makeCtrl({ capacitor: undefined, geo: fakeGeo({ mode: "web" }), push: push });
    await ctrl.refreshFcm();
    assert("fcm shows not available", /not available/i.test(text(doc, "diag-fcm-token")));
    assertEqual("fcm status unavailable", text(doc, "diag-fcm-status"), "unavailable");
    assertEqual("fcm permission unavailable", text(doc, "diag-fcm-permission"), "Unavailable");
    assert("register() never called off-device", push.calls.register === 0);
  }

  // -------------------------------------------------------------------------
  console.log("controller — copy degrades: nothing to copy / no clipboard:");
  // -------------------------------------------------------------------------
  {
    // Nothing observed yet (never refreshed) -> nothing to copy.
    const { ctrl, doc } = makeCtrl({ capacitor: undefined, geo: fakeGeo({ mode: "web" }), push: fakePush({ mode: "none" }), clipboard: fakeClipboard() });
    const r = await ctrl.copyToken();
    assertEqual("copy with no token -> false", r, false);
    assertEqual("copy status Nothing to copy", text(doc, "diag-fcm-copy-status"), "Nothing to copy");
  }
  {
    // Token present but no clipboard API available.
    const { ctrl, doc } = makeCtrl({
      capacitor: fakeCapacitor({ native: true, plugins: ALL_PLUGINS }),
      geo: fakeGeo({ mode: "native" }),
      push: fakePush({ mode: "native", permission: "granted", token: "fcm-diag-token-abc123" }),
      clipboard: null,
    });
    await ctrl.refreshFcm();
    const r = await ctrl.copyToken();
    assertEqual("copy without clipboard -> false", r, false);
    assertEqual("copy status Clipboard unavailable", text(doc, "diag-fcm-copy-status"), "Clipboard unavailable");
  }
  {
    // Clipboard write rejects.
    const clip = fakeClipboard({ fail: true });
    const { ctrl, doc } = makeCtrl({
      capacitor: fakeCapacitor({ native: true, plugins: ALL_PLUGINS }),
      geo: fakeGeo({ mode: "native" }),
      push: fakePush({ mode: "native", permission: "granted", token: "fcm-diag-token-abc123" }),
      clipboard: clip,
    });
    await ctrl.refreshFcm();
    const r = await ctrl.copyToken();
    assertEqual("copy failure -> false", r, false);
    assertEqual("copy status Copy failed", text(doc, "diag-fcm-copy-status"), "Copy failed");
  }

  // -------------------------------------------------------------------------
  console.log("controller — init() wires buttons + paints non-intrusive baselines:");
  // -------------------------------------------------------------------------
  {
    const push = fakePush({ mode: "native", permission: "granted", token: "fcm-diag-token-abc123" });
    const { ctrl, doc } = makeCtrl({
      capacitor: fakeCapacitor({ native: true, platform: "android", plugins: ALL_PLUGINS }),
      geo: fakeGeo({ mode: "native", permission: "granted", pos: { latitude: 1, longitude: 2, accuracy: 5 } }),
      push: push,
    });
    ctrl.init();
    // Baseline: platform + plugins painted immediately; GPS idle; FCM idle (no prompt fired).
    assertEqual("init paints platform", text(doc, "diag-platform-value"), "Native (Capacitor) — android");
    assertEqual("init GPS idle coords", text(doc, "diag-gps-coords"), "—");
    assertEqual("init FCM idle hint", text(doc, "diag-fcm-status"), "idle");
    assert("init did NOT prompt for push", push.calls.requestPermission === 0);
    // Now click the GPS refresh button — it must run a real read.
    doc.getElementById("diag-gps-refresh").dispatch("click");
    await flush();
    assertEqual("button-driven GPS read renders coords", text(doc, "diag-gps-coords"), "1.00000, 2.00000");
    // And the FCM check button registers + shows the token.
    doc.getElementById("diag-fcm-refresh").dispatch("click");
    await flush();
    assertEqual("button-driven FCM shows token", text(doc, "diag-fcm-token"), "fcm-diag-token-abc123");
    assert("button-driven FCM registered", push.calls.register === 1);
  }

  // -------------------------------------------------------------------------
  console.log("XSS-safe — hostile platform id + token reach DOM via textContent only:");
  // -------------------------------------------------------------------------
  {
    domState.innerHTMLUsed = false;
    const hostilePlatform = "<img src=x onerror=alert(1)>";
    const hostileToken = "</span><script>alert(2)</script>";
    const { ctrl, doc } = makeCtrl({
      capacitor: fakeCapacitor({ native: true, platform: hostilePlatform, plugins: ALL_PLUGINS }),
      geo: fakeGeo({ mode: "native" }),
      push: fakePush({ mode: "native", permission: "granted", token: hostileToken }),
      clipboard: fakeClipboard(),
    });
    ctrl.renderPlatform();
    await ctrl.refreshFcm();
    assertEqual("hostile platform stored as literal text", text(doc, "diag-platform-value"), "Native (Capacitor) — " + hostilePlatform);
    assertEqual("hostile token stored as literal text", text(doc, "diag-fcm-token"), hostileToken);
    assert("innerHTML NEVER used for hostile values (XSS-safe)", domState.innerHTMLUsed === false);
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
