/*
 * push-client.js — OSK-150. Push-notification CLIENT for the OpenSkeleton app: one
 * small wrapper that drives the official @capacitor/push-notifications plugin on the
 * Capacitor Android shell AND degrades to a graceful no-op in a plain browser.
 *
 * WHAT it does: exposes a tiny promise API (`window.OSKPush`) that
 *   1) requests the OS notification permission,
 *   2) registers the device for push (which makes FCM mint a registration token),
 *   3) receives that token via the plugin's `registration` event, and
 *   4) registers the token with the backend — POST /api/v1/me/devices (OSK-154),
 *      authenticated with the caller's Firebase ID token (Authorization: Bearer …
 *      from window.OSKAuth) — and DELETE /api/v1/me/devices/{token} on sign-out.
 * It also wires the `registrationError`, `pushNotificationReceived` and
 * `pushNotificationActionPerformed` (tap) listeners. Actually NAVIGATING on a tap is
 * OSK-156's job; this file only keeps a documented SEAM (extract the deep-link route
 * from the tapped notification's data and hand it to an injectable `onRoute`), so the
 * routing ticket has a single, tested place to slot in.
 *
 * TWO runtime modes, auto-detected (see oskPushDetectMode):
 *   - NATIVE — inside the Capacitor Android WebView, where the native runtime injects
 *     `window.Capacitor` and (after `npx cap sync android` installs the plugin's native
 *     half) `Capacitor.Plugins.PushNotifications`. Here we go through the plugin:
 *     requestPermissions() → register() → the `registration` event carries the FCM
 *     token → POST it to the backend. The Android runtime notification permission it
 *     needs on Android 13+ (POST_NOTIFICATIONS) is declared in
 *     android/app/src/main/AndroidManifest.xml.
 *   - NONE — everything else: a normal browser (the live Firebase-hosted site) has NO
 *     native push plugin (@capacitor/push-notifications is native-only; browser web-push
 *     is a separate concern), and a native shell whose plugin isn't installed yet is
 *     also NONE. Every entry point degrades to a graceful no-op: register()/unregister()
 *     resolve a typed `unsupported` result and NOTHING is attempted. The page is never
 *     broken and no plugin call is ever made off-device.
 *
 * WHY it is safe on the live web deploy: Firebase Hosting serves this identical file.
 *   In a browser `window.Capacitor` is undefined (or not a native platform), so the
 *   NATIVE branch is never taken and no plugin call is attempted. Nothing here runs on
 *   load except feature-detection + (when native + signed in) kicking off registration,
 *   so simply including the script has zero side effects in the browser.
 *
 * WHY live push still needs a HUMAN step (OSK-165 — the GATE): actually DELIVERING a
 *   push needs Firebase Cloud Messaging enabled for the project and a real
 *   `android/app/google-services.json` dropped into the app module. That file is a
 *   per-project config the human adds (OSK-165); it is deliberately NOT in this repo and
 *   MUST NOT be committed. The Android build already handles its absence gracefully —
 *   android/app/build.gradle applies the google-services plugin ONLY when the JSON is
 *   present, else logs and skips — so the APK builds fine without it. At runtime, with no
 *   google-services.json, FCM simply fails to initialise and the plugin emits a
 *   `registrationError`, which this module handles without crashing. Everything below is
 *   therefore correct + testable with the plugin mocked TODAY, and lights up for real the
 *   moment OSK-165 lands the config — no code change here required.
 *
 * WHY it is a plain (non-module) script AND Node-requirable (same pattern as
 * auth.js / geolocation.js): web/ is hand-authored static JS with NO bundler/build step,
 * so we do NOT `import` from '@capacitor/push-notifications'. Instead the NATIVE path
 * calls the plugin through the global bridge object Capacitor injects
 * (`window.Capacitor.Plugins.PushNotifications`). At the same time every bit of logic
 * here is a pure, dependency-injected helper that this file also exports via
 * module.exports when required from Node, so the permission → token → register-with-
 * backend flow (and the DELETE on unregister, and the deep-link seam) can be simulated
 * headlessly (web/e2e/push-client.simulation.cjs) with a stubbed plugin + stubbed fetch,
 * no device and no browser. The `typeof module` / `typeof window` guards keep
 * `node --check` and `require()` from ever touching browser globals.
 *
 * NO SECRETS: the only network call is to the config-driven apiBaseUrl with a short-lived
 *   Firebase ID token in the Authorization header. No API keys, no google-services.json,
 *   nothing sensitive is embedded here.
 */

// ===========================================================================
// PURE HELPERS (no browser globals — safe to require() from Node)
// ===========================================================================

// The two push backends this wrapper can drive. Kept as a small stable vocabulary so
// callers never have to sniff `window.Capacitor` themselves.
var OSK_PUSH_MODE = { NATIVE: "native", NONE: "none" };

// The platform strings the backend's DeviceToken.Platform enum accepts (OSK-154). The
// token is registered against one of these so the send-push service (OSK-155) can pick
// the right transport per device. Values MUST match the enum names exactly.
var OSK_PUSH_PLATFORM = { ANDROID: "ANDROID", IOS: "IOS", WEB: "WEB" };

// The FCM `data`-map key carrying an in-app deep-link route. This is a CLIENT-FACING
// wire contract shared with the backend (backend/push/PushData.ROUTE = "route",
// OSK-166) and consumed by the tap-handler (OSK-156). Do not rename the string value.
var OSK_PUSH_ROUTE_KEY = "route";

// Decide which backend is usable from an INJECTED environment (so the same function is
// exercised in the headless sim). `env.capacitor` is window.Capacitor (or a stub).
//   NATIVE requires: a Capacitor runtime that reports it's a native platform AND
//   actually has the PushNotifications plugin installed (guards against a shell built
//   before `cap sync` added the plugin, and against a plain browser's JS shim).
//   Otherwise NONE.
function oskPushDetectMode(env) {
  env = env || {};
  var cap = env.capacitor;
  if (
    cap &&
    typeof cap.isNativePlatform === "function" &&
    cap.isNativePlatform() &&
    cap.Plugins &&
    cap.Plugins.PushNotifications
  ) {
    return OSK_PUSH_MODE.NATIVE;
  }
  return OSK_PUSH_MODE.NONE;
}

// Which backend Platform enum value to register this device under. Reads the Capacitor
// runtime's own platform id (`getPlatform()` → "android" | "ios" | "web"). Since push is
// only ever driven in NATIVE mode, android/ios are the real cases; anything unknown
// defaults to ANDROID (this is the Android shell) so a token is never registered with an
// invalid platform the backend would 400.
function oskPushPlatformFor(capacitor) {
  var p =
    capacitor && typeof capacitor.getPlatform === "function" ? capacitor.getPlatform() : "";
  if (p === "ios") {
    return OSK_PUSH_PLATFORM.IOS;
  }
  if (p === "web") {
    return OSK_PUSH_PLATFORM.WEB;
  }
  return OSK_PUSH_PLATFORM.ANDROID;
}

// Map the Capacitor permission strings ('granted' | 'denied' | 'prompt' |
// 'prompt-with-rationale') down to a 3-value vocabulary. Anything that isn't a clear
// grant/deny is treated as "prompt" (the user hasn't decided yet). Mirrors the
// geolocation wrapper so both features speak the same permission words.
function oskPushNormalizePermissionState(state) {
  if (state === "granted") {
    return "granted";
  }
  if (state === "denied") {
    return "denied";
  }
  return "prompt";
}

// Extract the in-app deep-link route from a tapped/received notification, if any. This
// is the OSK-156 SEAM: the backend forwards a route under the `data.route` key
// (OSK-166); the tap-handler ticket decides where to navigate. Here we ONLY pull the
// string out (trimmed), returning null when there's nothing usable — so OSK-156 has one
// pure, tested place to read from and this file never itself navigates. Duck-typed and
// defensive: accepts either a raw notification ({ data }) or an ActionPerformed
// ({ notification: { data } }); a missing/blank/non-string route yields null.
function oskPushExtractRoute(input) {
  if (!input || typeof input !== "object") {
    return null;
  }
  // ActionPerformed wraps the notification; a received schema IS the notification.
  var notification = input.notification && typeof input.notification === "object"
    ? input.notification
    : input;
  var data = notification && notification.data;
  if (!data || typeof data !== "object") {
    return null;
  }
  var route = data[OSK_PUSH_ROUTE_KEY];
  if (typeof route !== "string") {
    return null;
  }
  var trimmed = route.trim();
  return trimmed === "" ? null : trimmed;
}

// Build an Error carrying a stable machine-readable `.reason` code (so callers branch on
// the reason, not on brittle message text). Same shape as the geolocation wrapper.
function oskPushError(reason, message) {
  var err = new Error(message || reason);
  err.reason = reason;
  return err;
}

// Trim trailing slashes off a base URL so `base + "/api/v1/…"` never doubles the slash.
function oskPushTrimBase(base) {
  return String(base || "").replace(/\/+$/, "");
}

// ---------------------------------------------------------------------------
// BACKEND CALLS (pure, dependency-injected — the register/unregister HTTP)
// Both mirror web/history.js's oskFetchHistory pattern: injectable getToken /
// apiBaseUrl / fetchImpl, and they NEVER throw — any failure (offline, CORS, backend
// down, token error) resolves to a typed status descriptor so the caller can log/retry
// without an unhandled rejection. Statuses:
//   "ok"           — backend accepted it (POST 2xx / DELETE 204)
//   "invalid"      — no push token to send (nothing attempted)
//   "no-session"   — getToken() returned null (signed out / auth not configured)
//   "unauthorized" — backend replied 401 (token rejected/expired)
//   "http-error"   — any other non-2xx (carries httpStatus)
//   "network-error"— the request threw/rejected
// ---------------------------------------------------------------------------

// POST /api/v1/me/devices — register (upsert) this device's push token for the caller.
// Body is { token, platform }; identity comes from the Bearer token, never the body
// (the backend derives the owner from the verified Firebase token — see DeviceController).
function oskPushRegisterWithBackend(opts) {
  var o = opts || {};
  var pushToken = o.token;
  var platform = o.platform || OSK_PUSH_PLATFORM.ANDROID;
  var getToken = o.getToken;
  var fetchImpl = o.fetchImpl;
  var base = oskPushTrimBase(o.apiBaseUrl);

  if (typeof pushToken !== "string" || pushToken.trim() === "") {
    return Promise.resolve({ status: "invalid" });
  }
  return Promise.resolve()
    .then(function () {
      return typeof getToken === "function" ? getToken() : null;
    })
    .then(function (idToken) {
      if (!idToken) {
        return { status: "no-session" };
      }
      return fetchImpl(base + "/api/v1/me/devices", {
        method: "POST",
        headers: {
          Authorization: "Bearer " + idToken,
          "Content-Type": "application/json",
          Accept: "application/json",
        },
        body: JSON.stringify({ token: pushToken, platform: platform }),
        cache: "no-store",
      }).then(function (r) {
        if (r.status === 401) {
          return { status: "unauthorized" };
        }
        if (!r.ok) {
          return { status: "http-error", httpStatus: r.status };
        }
        return { status: "ok" };
      });
    })
    .catch(function () {
      return { status: "network-error" };
    });
}

// DELETE /api/v1/me/devices/{token} — deregister this device's token on sign-out. The
// token is URL-encoded into the path (FCM tokens contain ':' etc.); the backend decodes
// the @PathVariable and treats a repeated/unknown delete as an idempotent 204 no-op.
function oskPushDeregisterWithBackend(opts) {
  var o = opts || {};
  var pushToken = o.token;
  var getToken = o.getToken;
  var fetchImpl = o.fetchImpl;
  var base = oskPushTrimBase(o.apiBaseUrl);

  if (typeof pushToken !== "string" || pushToken.trim() === "") {
    return Promise.resolve({ status: "invalid" });
  }
  return Promise.resolve()
    .then(function () {
      return typeof getToken === "function" ? getToken() : null;
    })
    .then(function (idToken) {
      if (!idToken) {
        return { status: "no-session" };
      }
      return fetchImpl(base + "/api/v1/me/devices/" + encodeURIComponent(pushToken), {
        method: "DELETE",
        headers: {
          Authorization: "Bearer " + idToken,
          Accept: "application/json",
        },
        cache: "no-store",
      }).then(function (r) {
        if (r.status === 401) {
          return { status: "unauthorized" };
        }
        if (!r.ok) {
          return { status: "http-error", httpStatus: r.status };
        }
        return { status: "ok" };
      });
    })
    .catch(function () {
      return { status: "network-error" };
    });
}

// ---------------------------------------------------------------------------
// Controller factory. Given the injected environment, returns the public push API:
//   mode()                     -> 'native' | 'none'
//   platform()                 -> 'ANDROID' | 'IOS' | 'WEB'
//   requestPermission()        -> Promise<'granted'|'denied'|'prompt'|'unavailable'>
//   register()                 -> Promise<{ status }>   (wires listeners + register())
//   unregister()               -> Promise<{ status, result? }> (DELETE + tear down)
//   handleRegistrationToken(t) -> Promise<result>       (the token -> backend step)
//   handleActionPerformed(a)   -> string|null           (deep-link seam, OSK-156)
//   handleReceived(n)          -> void                  (foreground receive hook)
//   lastToken()                -> string|null           (most recent FCM token)
// All re-detect the mode on each call so a Capacitor runtime that finishes injecting
// after this module loads is still picked up.
//
// deps:
//   capacitor   — window.Capacitor (or a stub)
//   getToken    — () => Promise<string|null>   (browser: OSKAuth.getIdToken)
//   apiBaseUrl  — backend base URL             (browser: config.js apiBaseUrl)
//   fetchImpl   — fetch-compatible fn          (browser: window.fetch; test: a stub)
//   onRoute     — (route, action) => void      OSK-156 seam; called on tap when a route
//                 is present. Default: no-op (this file never navigates).
//   onRegistration/onReceived/onError — optional observers for logging/UX.
//   logger      — console-like { info?, warn? }; default silent.
// ---------------------------------------------------------------------------
function oskCreatePushClient(deps) {
  deps = deps || {};
  var env = { capacitor: deps.capacitor };
  var getToken = deps.getToken;
  var apiBaseUrl = deps.apiBaseUrl;
  var fetchImpl = deps.fetchImpl;
  var onRoute = typeof deps.onRoute === "function" ? deps.onRoute : function () {};
  var onRegistration =
    typeof deps.onRegistration === "function" ? deps.onRegistration : function () {};
  var onReceived = typeof deps.onReceived === "function" ? deps.onReceived : function () {};
  var onError = typeof deps.onError === "function" ? deps.onError : function () {};
  var logger = deps.logger || {};
  function logInfo(msg, extra) {
    if (typeof logger.info === "function") {
      logger.info(msg, extra);
    }
  }
  function logWarn(msg, extra) {
    if (typeof logger.warn === "function") {
      logger.warn(msg, extra);
    }
  }

  // The most recent FCM token this device has been issued. Set the instant the
  // `registration` event fires (before the backend POST), so unregister() can DELETE the
  // right token even if the POST itself failed — the device DID register with FCM.
  var lastToken = null;
  // Live native listener handles, so unregister() can remove exactly what register()
  // attached (each is a Capacitor PluginListenerHandle with .remove()).
  var listenerHandles = [];
  var listenersAttached = false;

  function mode() {
    return oskPushDetectMode(env);
  }
  function platform() {
    return oskPushPlatformFor(env.capacitor);
  }
  function plugin() {
    return env.capacitor.Plugins.PushNotifications;
  }

  // Ask for the OS notification permission. NATIVE → a real Android runtime prompt via
  // the plugin (its result is { receive: <state> }). NONE → "unavailable".
  function requestPermission() {
    if (mode() !== OSK_PUSH_MODE.NATIVE) {
      return Promise.resolve("unavailable");
    }
    var p = plugin();
    return Promise.resolve()
      .then(function () {
        return p.requestPermissions();
      })
      .then(function (res) {
        var state = (res && res.receive) || "prompt";
        return oskPushNormalizePermissionState(state);
      });
  }

  // The token → backend step. Exposed publicly (and wired to the `registration` event)
  // so both the live flow and the sim exercise the exact same code. Records lastToken,
  // POSTs the token, then notifies the onRegistration observer with { token, result }.
  // Never throws (the backend helper resolves a status descriptor).
  function handleRegistrationToken(token) {
    lastToken = typeof token === "string" && token.trim() !== "" ? token : lastToken;
    return oskPushRegisterWithBackend({
      token: token,
      platform: platform(),
      getToken: getToken,
      apiBaseUrl: apiBaseUrl,
      fetchImpl: fetchImpl,
    }).then(function (result) {
      if (result.status === "ok") {
        logInfo("[push] device token registered with backend");
      } else {
        logWarn("[push] backend registration returned status: " + result.status, result);
      }
      onRegistration({ token: token, result: result });
      return result;
    });
  }

  // Deep-link SEAM (OSK-156). Pull the route out of a tapped notification and hand it to
  // onRoute — WITHOUT navigating here. Returns the route (or null) so the sim can assert
  // the extraction. OSK-156 replaces the injected onRoute with real navigation.
  function handleActionPerformed(action) {
    var route = oskPushExtractRoute(action);
    logInfo("[push] notification tapped" + (route ? " (route: " + route + ")" : ""));
    try {
      onRoute(route, action);
    } catch (e) {
      // A bad route handler must not turn a tap into an unhandled rejection.
      logWarn("[push] onRoute handler threw", e);
    }
    return route;
  }

  // Foreground-receive hook. A push arriving while the app is open does not raise a
  // system tray notification on Android; surfacing it in-app is future UX (kept as a
  // hook). We just forward it to the onReceived observer.
  function handleReceived(notification) {
    logInfo("[push] notification received in foreground");
    try {
      onReceived(notification);
    } catch (e) {
      logWarn("[push] onReceived handler threw", e);
    }
  }

  // Attach the four native listeners exactly once. Done BEFORE calling register() so no
  // `registration` event can be missed. Each handle is kept for teardown.
  function attachListeners() {
    if (listenersAttached) {
      return;
    }
    var p = plugin();
    listenersAttached = true;
    // The FCM registration token arrived — send it to the backend.
    listenerHandles.push(
      p.addListener("registration", function (token) {
        handleRegistrationToken(token && token.value);
      })
    );
    // FCM failed to register (commonly: no google-services.json / FCM not enabled yet —
    // the OSK-165 gate). Degrade gracefully: log + notify, never crash.
    listenerHandles.push(
      p.addListener("registrationError", function (error) {
        logWarn("[push] registration error (is FCM configured? see OSK-165)", error);
        onError(error);
      })
    );
    // A push arrived while the app is in the foreground.
    listenerHandles.push(
      p.addListener("pushNotificationReceived", function (notification) {
        handleReceived(notification);
      })
    );
    // The user tapped a notification — hand the deep-link route to the seam (OSK-156).
    listenerHandles.push(
      p.addListener("pushNotificationActionPerformed", function (action) {
        handleActionPerformed(action);
      })
    );
  }

  // Remove all native listeners this controller attached and forget them, so a later
  // register() starts clean. Best-effort: a handle without .remove() is skipped.
  function detachListeners() {
    var handles = listenerHandles;
    listenerHandles = [];
    listenersAttached = false;
    return Promise.all(
      handles.map(function (h) {
        // addListener resolves to the handle (Promise) in the real plugin; tolerate both
        // a handle and a promise-of-handle.
        return Promise.resolve(h)
          .then(function (handle) {
            if (handle && typeof handle.remove === "function") {
              return handle.remove();
            }
          })
          .catch(function () {
            /* teardown must not throw */
          });
      })
    );
  }

  // Full registration flow. NONE → graceful no-op ({ status: "unsupported" }). NATIVE →
  // wire listeners, request permission, and (only if granted) call plugin.register() to
  // make FCM mint the token, which then arrives asynchronously via the `registration`
  // listener and is POSTed to the backend by handleRegistrationToken.
  //   returns { status: "registering" }  — granted; register() called, token pending
  //           { status: "denied" }        — permission denied; nothing registered
  //           { status: "unsupported" }   — not on a native push platform (no-op)
  function register() {
    if (mode() !== OSK_PUSH_MODE.NATIVE) {
      return Promise.resolve({ status: "unsupported" });
    }
    var p = plugin();
    attachListeners();
    return requestPermission().then(function (perm) {
      if (perm !== "granted") {
        logWarn("[push] notification permission not granted: " + perm);
        return { status: "denied", permission: perm };
      }
      return Promise.resolve()
        .then(function () {
          return p.register();
        })
        .then(function () {
          logInfo("[push] register() called; awaiting FCM token via registration event");
          return { status: "registering" };
        });
    });
  }

  // Tear-down on sign-out. NONE → no-op ({ status: "unsupported" }). NATIVE → DELETE the
  // last known token from the backend, then remove the native listeners. Returns the
  // backend result so callers can log it. Never rejects.
  function unregister() {
    if (mode() !== OSK_PUSH_MODE.NATIVE) {
      return Promise.resolve({ status: "unsupported" });
    }
    var token = lastToken;
    return oskPushDeregisterWithBackend({
      token: token,
      getToken: getToken,
      apiBaseUrl: apiBaseUrl,
      fetchImpl: fetchImpl,
    }).then(function (result) {
      if (result.status === "ok") {
        logInfo("[push] device token deregistered from backend");
      } else {
        logWarn("[push] backend deregistration returned status: " + result.status, result);
      }
      return detachListeners().then(function () {
        lastToken = null;
        return { status: result.status, result: result };
      });
    });
  }

  return {
    mode: mode,
    platform: platform,
    requestPermission: requestPermission,
    register: register,
    unregister: unregister,
    handleRegistrationToken: handleRegistrationToken,
    handleActionPerformed: handleActionPerformed,
    handleReceived: handleReceived,
    lastToken: function () {
      return lastToken;
    },
  };
}

// ===========================================================================
// NODE EXPORT — expose the pure helpers + factory for the headless simulation.
// `typeof module` is "undefined" in a classic browser <script>, so this is a no-op there.
// ===========================================================================
if (typeof module !== "undefined" && module.exports) {
  module.exports = {
    OSK_PUSH_MODE: OSK_PUSH_MODE,
    OSK_PUSH_PLATFORM: OSK_PUSH_PLATFORM,
    OSK_PUSH_ROUTE_KEY: OSK_PUSH_ROUTE_KEY,
    detectMode: oskPushDetectMode,
    platformFor: oskPushPlatformFor,
    normalizePermissionState: oskPushNormalizePermissionState,
    extractRoute: oskPushExtractRoute,
    registerWithBackend: oskPushRegisterWithBackend,
    deregisterWithBackend: oskPushDeregisterWithBackend,
    createPushClient: oskCreatePushClient,
  };
}

// ===========================================================================
// BROWSER BOOTSTRAP — runs ONLY in a real browser (guarded by `typeof window`).
// Builds the real environment from window.Capacitor, window.OSKAuth and the config's
// apiBaseUrl, publishes the controller on window.OSKPush (so OSK-156 and other modules
// can reuse it), and — WHEN on a native push platform — auto-registers once the user is
// signed in and deregisters on sign-out. In a plain browser mode() is NONE, so every
// path is a no-op and nothing runs beyond feature-detection.
// ===========================================================================
if (typeof window !== "undefined") {
  (function () {
    "use strict";

    var cfg = window.__APP_CONFIG__ || {};

    var push = oskCreatePushClient({
      capacitor: window.Capacitor,
      // Bearer token for the backend calls comes from the web auth module (OSK-74).
      getToken: function () {
        return window.OSKAuth && typeof window.OSKAuth.getIdToken === "function"
          ? window.OSKAuth.getIdToken()
          : Promise.resolve(null);
      },
      apiBaseUrl: cfg.apiBaseUrl,
      fetchImpl: typeof window.fetch === "function" ? window.fetch.bind(window) : undefined,
      // OSK-156 SEAM: route a tapped notification into in-app navigation. The deep-link
      // router (web/deep-link.js) owns the allowlist + navigation + cold-start persistence;
      // we look it up LAZILY on each tap (window.OSKDeepLink) so script include order does
      // not matter, and fall back to a harmless log if it is somehow absent. deep-link.js
      // itself sanitises the route against an allowlist, so an unknown/hostile route here is
      // a safe no-op and can never open-redirect the WebView.
      onRoute: function (route) {
        var dl = window.OSKDeepLink;
        if (dl && typeof dl.handleTapRoute === "function") {
          dl.handleTapRoute(route);
        } else if (route) {
          // eslint-disable-next-line no-console
          console.info("[push] deep-link router unavailable; route ignored: " + route);
        }
      },
      logger: window.console,
    });

    // Public handle for reuse elsewhere (idempotent: last include wins, harmless).
    window.OSKPush = push;

    // Only the native shell has a push plugin; in a browser this is all a no-op, so we
    // don't even bother subscribing to auth changes there.
    if (push.mode() !== OSK_PUSH_MODE.NATIVE) {
      return;
    }

    // Drive register/unregister off the signed-in state: register once the user is signed
    // in, deregister on sign-out. Guarded so we don't re-register on every token refresh.
    var registered = false;
    function onAuth(user) {
      if (user && !registered) {
        registered = true;
        push.register().catch(function () {
          /* register() handles its own errors; keep this a no-op safety net */
        });
      } else if (!user && registered) {
        registered = false;
        push.unregister().catch(function () {
          /* unregister() handles its own errors */
        });
      }
    }

    if (window.OSKAuth && typeof window.OSKAuth.onAuthStateChanged === "function") {
      window.OSKAuth.onAuthStateChanged(onAuth);
    }
  })();
}
