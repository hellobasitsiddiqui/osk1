// geolocation.simulation.cjs — headless Node simulation of the OSK-152 device
// location wrapper (web/geolocation.js).
//
// WHY this exists: the real thing needs either a Capacitor Android device (to
// exercise @capacitor/geolocation) or a browser with a live GPS + a human tapping
// "Allow" on the OS permission prompt — none of which exists in CI. But every bit
// of decision logic in geolocation.js is a pure, dependency-injected helper that
// the module exports for exactly this reason. This script require()s those exports
// and drives them with a STUBBED Capacitor plugin, a STUBBED navigator.geolocation
// and a FAKE DOM, asserting:
//   - detectMode picks NATIVE / WEB / NONE from the injected environment,
//   - normalizePosition collapses the (identical) Capacitor + browser position
//     shapes into one object, tags the source, and returns null for junk,
//   - normalizePermissionState maps Capacitor strings to granted/denied/prompt,
//   - formatCoords renders 5-dp "lat, lng" and an em-dash for no fix,
//   - the NATIVE controller requests permission then reads a position (and rejects
//     with reason "permission-denied" when the plugin reports a denial),
//   - the WEB fallback wraps navigator.geolocation (success -> normalized "web"
//     position; PositionError code 1 -> reason "permission-denied"),
//   - the NONE path is a graceful no-op: requestPermission -> "unavailable",
//     getCurrentPosition rejects with reason "unsupported",
//   - the UI hook renders "Locating…" -> the coordinates on success, the denied
//     message on a denied fix, and "unavailable" when there's no backend, using
//     textContent ONLY (a poisoned innerHTML setter must never fire).
//
// It is a plain assertion harness (no test framework, no deps): it prints each
// check and exits non-zero on any failure, so it doubles as a CI-friendly gate.
// It is a `.cjs` file OUTSIDE web/e2e/tests/, so Playwright (testDir ./tests)
// never picks it up.
//
// Run:  node web/e2e/geolocation.simulation.cjs

"use strict";

const path = require("node:path");
// Require the REAL module under test. geolocation.js is a classic browser script
// that also exports its pure helpers when required from Node (its browser
// bootstrap is skipped because `window` is undefined here), so this asserts the
// SAME code the device/browser runs.
const geo = require(path.resolve(__dirname, "..", "geolocation.js"));

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

// ---------------------------------------------------------------------------
// Fakes.
// ---------------------------------------------------------------------------

// A Capacitor-position-shaped reading (the browser GeolocationPosition has the
// same shape, which is the whole point of one normalizer).
function fakeReading(lat, lng, accuracy) {
  return { coords: { latitude: lat, longitude: lng, accuracy: accuracy }, timestamp: 1700000000000 };
}

// A stubbed Capacitor runtime whose Geolocation plugin returns scripted results.
// `permission` is what requestPermissions() resolves { location } to; `position`
// is what getCurrentPosition() resolves to (or an Error to reject with).
function fakeCapacitor(opts) {
  opts = opts || {};
  const calls = { requestPermissions: 0, getCurrentPosition: 0 };
  return {
    calls: calls,
    capacitor: {
      isNativePlatform: function () {
        return true;
      },
      Plugins: {
        Geolocation: {
          requestPermissions: function () {
            calls.requestPermissions++;
            return Promise.resolve({ location: opts.permission || "granted", coarseLocation: opts.permission || "granted" });
          },
          getCurrentPosition: function (options) {
            calls.getCurrentPosition++;
            calls.lastOptions = options;
            if (opts.position instanceof Error) {
              return Promise.reject(opts.position);
            }
            return Promise.resolve(opts.position || fakeReading(51.5074, -0.1278, 12));
          },
        },
      },
    },
  };
}

// A stubbed browser navigator.geolocation. `mode` = "ok" resolves a position;
// "denied" invokes the error callback with PositionError code 1.
function fakeNavigatorGeolocation(mode) {
  return {
    getCurrentPosition: function (onSuccess, onError) {
      if (mode === "denied") {
        onError({ code: 1, message: "User denied Geolocation" });
        return;
      }
      onSuccess(fakeReading(40.7128, -74.006, 30));
    },
  };
}

// Tiny fake DOM: elements track textContent + a poisoned innerHTML setter (so the
// textContent-only, XSS-safe contract is machine-checked) + click listeners.
const domState = { innerHTMLUsed: false };

class FakeElement {
  constructor(tag) {
    this.tagName = String(tag || "").toUpperCase();
    this.textContent = "";
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

// A promise that resolves after all currently-queued microtasks/timeouts, so we
// can await the UI's async render settling before asserting.
function flush() {
  return new Promise(function (resolve) {
    setTimeout(resolve, 0);
  });
}

async function main() {
  const M = geo.OSK_GEO_MODE;

  // -------------------------------------------------------------------------
  console.log("detectMode:");
  // -------------------------------------------------------------------------
  assertEqual("native env -> NATIVE", geo.detectMode({ capacitor: fakeCapacitor().capacitor }), M.NATIVE);
  assertEqual(
    "web env (navigator only) -> WEB",
    geo.detectMode({ geolocation: fakeNavigatorGeolocation("ok") }),
    M.WEB
  );
  assertEqual("empty env -> NONE", geo.detectMode({}), M.NONE);
  assertEqual("undefined env -> NONE", geo.detectMode(), M.NONE);
  // A Capacitor runtime that is NOT native (a browser with the JS shim) must not
  // be treated as native.
  assertEqual(
    "capacitor not-native -> NONE",
    geo.detectMode({ capacitor: { isNativePlatform: function () { return false; }, Plugins: { Geolocation: {} } } }),
    M.NONE
  );
  // A native runtime whose Geolocation plugin isn't installed yet must not be
  // treated as native (guards a shell built before `cap sync`).
  assertEqual(
    "native but no plugin -> NONE",
    geo.detectMode({ capacitor: { isNativePlatform: function () { return true; }, Plugins: {} } }),
    M.NONE
  );

  // -------------------------------------------------------------------------
  console.log("normalizePosition:");
  // -------------------------------------------------------------------------
  const np = geo.normalizePosition(fakeReading(51.5074, -0.1278, 12), "native");
  assertEqual("latitude carried", np.latitude, 51.5074);
  assertEqual("longitude carried", np.longitude, -0.1278);
  assertEqual("accuracy carried", np.accuracy, 12);
  assertEqual("timestamp carried", np.timestamp, 1700000000000);
  assertEqual("source tagged", np.source, "native");
  assertEqual("null for missing coords", geo.normalizePosition({}, "web"), null);
  assertEqual("null for non-numeric lat", geo.normalizePosition({ coords: { latitude: "x", longitude: 1 } }, "web"), null);
  assertEqual("null for null raw", geo.normalizePosition(null, "web"), null);
  assertEqual(
    "accuracy defaults to null when absent",
    geo.normalizePosition({ coords: { latitude: 1, longitude: 2 } }, "web").accuracy,
    null
  );

  // -------------------------------------------------------------------------
  console.log("normalizePermissionState:");
  // -------------------------------------------------------------------------
  assertEqual("granted", geo.normalizePermissionState("granted"), "granted");
  assertEqual("denied", geo.normalizePermissionState("denied"), "denied");
  assertEqual("prompt", geo.normalizePermissionState("prompt"), "prompt");
  assertEqual("prompt-with-rationale -> prompt", geo.normalizePermissionState("prompt-with-rationale"), "prompt");
  assertEqual("unknown -> prompt", geo.normalizePermissionState("banana"), "prompt");

  // -------------------------------------------------------------------------
  console.log("formatCoords:");
  // -------------------------------------------------------------------------
  assertEqual("5-dp lat, lng", geo.formatCoords({ latitude: 51.5074, longitude: -0.1278 }), "51.50740, -0.12780");
  assertEqual("em-dash for null pos", geo.formatCoords(null), "—");
  assertEqual("em-dash for missing lng", geo.formatCoords({ latitude: 1 }), "—");

  // -------------------------------------------------------------------------
  console.log("controller — NATIVE (granted):");
  // -------------------------------------------------------------------------
  {
    const cap = fakeCapacitor({ permission: "granted", position: fakeReading(51.5074, -0.1278, 12) });
    const ctrl = geo.createGeolocation({ capacitor: cap.capacitor });
    assertEqual("mode() == native", ctrl.mode(), M.NATIVE);
    assertEqual("requestPermission -> granted", await ctrl.requestPermission(), "granted");
    const pos = await ctrl.getCurrentPosition();
    assertEqual("position latitude", pos.latitude, 51.5074);
    assertEqual("position source == native", pos.source, "native");
    assert("requestPermissions was called before read", cap.calls.requestPermissions >= 1);
    assert("getCurrentPosition was called", cap.calls.getCurrentPosition === 1);
    assert("default options passed through", cap.calls.lastOptions && cap.calls.lastOptions.enableHighAccuracy === true);
  }

  // -------------------------------------------------------------------------
  console.log("controller — NATIVE (denied):");
  // -------------------------------------------------------------------------
  {
    const cap = fakeCapacitor({ permission: "denied" });
    const ctrl = geo.createGeolocation({ capacitor: cap.capacitor });
    assertEqual("requestPermission -> denied", await ctrl.requestPermission(), "denied");
    let reason = null;
    try {
      await ctrl.getCurrentPosition();
    } catch (e) {
      reason = e.reason;
    }
    assertEqual("getCurrentPosition rejects permission-denied", reason, "permission-denied");
    assert("did NOT call getCurrentPosition after denial", cap.calls.getCurrentPosition === 0);
  }

  // -------------------------------------------------------------------------
  console.log("controller — WEB fallback:");
  // -------------------------------------------------------------------------
  {
    const ctrl = geo.createGeolocation({ geolocation: fakeNavigatorGeolocation("ok") });
    assertEqual("mode() == web", ctrl.mode(), M.WEB);
    assertEqual("requestPermission -> prompt (browser asks at read time)", await ctrl.requestPermission(), "prompt");
    const pos = await ctrl.getCurrentPosition();
    assertEqual("web position latitude", pos.latitude, 40.7128);
    assertEqual("web position source == web", pos.source, "web");
  }
  {
    const ctrl = geo.createGeolocation({ geolocation: fakeNavigatorGeolocation("denied") });
    let reason = null;
    try {
      await ctrl.getCurrentPosition();
    } catch (e) {
      reason = e.reason;
    }
    assertEqual("web denial maps to permission-denied", reason, "permission-denied");
  }

  // -------------------------------------------------------------------------
  console.log("controller — NONE (graceful no-op):");
  // -------------------------------------------------------------------------
  {
    const ctrl = geo.createGeolocation({});
    assertEqual("mode() == none", ctrl.mode(), M.NONE);
    assertEqual("requestPermission -> unavailable", await ctrl.requestPermission(), "unavailable");
    let reason = null;
    try {
      await ctrl.getCurrentPosition();
    } catch (e) {
      reason = e.reason;
    }
    assertEqual("getCurrentPosition rejects unsupported", reason, "unsupported");
  }

  // -------------------------------------------------------------------------
  console.log("UI hook — NATIVE success renders coordinates:");
  // -------------------------------------------------------------------------
  {
    domState.innerHTMLUsed = false;
    const cap = fakeCapacitor({ permission: "granted", position: fakeReading(51.5074, -0.1278, 12) });
    const ctrl = geo.createGeolocation({ capacitor: cap.capacitor });
    const button = new FakeElement("button");
    const output = new FakeElement("p");
    const statusEl = new FakeElement("span");
    const ui = geo.createGeoUi({ geo: ctrl, button: button, output: output, status: statusEl });
    assertEqual("initial output is idle em-dash", output.textContent, "—");
    // Simulate the user clicking the button (wires through addEventListener).
    button.dispatch("click");
    // The click handler is fire-and-forget; drive the same flow synchronously via
    // refresh() so we can await the settled DOM.
    await ui.refresh();
    assertEqual("output shows formatted coordinates", output.textContent, "51.50740, -0.12780");
    assertEqual("status phase == ok", statusEl.textContent, "ok");
    assert("innerHTML never used (XSS-safe)", domState.innerHTMLUsed === false);
  }

  // -------------------------------------------------------------------------
  console.log("UI hook — NATIVE denial renders the denied message:");
  // -------------------------------------------------------------------------
  {
    const cap = fakeCapacitor({ permission: "denied" });
    const ctrl = geo.createGeolocation({ capacitor: cap.capacitor });
    const output = new FakeElement("p");
    const statusEl = new FakeElement("span");
    const ui = geo.createGeoUi({ geo: ctrl, output: output, status: statusEl });
    await ui.refresh();
    assertEqual("output shows permission-denied copy", output.textContent, "Location permission denied");
    assertEqual("status phase == error", statusEl.textContent, "error");
  }

  // -------------------------------------------------------------------------
  console.log("UI hook — NONE renders unavailable (no read attempted):");
  // -------------------------------------------------------------------------
  {
    const ctrl = geo.createGeolocation({});
    const output = new FakeElement("p");
    const statusEl = new FakeElement("span");
    const ui = geo.createGeoUi({ geo: ctrl, output: output, status: statusEl });
    // Initial render already reports unsupported (no backend at all).
    assertEqual("initial output shows unavailable", output.textContent, "Location unavailable on this device");
    assertEqual("initial status phase == unsupported", statusEl.textContent, "unsupported");
    await ui.refresh();
    assertEqual("refresh keeps unavailable", output.textContent, "Location unavailable on this device");
  }

  // -------------------------------------------------------------------------
  console.log("UI hook — WEB success (button click wiring):");
  // -------------------------------------------------------------------------
  {
    const ctrl = geo.createGeolocation({ geolocation: fakeNavigatorGeolocation("ok") });
    const button = new FakeElement("button");
    const output = new FakeElement("p");
    geo.createGeoUi({ geo: ctrl, button: button, output: output });
    assert("click handler registered on button", (button._listeners.click || []).length === 1);
    button.dispatch("click");
    await flush();
    assertEqual("output shows web coordinates after click", output.textContent, "40.71280, -74.00600");
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
