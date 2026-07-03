/*
 * geolocation.js — OSK-152. Device location for the OpenSkeleton app, one small
 * wrapper that works on the Capacitor Android shell AND in a plain browser.
 *
 * WHAT it does: exposes a tiny promise API (`window.OSKGeolocation`) to request
 * location permission and read the current latitude/longitude, plus an optional
 * UI hook that renders the coordinates into a page widget (the ops page's
 * "Device location" panel). The full diagnostics screen is a separate ticket
 * (OSK-169) — this is deliberately the minimal read-one-position slice.
 *
 * THREE runtime modes, auto-detected (see oskGeoDetectMode):
 *   - NATIVE — inside the Capacitor Android WebView, where the native runtime
 *     injects `window.Capacitor` and (after `npx cap sync android` installs the
 *     plugin's native half) `Capacitor.Plugins.Geolocation`. Here we go through
 *     the plugin: requestPermissions() then getCurrentPosition(). The Android
 *     permissions it needs (ACCESS_FINE/COARSE_LOCATION) are declared in
 *     android/app/src/main/AndroidManifest.xml.
 *   - WEB — a normal browser (the live Firebase-hosted site) with no Capacitor
 *     runtime but a real `navigator.geolocation`. We fall back to the standard
 *     web Geolocation API so the same button works on the web deploy. The browser
 *     shows its own native permission prompt at read time.
 *   - NONE — neither is available (e.g. an old browser, or a locked-down context
 *     with geolocation disabled). Everything degrades to a graceful no-op: the API
 *     rejects with a typed `unsupported` error and the UI shows "unavailable"; the
 *     page is never broken.
 *
 * WHY it is safe on the live web deploy: Firebase Hosting serves this identical
 * file. In a browser `window.Capacitor` is undefined, so the NATIVE branch is
 * never taken and no plugin call is attempted — we either use the browser's own
 * geolocation or no-op. Nothing here runs on load without a user gesture except
 * feature-detection, so simply including the script has zero side effects.
 *
 * WHY it is a plain (non-module) script AND Node-requirable (same pattern as
 * auth.js / badges.js): web/ is hand-authored static JS with NO bundler/build
 * step, so we do NOT `import` from '@capacitor/geolocation'. Instead the NATIVE
 * path calls the plugin through the global bridge object Capacitor injects
 * (`window.Capacitor.Plugins.Geolocation`). At the same time every bit of logic
 * here is a pure, dependency-injected helper that this file also exports via
 * module.exports when required from Node, so the permission + position + render
 * flow can be simulated headlessly (web/e2e/geolocation.simulation.cjs) with a
 * stubbed plugin / stubbed navigator / fake DOM — no device, no browser. The
 * `typeof module` / `typeof window` guards keep `node --check` and `require()`
 * from ever touching browser globals.
 */

// ===========================================================================
// PURE HELPERS (no browser globals — safe to require() from Node)
// ===========================================================================

// The three location backends this wrapper can drive. Kept as a small stable
// vocabulary so callers (and OSK-169) never have to sniff `window.Capacitor`.
var OSK_GEO_MODE = { NATIVE: "native", WEB: "web", NONE: "none" };

// Sensible defaults for a single position read. enableHighAccuracy asks for a
// GPS-grade fix where available; timeout stops us hanging forever on a device
// that can't get a lock; maximumAge:0 forces a fresh reading rather than a stale
// cached one. Shape is identical for the Capacitor plugin and the web API.
var OSK_GEO_DEFAULT_OPTS = { enableHighAccuracy: true, timeout: 10000, maximumAge: 0 };

// Decide which backend is usable from an INJECTED environment (so the same
// function is exercised in the headless sim). `env.capacitor` is window.Capacitor
// (or a stub); `env.geolocation` is navigator.geolocation (or a stub).
//   NATIVE requires: a Capacitor runtime that reports it's a native platform AND
//   actually has the Geolocation plugin installed (guards against a shell built
//   before `cap sync` added the plugin).
//   WEB requires: an object exposing getCurrentPosition (the browser API).
//   Otherwise NONE.
function oskGeoDetectMode(env) {
  env = env || {};
  var cap = env.capacitor;
  if (
    cap &&
    typeof cap.isNativePlatform === "function" &&
    cap.isNativePlatform() &&
    cap.Plugins &&
    cap.Plugins.Geolocation
  ) {
    return OSK_GEO_MODE.NATIVE;
  }
  if (env.geolocation && typeof env.geolocation.getCurrentPosition === "function") {
    return OSK_GEO_MODE.WEB;
  }
  return OSK_GEO_MODE.NONE;
}

// Collapse a Capacitor Position OR a browser GeolocationPosition — which have the
// SAME `{ coords: { latitude, longitude, accuracy }, timestamp }` shape — into one
// normalized object, tagging where it came from. Returns null for a malformed
// reading so callers can treat "no usable fix" uniformly.
function oskGeoNormalizePosition(raw, source) {
  if (!raw || !raw.coords) {
    return null;
  }
  var c = raw.coords;
  if (typeof c.latitude !== "number" || typeof c.longitude !== "number") {
    return null;
  }
  return {
    latitude: c.latitude,
    longitude: c.longitude,
    accuracy: typeof c.accuracy === "number" ? c.accuracy : null,
    timestamp: typeof raw.timestamp === "number" ? raw.timestamp : null,
    source: source || null,
  };
}

// Map the Capacitor permission strings ('granted' | 'denied' | 'prompt' |
// 'prompt-with-rationale') down to a 3-value vocabulary. Anything that isn't a
// clear grant/deny is treated as "prompt" (the user hasn't decided yet).
function oskGeoNormalizePermissionState(state) {
  if (state === "granted") {
    return "granted";
  }
  if (state === "denied") {
    return "denied";
  }
  return "prompt";
}

// Format a normalized position for display: 5 decimal places (~1.1 m precision),
// "lat, lng". Returns an em-dash when there's nothing to show, so the UI never
// prints "undefined". Pure string work — no DOM.
function oskGeoFormatCoords(pos) {
  if (!pos || typeof pos.latitude !== "number" || typeof pos.longitude !== "number") {
    return "—"; // em dash
  }
  return pos.latitude.toFixed(5) + ", " + pos.longitude.toFixed(5);
}

// Build an Error carrying a stable machine-readable `.reason` code (so callers
// branch on the reason, not on brittle message text).
function oskGeoError(reason, message) {
  var err = new Error(message || reason);
  err.reason = reason;
  return err;
}

// Wrap the browser's callback-style geolocation API in a promise that resolves a
// NORMALIZED position or rejects with a typed error. Kept separate so the web
// path is unit-testable with a stubbed `geolocation`.
function oskGeoWebGetCurrentPosition(geolocation, options) {
  return new Promise(function (resolve, reject) {
    geolocation.getCurrentPosition(
      function onSuccess(raw) {
        var pos = oskGeoNormalizePosition(raw, OSK_GEO_MODE.WEB);
        if (!pos) {
          reject(oskGeoError("no-position", "Browser returned no usable position"));
          return;
        }
        resolve(pos);
      },
      function onError(err) {
        // W3C PositionError codes: 1 PERMISSION_DENIED, 2 POSITION_UNAVAILABLE,
        // 3 TIMEOUT. Translate to our reason vocabulary (shared with the native
        // path) so the UI treats a denied fix identically on web and on device.
        var code = err && err.code;
        var reason =
          code === 1 ? "permission-denied" : code === 3 ? "timeout" : "position-unavailable";
        reject(oskGeoError(reason, (err && err.message) || reason));
      },
      options || OSK_GEO_DEFAULT_OPTS
    );
  });
}

// ---------------------------------------------------------------------------
// Controller factory. Given the injected environment, returns the public API:
//   mode()               -> 'native' | 'web' | 'none'
//   requestPermission()  -> Promise<'granted'|'denied'|'prompt'|'unavailable'>
//   getCurrentPosition() -> Promise<normalized position> (rejects with .reason)
// All three re-detect the mode on each call so a Capacitor runtime that finishes
// injecting after this module loads is still picked up.
// ---------------------------------------------------------------------------
function oskCreateGeolocation(deps) {
  deps = deps || {};
  var env = { capacitor: deps.capacitor, geolocation: deps.geolocation };

  function mode() {
    return oskGeoDetectMode(env);
  }

  // Ask for permission up front. On NATIVE this is a real Android runtime prompt
  // via the plugin. On WEB there's nothing to pre-request (the browser prompts at
  // read time), so we report "prompt". On NONE, "unavailable".
  function requestPermission() {
    var m = mode();
    if (m === OSK_GEO_MODE.NATIVE) {
      var plugin = env.capacitor.Plugins.Geolocation;
      return Promise.resolve()
        .then(function () {
          return plugin.requestPermissions();
        })
        .then(function (res) {
          // Capacitor returns { location, coarseLocation } permission strings.
          // Prefer the precise-location decision, fall back to the coarse one.
          var state = (res && (res.location || res.coarseLocation)) || "prompt";
          return oskGeoNormalizePermissionState(state);
        });
    }
    if (m === OSK_GEO_MODE.WEB) {
      return Promise.resolve("prompt");
    }
    return Promise.resolve("unavailable");
  }

  // Read the current position once. NATIVE requests permission first (the Android
  // plugin does NOT auto-prompt inside getCurrentPosition) and short-circuits on a
  // hard denial; WEB defers to the browser prompt; NONE rejects with `unsupported`.
  function getCurrentPosition(options) {
    var m = mode();
    if (m === OSK_GEO_MODE.NATIVE) {
      var plugin = env.capacitor.Plugins.Geolocation;
      return Promise.resolve()
        .then(function () {
          return plugin.requestPermissions();
        })
        .then(function (res) {
          var state = oskGeoNormalizePermissionState(
            (res && (res.location || res.coarseLocation)) || "prompt"
          );
          if (state === "denied") {
            throw oskGeoError("permission-denied", "Location permission denied");
          }
          return plugin.getCurrentPosition(options || OSK_GEO_DEFAULT_OPTS);
        })
        .then(function (raw) {
          var pos = oskGeoNormalizePosition(raw, OSK_GEO_MODE.NATIVE);
          if (!pos) {
            throw oskGeoError("no-position", "Plugin returned no usable position");
          }
          return pos;
        });
    }
    if (m === OSK_GEO_MODE.WEB) {
      return oskGeoWebGetCurrentPosition(env.geolocation, options);
    }
    return Promise.reject(
      oskGeoError("unsupported", "Geolocation is not available in this environment")
    );
  }

  return {
    mode: mode,
    requestPermission: requestPermission,
    getCurrentPosition: getCurrentPosition,
  };
}

// ---------------------------------------------------------------------------
// UI hook factory. Wires a "get my location" button + an output element to the
// controller and renders lat/long into the output. Deliberately tiny — the full
// diagnostics screen is OSK-169. Everything is written with textContent only
// (never innerHTML), so a hostile string can never become markup.
//
// deps: { geo, button, output, status? } — status is an optional element whose
// textContent is set to the phase name (idle/loading/ok/error/unsupported) as a
// coarse status hook for styling/tests.
// ---------------------------------------------------------------------------
function oskCreateGeoUi(deps) {
  deps = deps || {};
  var geo = deps.geo;
  var button = deps.button || null;
  var output = deps.output || null;
  var statusEl = deps.status || null;

  function setText(el, text) {
    if (el) {
      el.textContent = text;
    }
  }

  // Render a UI phase. Pure with respect to `state` — no async, no fetching.
  function render(state) {
    state = state || {};
    var phase = state.phase || "idle";
    setText(statusEl, phase);
    if (phase === "loading") {
      setText(output, "Locating…");
    } else if (phase === "ok") {
      setText(output, oskGeoFormatCoords(state.pos));
    } else if (phase === "unsupported") {
      setText(output, "Location unavailable on this device");
    } else if (phase === "error") {
      setText(output, state.message || "Could not get location");
    } else {
      setText(output, "—"); // idle
    }
  }

  // Kick off one position read and drive the phases. Returns the promise so
  // tests (and callers) can await the settled UI. Never rejects — a failure is
  // rendered as the error phase, so a click can't produce an unhandled rejection.
  function refresh() {
    if (geo.mode() === OSK_GEO_MODE.NONE) {
      render({ phase: "unsupported" });
      return Promise.resolve();
    }
    render({ phase: "loading" });
    return geo
      .getCurrentPosition()
      .then(function (pos) {
        render({ phase: "ok", pos: pos });
      })
      .catch(function (err) {
        var message =
          err && err.reason === "permission-denied"
            ? "Location permission denied"
            : "Could not get location";
        render({ phase: "error", message: message });
      });
  }

  if (button && typeof button.addEventListener === "function") {
    button.addEventListener("click", function () {
      // Fire-and-forget: refresh() handles its own errors.
      refresh();
    });
  }

  // Paint the initial state so the widget isn't blank before the first click:
  // "unavailable" when there's no backend at all, otherwise the idle em-dash.
  render({ phase: geo.mode() === OSK_GEO_MODE.NONE ? "unsupported" : "idle" });

  return { render: render, refresh: refresh };
}

// ===========================================================================
// NODE EXPORT — expose the pure helpers + factories for the headless simulation.
// `typeof module` is "undefined" in a classic browser <script>, so this is a
// no-op there.
// ===========================================================================
if (typeof module !== "undefined" && module.exports) {
  module.exports = {
    OSK_GEO_MODE: OSK_GEO_MODE,
    OSK_GEO_DEFAULT_OPTS: OSK_GEO_DEFAULT_OPTS,
    detectMode: oskGeoDetectMode,
    normalizePosition: oskGeoNormalizePosition,
    normalizePermissionState: oskGeoNormalizePermissionState,
    formatCoords: oskGeoFormatCoords,
    webGetCurrentPosition: oskGeoWebGetCurrentPosition,
    createGeolocation: oskCreateGeolocation,
    createGeoUi: oskCreateGeoUi,
  };
}

// ===========================================================================
// BROWSER BOOTSTRAP — runs ONLY in a real browser (guarded by `typeof window`).
// Builds the real environment from window.Capacitor + navigator.geolocation,
// publishes the controller on window.OSKGeolocation (so other modules and the
// OSK-169 diagnostics screen can reuse it), and — if the current page carries the
// location widget's elements — wires the button + output. Pages WITHOUT those
// elements (every page except the ops panel) get the API but no UI, at zero cost.
// ===========================================================================
if (typeof window !== "undefined") {
  (function () {
    "use strict";

    var env = {
      capacitor: window.Capacitor,
      geolocation:
        typeof navigator !== "undefined" && navigator.geolocation ? navigator.geolocation : null,
    };

    var geo = oskCreateGeolocation(env);
    // Public handle for reuse elsewhere (idempotent: last include wins, harmless).
    window.OSKGeolocation = geo;

    function wireUi() {
      var button = document.getElementById("osk-geo-btn");
      var output = document.getElementById("osk-geo-output");
      var statusEl = document.getElementById("osk-geo-status");
      // No widget on this page — nothing to wire. The API on window still works.
      if (!button && !output) {
        return;
      }
      oskCreateGeoUi({ geo: geo, button: button, output: output, status: statusEl });
    }

    if (document.readyState === "complete" || document.readyState === "interactive") {
      wireUi();
    } else {
      document.addEventListener("DOMContentLoaded", wireUi, { once: true });
    }
  })();
}
