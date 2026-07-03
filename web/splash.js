/*
 * splash.js — hide the native mobile splash screen once the web app is ready.
 *
 * OSK-158. Context: on the Capacitor Android shell (see capacitor.config.ts) a
 * branded native splash is shown from the very first frame of a cold start,
 * while the OS spins up the WebView and this page loads. This module is the
 * "hide-on-ready" hook: as soon as the app has painted, it tells the native
 * SplashScreen plugin to dismiss, so the splash covers exactly the load gap and
 * no longer.
 *
 * Why it is safe everywhere:
 *  - PURE NO-OP IN A BROWSER. On the live https site there is no Capacitor
 *    runtime, so `window.Capacitor` is undefined and this module does nothing.
 *    Firebase Hosting serves the identical file; only the native WebView reacts.
 *  - NO BUNDLER / NO IMPORTS. web/ is hand-authored static JS with no build
 *    step, so we do NOT `import` from '@capacitor/splash-screen'. Instead we call
 *    the plugin through the global bridge object that Capacitor's native runtime
 *    injects into the WebView (`window.Capacitor.Plugins.SplashScreen`). The
 *    native side of the plugin is installed via `npx cap sync android`, which is
 *    all that is needed for this global call to resolve on-device.
 *  - BELT AND BRACES. capacitor.config.ts also sets `launchAutoHide: true` with
 *    a `launchShowDuration` cap, so even if this hook never ran the splash could
 *    not stick. This hook simply dismisses it *sooner* — the moment the app is
 *    actually ready — for a snappier launch.
 */
(function () {
  "use strict";

  // Guard so the hide is attempted at most once even if several ready-signals
  // (DOMContentLoaded, load, the timeout fallback) fire.
  var hidden = false;

  function hideNativeSplash() {
    if (hidden) return;
    hidden = true;

    try {
      var cap = window.Capacitor;

      // Not running inside a Capacitor native shell (e.g. a normal browser) —
      // nothing to hide. This is the case for the live web deployment.
      if (!cap || typeof cap.isNativePlatform !== "function" || !cap.isNativePlatform()) {
        return;
      }

      var splash = cap.Plugins && cap.Plugins.SplashScreen;
      if (splash && typeof splash.hide === "function") {
        // Fire-and-forget: the plugin returns a promise, but we neither need to
        // await it nor let a rejection surface as an unhandled error.
        Promise.resolve(splash.hide()).catch(function () {
          /* ignore — the native auto-hide fallback still applies */
        });
      }
    } catch (e) {
      // Never let splash housekeeping break page startup.
    }
  }

  // Fire as soon as the DOM is ready. If the document has already parsed by the
  // time this script runs (it is included at the foot of the page), hide now.
  if (document.readyState === "complete" || document.readyState === "interactive") {
    hideNativeSplash();
  } else {
    document.addEventListener("DOMContentLoaded", hideNativeSplash, { once: true });
  }

  // Extra safety net inside the WebView: also hide on full `load`, and on a short
  // timeout, so a delayed/absent DOMContentLoaded can never leave the splash up
  // longer than necessary.
  window.addEventListener("load", hideNativeSplash, { once: true });
  window.setTimeout(hideNativeSplash, 2000);
})();
