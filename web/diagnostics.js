/*
 * diagnostics.js — OSK-169. QA DIAGNOSTICS controller for the OpenSkeleton app: one
 * small, self-contained module that powers the /diagnostics screen (web/diagnostics.html)
 * so a tester can, ON A DEVICE, see at a glance what the native shell is actually giving
 * the web app. It answers four questions:
 *
 *   1) PLATFORM      — is this running native (Capacitor Android) or plain web, and which
 *                      platform id does the runtime report (android / web / …)?
 *   2) PLUGIN STATUS — which of the EXPECTED Capacitor plugins are actually loaded
 *                      (SplashScreen, Geolocation, PushNotifications) — present/absent
 *                      each, so a shell built before `npx cap sync android` (or a plain
 *                      browser) is instantly obvious.
 *   3) GPS           — the location permission state + the current latitude/longitude,
 *                      fetched on demand via the OSK-152 wrapper (window.OSKGeolocation),
 *                      degrading gracefully to "unavailable" off-device.
 *   4) FCM PUSH      — the notification permission state + the FCM registration token,
 *                      obtained via the OSK-150 push client (window.OSKPush), with a
 *                      copy-to-clipboard for the token (QA pastes it into a test-send
 *                      console). Off-device / before FCM is configured it shows a clear
 *                      "not available / FCM not configured (OSK-165)" state.
 *
 * WHY IT IS ITS OWN FILE (not folded into an existing page/module): the diagnostics screen
 * is a leaf, additive surface. Keeping ALL of its logic here — a brand-new file — means it
 * can be built and reviewed without touching the shared web modules (auth.js, the pages
 * other tickets are editing in parallel), so it never conflicts with concurrent web work.
 * It REUSES the already-merged wrappers rather than re-implementing device access:
 * window.OSKGeolocation (OSK-152) and window.OSKPush (OSK-150). This file adds only the
 * aggregation + rendering.
 *
 * WHY IT IS SAFE ON THE LIVE WEB DEPLOY: Firebase Hosting serves this identical file. In a
 * plain browser window.Capacitor is undefined, so the platform reads "Web browser", every
 * plugin reads "Not loaded", GPS falls back to the browser Geolocation API (or shows
 * "unavailable"), and push mode() is NONE so the FCM section shows the graceful
 * "not available" state — nothing here throws, and NO plugin call is ever attempted off
 * device. Nothing runs on load except feature-detection + rendering the baseline states;
 * the actual permission prompts fire ONLY when a QA presses a "refresh" button (so simply
 * opening the page never nags the user for location/notifications).
 *
 * WHY IT IS A PLAIN (non-module) SCRIPT AND Node-requirable (same pattern as auth.js /
 * geolocation.js / push-client.js): web/ is hand-authored static JS with NO bundler/build
 * step. Every bit of decision + render logic here is a pure, dependency-injected helper
 * that this file also exports via module.exports when required from Node, so the whole
 * screen — native-vs-web detection, plugin present/absent rendering, GPS success/denied,
 * FCM token present/absent, and the XSS-safe DOM contract — can be simulated headlessly
 * (web/e2e/diagnostics.simulation.cjs) with a fake DOM + stubbed geo/push/clipboard, no
 * device and no browser. The `typeof module` / `typeof window` guards keep `node --check`
 * and `require()` from ever touching browser globals.
 *
 * XSS-SAFE: every dynamic value (platform string, coordinates, the FCM token, error copy)
 * is written with textContent ONLY — never innerHTML — so a hostile value can never become
 * markup. NO SECRETS are embedded: the FCM token is a RUNTIME value read from the device
 * plugin and shown to the QA using the app; it is never committed, logged to a server, or
 * hardcoded here.
 */

// ===========================================================================
// PURE HELPERS (no browser globals — safe to require() from Node)
// ===========================================================================

// The Capacitor plugins this app is expected to register (see capacitor.config.ts /
// OSK-146). The diagnostics screen checks each one is actually present on the bridge so a
// missing plugin (e.g. a shell built before `npx cap sync android`) is obvious. Order is
// the display order.
var OSK_DIAG_EXPECTED_PLUGINS = ["SplashScreen", "Geolocation", "PushNotifications"];

// Detect platform from an INJECTED environment (so the same function runs in the headless
// sim). `env.capacitor` is window.Capacitor (or a stub). Returns a small descriptor:
//   hasCapacitor — is a Capacitor bridge present at all (native OR a browser JS shim)?
//   native       — does the runtime report it is a real native platform?
//   platform     — the runtime's own platform id ("android" | "web" | …); defaults to
//                  "web" when there's no bridge, "unknown" for a native bridge that
//                  somehow reports no id.
function oskDiagDetectPlatform(env) {
  env = env || {};
  var cap = env.capacitor;
  var hasCapacitor = !!cap;
  var native = !!(cap && typeof cap.isNativePlatform === "function" && cap.isNativePlatform());
  var platform =
    cap && typeof cap.getPlatform === "function" ? String(cap.getPlatform() || "") : "";
  if (!platform) {
    platform = native ? "unknown" : "web";
  }
  return { hasCapacitor: hasCapacitor, native: native, platform: platform };
}

// For each EXPECTED plugin, is it actually loaded on the bridge? Reads
// `env.capacitor.Plugins[name]` defensively (no bridge / no Plugins map => all absent).
// Returns [{ name, present }] in the expected order so the UI can paint fixed rows.
function oskDiagPluginStatus(env, expected) {
  env = env || {};
  var names = expected || OSK_DIAG_EXPECTED_PLUGINS;
  var cap = env.capacitor;
  var plugins = cap && cap.Plugins ? cap.Plugins : {};
  return names.map(function (name) {
    return { name: name, present: !!plugins[name] };
  });
}

// Format a normalized position ({ latitude, longitude }) as 5-dp "lat, lng" (~1.1 m), or
// an em-dash when there's nothing to show (so the UI never prints "undefined"). Mirrors
// geolocation.js's formatCoords so the two surfaces read identically. Pure string work.
function oskDiagFormatCoords(pos) {
  if (!pos || typeof pos.latitude !== "number" || typeof pos.longitude !== "number") {
    return "—"; // em dash
  }
  return pos.latitude.toFixed(5) + ", " + pos.longitude.toFixed(5);
}

// Human label for a permission state (the shared granted/denied/prompt/unavailable
// vocabulary the geo + push wrappers both speak). Anything unexpected reads "Unknown".
function oskDiagPermissionLabel(state) {
  if (state === "granted") {
    return "Granted";
  }
  if (state === "denied") {
    return "Denied";
  }
  if (state === "prompt") {
    return "Not yet requested";
  }
  if (state === "unavailable") {
    return "Unavailable";
  }
  return "Unknown";
}

// Human copy for a geolocation error `.reason` code (from geolocation.js). Keeps the UI
// message logic pure + testable, and consistent between the ops widget and this screen.
function oskDiagGeoErrorMessage(reason) {
  if (reason === "permission-denied") {
    return "Location permission denied";
  }
  if (reason === "timeout") {
    return "Timed out getting a location fix";
  }
  if (reason === "position-unavailable") {
    return "Position unavailable";
  }
  if (reason === "unsupported") {
    return "Location unavailable on this device";
  }
  return "Could not get location";
}

// Derive the FCM display STATE from the three inputs the screen has: the push mode
// ("native" | "none"), the notification permission, and whatever token we managed to
// read. Returns a { phase, token, message } the renderer maps straight to the UI. This is
// where the OSK-165 gate is surfaced honestly:
//   phase "unavailable" — not a native push platform (plain web, or plugin not loaded):
//                         push is simply not available here.
//   phase "denied"      — native, but the user denied the notification permission.
//   phase "no-token"    — native + (permission not denied) but NO token arrived. The
//                         overwhelmingly common cause is that FCM isn't configured yet —
//                         google-services.json is the human OSK-165 gate — so we say so.
//   phase "ok"          — a real FCM registration token is present.
// A blank/whitespace/non-string token is treated as "no token".
function oskDiagFcmState(input) {
  input = input || {};
  var mode = input.mode;
  var permission = input.permission;
  var token =
    typeof input.token === "string" && input.token.trim() !== "" ? input.token.trim() : null;

  if (mode !== "native") {
    return {
      phase: "unavailable",
      token: null,
      message: "Push not available on this platform (web / native plugin not loaded)",
    };
  }
  if (permission === "denied") {
    return { phase: "denied", token: null, message: "Notification permission denied" };
  }
  if (!token) {
    return {
      phase: "no-token",
      token: null,
      message: "No FCM token yet — FCM not configured (OSK-165)",
    };
  }
  return { phase: "ok", token: token, message: null };
}

// ---------------------------------------------------------------------------
// Controller factory. Wires the injected device wrappers + a document to the
// diagnostics DOM. Everything the browser needs is injected so the sim can drive the exact
// same code with fakes:
//
// deps:
//   doc        — document (or a fake with getElementById) — the element source.
//   capacitor  — window.Capacitor (or a stub / undefined off-device).
//   geo        — the OSK-152 controller (window.OSKGeolocation): mode() /
//                requestPermission() / getCurrentPosition().
//   push       — the OSK-150 controller (window.OSKPush): mode() / requestPermission() /
//                register() / lastToken().
//   clipboard  — navigator.clipboard (or a stub) for the copy-token action.
//   wait       — (ms) => Promise, used to space out the token poll (default setTimeout).
//   fcmTokenRetries / fcmTokenIntervalMs — how many times / how often to re-read
//                push.lastToken() after register() before giving up (the token arrives
//                asynchronously via the plugin's `registration` event).
//
// The controller writes to fixed element ids (see diagnostics.html). Every write goes
// through setText/setDot (textContent + className only) so it is XSS-safe by construction.
// ---------------------------------------------------------------------------
function oskCreateDiagnostics(deps) {
  deps = deps || {};
  var doc = deps.doc;
  var capacitor = deps.capacitor;
  var geo = deps.geo || null;
  var push = deps.push || null;
  var clipboard = deps.clipboard || null;
  var wait =
    typeof deps.wait === "function"
      ? deps.wait
      : function (ms) {
          return new Promise(function (resolve) {
            setTimeout(resolve, ms);
          });
        };
  var fcmTokenRetries = deps.fcmTokenRetries != null ? deps.fcmTokenRetries : 5;
  var fcmTokenIntervalMs = deps.fcmTokenIntervalMs != null ? deps.fcmTokenIntervalMs : 400;

  // The most recently observed FCM token — remembered so the copy button can copy it
  // without re-registering. Null until a token has actually been read.
  var lastFcmToken = null;

  // --- tiny DOM helpers (the ONLY places that touch elements) ---------------
  function el(id) {
    return doc && typeof doc.getElementById === "function" ? doc.getElementById(id) : null;
  }
  // XSS-safe text set: textContent only, never innerHTML. Missing element => no-op.
  function setText(id, text) {
    var node = el(id);
    if (node) {
      node.textContent = text == null ? "" : String(text);
    }
  }
  // Set a status dot's colour by state name ("ok" | "warn" | "down" | "pending"). The dot
  // classes are defined in diagnostics.html's stylesheet (shared status palette).
  function setDot(id, state) {
    var node = el(id);
    if (node) {
      node.className = "dot dot--" + state;
    }
  }
  // Enable/disable a button (used for the copy-token affordance).
  function setDisabled(id, disabled) {
    var node = el(id);
    if (node) {
      node.disabled = !!disabled;
    }
  }
  // Attach a click handler if the element (and addEventListener) exists.
  function wireButton(id, handler) {
    var node = el(id);
    if (node && typeof node.addEventListener === "function") {
      node.addEventListener("click", function () {
        // Fire-and-forget: each handler catches its own errors, so a click can never
        // produce an unhandled rejection.
        handler();
      });
    }
  }

  // -------------------------------------------------------------------------
  // PLATFORM
  // -------------------------------------------------------------------------
  function renderPlatform() {
    var info = oskDiagDetectPlatform({ capacitor: capacitor });
    var label = info.native
      ? "Native (Capacitor)"
      : info.hasCapacitor
      ? "Web (Capacitor bridge, not native)"
      : "Web browser";
    setText("diag-platform-value", label + " — " + info.platform);
    // Green when we're on the native shell (the happy on-device case), amber otherwise
    // (a plain browser is fine, just not the native runtime QA usually wants to verify).
    setDot("diag-platform-dot", info.native ? "ok" : "warn");
    return info;
  }

  // -------------------------------------------------------------------------
  // PLUGINS
  // -------------------------------------------------------------------------
  function renderPlugins() {
    var rows = oskDiagPluginStatus({ capacitor: capacitor });
    rows.forEach(function (r) {
      setText("diag-plugin-" + r.name, r.present ? "Available" : "Not loaded");
      // Present = green; absent = amber (absent is EXPECTED on the web deploy, so it's a
      // neutral "attention" state, not a red error).
      setDot("diag-plugin-" + r.name + "-dot", r.present ? "ok" : "warn");
    });
    return rows;
  }

  // -------------------------------------------------------------------------
  // GPS
  // -------------------------------------------------------------------------
  // Render one GPS phase. Pure with respect to `state` — no async, no fetching.
  function renderGps(state) {
    state = state || {};
    var phase = state.phase || "idle";
    setText("diag-gps-status", phase);
    if (state.permission != null) {
      setText("diag-gps-permission", oskDiagPermissionLabel(state.permission));
    }
    if (phase === "loading") {
      setText("diag-gps-coords", "Locating…");
      setDot("diag-gps-dot", "pending");
    } else if (phase === "ok") {
      setText("diag-gps-coords", oskDiagFormatCoords(state.pos));
      setText(
        "diag-gps-accuracy",
        state.pos && typeof state.pos.accuracy === "number"
          ? "±" + Math.round(state.pos.accuracy) + " m"
          : "—"
      );
      setDot("diag-gps-dot", "ok");
    } else if (phase === "unsupported") {
      setText("diag-gps-coords", "Location unavailable on this device");
      setText("diag-gps-permission", oskDiagPermissionLabel("unavailable"));
      setText("diag-gps-accuracy", "—");
      setDot("diag-gps-dot", "down");
    } else if (phase === "error") {
      setText("diag-gps-coords", oskDiagGeoErrorMessage(state.reason));
      setText("diag-gps-accuracy", "—");
      // A hard permission denial reads red; any other transient failure reads amber.
      setDot("diag-gps-dot", state.reason === "permission-denied" ? "down" : "warn");
    } else {
      // idle — before the first refresh
      setText("diag-gps-coords", "—");
      setText("diag-gps-accuracy", "—");
      setDot("diag-gps-dot", "pending");
    }
  }

  // Kick off one location read and drive the phases. Returns the promise so the sim (and
  // callers) can await the settled UI. Never rejects — a failure is rendered as the error
  // phase. Requests permission first (surfacing the state) then reads a position.
  function refreshGps() {
    var m = geo && typeof geo.mode === "function" ? geo.mode() : "none";
    if (m === "none") {
      renderGps({ phase: "unsupported" });
      return Promise.resolve();
    }
    renderGps({ phase: "loading" });
    var permState = "unknown";
    return Promise.resolve()
      .then(function () {
        return geo.requestPermission();
      })
      .then(function (perm) {
        permState = perm;
        return geo.getCurrentPosition();
      })
      .then(function (pos) {
        renderGps({ phase: "ok", permission: permState, pos: pos });
      })
      .catch(function (err) {
        var reason = err && err.reason;
        renderGps({
          phase: "error",
          reason: reason,
          // A denial gives us a definite permission state even if requestPermission didn't.
          permission: reason === "permission-denied" ? "denied" : permState,
        });
      });
  }

  // -------------------------------------------------------------------------
  // FCM PUSH
  // -------------------------------------------------------------------------
  // Poll push.lastToken() up to `fcmTokenRetries` times (spaced by `fcmTokenIntervalMs`)
  // because the FCM token arrives ASYNCHRONOUSLY via the plugin's `registration` event
  // after register() resolves. Resolves the token string, or null if none appears in time
  // (the OSK-165-not-configured case). In the sim, `wait` resolves instantly so this is
  // deterministic + fast.
  function pollToken(retries) {
    var t = push && typeof push.lastToken === "function" ? push.lastToken() : null;
    if (typeof t === "string" && t.trim() !== "") {
      return Promise.resolve(t.trim());
    }
    if (retries <= 0) {
      return Promise.resolve(null);
    }
    return wait(fcmTokenIntervalMs).then(function () {
      return pollToken(retries - 1);
    });
  }

  // Render one FCM state (from oskDiagFcmState) + the permission label.
  function renderFcm(state, permission) {
    state = state || {};
    setText("diag-fcm-status", state.phase);
    setText("diag-fcm-permission", oskDiagPermissionLabel(permission));
    if (state.phase === "ok") {
      setText("diag-fcm-token", state.token);
      setDot("diag-fcm-dot", "ok");
      setDisabled("diag-fcm-copy", false); // a real token — copy is available
    } else {
      setText("diag-fcm-token", state.message || "—");
      // denied = red; unavailable/no-token = amber (expected off-device / pre-OSK-165).
      setDot("diag-fcm-dot", state.phase === "denied" ? "down" : "warn");
      setDisabled("diag-fcm-copy", true); // nothing to copy
    }
  }

  // Drive the FCM check: request permission, register (native only), read the token, and
  // render the resulting state. Never rejects — any failure lands in the graceful
  // "no-token" state. Records lastFcmToken for the copy button.
  function refreshFcm() {
    var m = push && typeof push.mode === "function" ? push.mode() : "none";
    if (m !== "native") {
      lastFcmToken = null;
      renderFcm(oskDiagFcmState({ mode: m }), "unavailable");
      return Promise.resolve();
    }
    setText("diag-fcm-status", "loading");
    setText("diag-fcm-token", "Registering…");
    setDot("diag-fcm-dot", "pending");
    setDisabled("diag-fcm-copy", true);

    var permState = "unknown";
    return Promise.resolve()
      .then(function () {
        return push.requestPermission();
      })
      .then(function (perm) {
        permState = perm;
        // Only register when the OS notification permission is granted; otherwise there is
        // nothing to register and no token to expect.
        if (perm !== "granted") {
          return null;
        }
        return push.register();
      })
      .then(function () {
        return pollToken(fcmTokenRetries);
      })
      .then(function (token) {
        var state = oskDiagFcmState({ mode: "native", permission: permState, token: token });
        lastFcmToken = state.token;
        renderFcm(state, permState);
      })
      .catch(function () {
        // Defensive: even if a wrapper unexpectedly throws, degrade to no-token.
        lastFcmToken = null;
        renderFcm(oskDiagFcmState({ mode: "native", permission: permState, token: null }), permState);
      });
  }

  // Copy the last observed FCM token to the clipboard (QA convenience). Never rejects;
  // reports the outcome in the small copy-status line. Returns a boolean for the sim.
  function copyToken() {
    if (!lastFcmToken) {
      setText("diag-fcm-copy-status", "Nothing to copy");
      return Promise.resolve(false);
    }
    if (!clipboard || typeof clipboard.writeText !== "function") {
      setText("diag-fcm-copy-status", "Clipboard unavailable");
      return Promise.resolve(false);
    }
    return Promise.resolve()
      .then(function () {
        return clipboard.writeText(lastFcmToken);
      })
      .then(function () {
        setText("diag-fcm-copy-status", "Copied!");
        return true;
      })
      .catch(function () {
        setText("diag-fcm-copy-status", "Copy failed");
        return false;
      });
  }

  // -------------------------------------------------------------------------
  // INIT — paint the static facts (platform + plugins) and the non-intrusive baseline
  // states for GPS + FCM (NO permission prompts on load), then wire the buttons.
  // -------------------------------------------------------------------------
  function init() {
    renderPlatform();
    renderPlugins();

    // GPS baseline: show "unavailable" immediately when there's no backend at all (no
    // prompt needed); otherwise an idle em-dash until the QA presses refresh.
    var gm = geo && typeof geo.mode === "function" ? geo.mode() : "none";
    renderGps({ phase: gm === "none" ? "unsupported" : "idle" });

    // FCM baseline: off a native push platform, show the terminal "unavailable" state
    // (no prompt). On native, an idle hint until the QA presses refresh (so the page never
    // triggers the notification prompt just by being opened).
    var pm = push && typeof push.mode === "function" ? push.mode() : "none";
    if (pm !== "native") {
      lastFcmToken = null;
      renderFcm(oskDiagFcmState({ mode: pm }), "unavailable");
    } else {
      setText("diag-fcm-status", "idle");
      setText("diag-fcm-permission", "—");
      setText("diag-fcm-token", "Tap “Check push” to register");
      setDot("diag-fcm-dot", "pending");
      setDisabled("diag-fcm-copy", true);
    }

    wireButton("diag-gps-refresh", refreshGps);
    wireButton("diag-fcm-refresh", refreshFcm);
    wireButton("diag-fcm-copy", copyToken);

    return controller;
  }

  var controller = {
    init: init,
    renderPlatform: renderPlatform,
    renderPlugins: renderPlugins,
    renderGps: renderGps,
    refreshGps: refreshGps,
    renderFcm: renderFcm,
    refreshFcm: refreshFcm,
    copyToken: copyToken,
    lastFcmToken: function () {
      return lastFcmToken;
    },
  };
  return controller;
}

// ===========================================================================
// NODE EXPORT — expose the pure helpers + factory for the headless simulation.
// `typeof module` is "undefined" in a classic browser <script>, so this is a no-op there.
// ===========================================================================
if (typeof module !== "undefined" && module.exports) {
  module.exports = {
    OSK_DIAG_EXPECTED_PLUGINS: OSK_DIAG_EXPECTED_PLUGINS,
    detectPlatform: oskDiagDetectPlatform,
    pluginStatus: oskDiagPluginStatus,
    formatCoords: oskDiagFormatCoords,
    permissionLabel: oskDiagPermissionLabel,
    geoErrorMessage: oskDiagGeoErrorMessage,
    fcmState: oskDiagFcmState,
    createDiagnostics: oskCreateDiagnostics,
  };
}

// ===========================================================================
// BROWSER BOOTSTRAP — runs ONLY in a real browser (guarded by `typeof window`). Builds the
// real environment from window.Capacitor + the already-published OSK-152 / OSK-150
// wrappers + navigator.clipboard, publishes the controller on window.OSKDiagnostics (for
// tests / manual poking), and initialises the screen once the DOM is ready. Pages WITHOUT
// the diagnostics elements are impossible here (this script is only included by
// diagnostics.html), but init() is defensive anyway (every element lookup is optional).
// ===========================================================================
if (typeof window !== "undefined") {
  (function () {
    "use strict";

    var diagnostics = oskCreateDiagnostics({
      doc: typeof document !== "undefined" ? document : null,
      capacitor: window.Capacitor,
      // Reuse the merged device wrappers rather than re-implementing plugin access.
      geo: window.OSKGeolocation || null,
      push: window.OSKPush || null,
      clipboard:
        typeof navigator !== "undefined" && navigator.clipboard ? navigator.clipboard : null,
    });

    // Public handle for reuse elsewhere / manual QA (idempotent: last include wins).
    window.OSKDiagnostics = diagnostics;

    function boot() {
      diagnostics.init();
    }
    if (document.readyState === "complete" || document.readyState === "interactive") {
      boot();
    } else {
      document.addEventListener("DOMContentLoaded", boot, { once: true });
    }
  })();
}
