/*
 * deep-link.js — OSK-156. Turn a tapped push notification into in-app NAVIGATION.
 *
 * WHAT it does: when the user taps a push notification, the push client (OSK-150,
 * web/push-client.js) fires its `pushNotificationActionPerformed` listener, extracts the
 * `data.route` value (the OSK-166 wire contract, backend/push/PushData.ROUTE = "route")
 * and hands that raw route string to this module. This module decides WHERE to go:
 *   1) it SANITISES the route against a strict allowlist of known in-app screens
 *      (oskDeepLinkResolveRoute) — an unknown/hostile/blank route resolves to null and is
 *      a safe no-op, so a notification can NEVER open-redirect the WebView to an
 *      attacker-controlled or off-origin URL (see the SECURITY note below), and
 *   2) it navigates the app to the mapped page path, handling BOTH runtime cases:
 *        (a) app already RUNNING — navigate immediately (window.location), and
 *        (b) COLD START — the app was launched BY the tap and the tap event can arrive
 *            while the first page is still parsing: persist the pending route to
 *            localStorage and consume it on the next app load, so the user still lands on
 *            the right screen even if we couldn't navigate at the instant of the tap.
 *
 * WHY an allowlist (SECURITY — the load-bearing decision). The route ultimately originates
 *   off-device: it rides in the FCM `data` map that a push SENDER controls. Treating it as
 *   a URL and navigating to it verbatim would be a classic open-redirect / arbitrary-nav
 *   sink (`javascript:` execution, `//evil.example` protocol-relative, `https://evil…`
 *   off-origin, `data:` etc.). So this module NEVER navigates to the raw route. It maps the
 *   route's FIRST path segment through a fixed, in-repo ALLOWLIST to one of a small set of
 *   KNOWN same-origin page files we ship (app/status/ops/help/admin/profile/history/index).
 *   Anything not in that map — including every URL with a scheme, a protocol-relative
 *   prefix, a backslash trick, or simply an unknown screen name — resolves to null and is
 *   ignored. Query strings and fragments on the route are dropped (we navigate only to the
 *   bare page path we control), so no attacker-controlled text is ever forwarded into the
 *   destination URL. Adding a NEW deep-linkable screen is a deliberate one-line edit to
 *   OSK_DEEPLINK_ALLOWLIST below — never an implicit "trust whatever the payload says".
 *
 * WHY it maps to ".html" file paths (not the clean "/status" rewrite paths). Inside the
 *   Capacitor Android WebView the app is served from the bundled web/ directory over
 *   https://localhost/ (see capacitor.config.ts — bundled-assets mode), where there is NO
 *   Firebase Hosting rewrite server, so only the real files (/status.html) resolve. On the
 *   live site /status.html is served too (the rewrite is /status -> /status.html). Mapping
 *   to the concrete file therefore works IDENTICALLY in the WebView and in the browser.
 *
 * WHY it is safe on the live web deploy AND requires no FCM. This is the same shape as
 *   push-client.js / splash.js: a plain (non-module) browser script that ALSO exports its
 *   pure helpers for Node when require()d, with all browser globals behind `typeof` guards.
 *   In a plain browser there is no push plugin, so no tap ever fires; the only thing this
 *   module does on load is publish `window.OSKDeepLink` and run consumePendingRoute() once
 *   (a no-op when nothing is pending). It embeds NO secrets and makes NO network calls —
 *   it only ever navigates same-origin. Live push delivery is still gated on the human FCM
 *   step (OSK-165, google-services.json — deliberately not committed); this routing logic
 *   is correct and fully testable TODAY with the plugin mocked (web/e2e/deep-link.simulation.cjs)
 *   and lights up for real the moment OSK-165 lands, with no change here.
 */

// ===========================================================================
// PURE HELPERS + CONSTANTS (no browser globals — safe to require() from Node)
// ===========================================================================

// The FCM `data`-map key carrying the in-app route. Shared wire contract with the backend
// (backend/push/PushData.ROUTE = "route", OSK-166) and the push client
// (web/push-client.js OSK_PUSH_ROUTE_KEY). Kept here too so this module is self-describing.
// Do NOT rename the string value — it is matched on the wire.
var OSK_DEEPLINK_ROUTE_KEY = "route";

// localStorage key under which a COLD-START pending route is stashed between the tap (which
// may fire before the first page can navigate) and the next app load that consumes it.
// Namespaced ("osk.…") so it can't collide with any other feature's storage.
var OSK_DEEPLINK_STORAGE_KEY = "osk.pushDeepLinkPendingRoute";

// The ALLOWLIST: the ONLY routes we will navigate to, mapping a normalised route "screen
// key" to a concrete, same-origin page file we actually ship in web/. This is the security
// boundary — a route whose screen key is not a key here is rejected (resolves to null).
//
// Keys are the first path segment of the route, lower-cased (so "/status", "status",
// "/status?x=1" and "/status/anything" all key on "status"). Several intuitive aliases for
// the signed-in home ("", "/", "home", "app") all map to the app dashboard (app.html) — the
// in-app screen where the push client itself lives. Values are the destination paths passed
// straight to window.location; because we fully control them, they are inherently safe.
//
// To add a deep-linkable screen: add ONE entry here (key = route screen name, value =
// "/<page>.html"). That is the single, reviewed place trust is granted.
var OSK_DEEPLINK_ALLOWLIST = {
  "": "/app.html", // a bare "/" route means "open the app home"
  home: "/app.html",
  app: "/app.html",
  index: "/index.html",
  status: "/status.html",
  ops: "/ops.html",
  help: "/help.html",
  admin: "/admin.html",
  profile: "/profile.html",
  history: "/history.html",
};

// True for any string that begins with a URL scheme ("javascript:", "http:", "https:",
// "data:", "file:", "capacitor:", "mailto:", …). A legitimate in-app route is ALWAYS
// path-like and never carries a scheme, so the presence of one is a hard reject signal —
// this is the primary guard against `javascript:`-execution and off-origin redirects.
function oskDeepLinkHasScheme(s) {
  return /^[a-z][a-z0-9+.\-]*:/i.test(s);
}

// Pull the raw route string out of a tapped/received notification, if any. Duck-typed and
// defensive, mirroring push-client.js's own extractor: accepts either an ActionPerformed
// ({ notification: { data } }) or a raw notification ({ data }); a missing/blank/non-string
// route yields null. Kept here so this module can be driven directly from a notification
// object in tests without going through the push client.
function oskDeepLinkExtractRoute(input) {
  if (!input || typeof input !== "object") {
    return null;
  }
  var notification =
    input.notification && typeof input.notification === "object" ? input.notification : input;
  var data = notification && notification.data;
  if (!data || typeof data !== "object") {
    return null;
  }
  var route = data[OSK_DEEPLINK_ROUTE_KEY];
  if (typeof route !== "string") {
    return null;
  }
  var trimmed = route.trim();
  return trimmed === "" ? null : trimmed;
}

// Normalise an app path / route to a bare "page key" for same-page comparison: strip any
// query/fragment, leading/trailing slashes and a trailing ".html", and lower-case it. Both
// a route ("/status") and the current location.pathname ("/status.html" in the WebView, or
// "/status" behind a Hosting rewrite) normalise to the same "status", so we can tell whether
// we are ALREADY on the target page and avoid a pointless self-navigation/reload. An empty
// result (the site root "/") normalises to "index".
function oskDeepLinkPageKey(pathOrRoute) {
  var s = String(pathOrRoute == null ? "" : pathOrRoute).trim();
  // Drop query string and hash — only the path identifies the page.
  s = s.split("?")[0].split("#")[0];
  // Strip surrounding slashes, then a trailing ".html".
  s = s.replace(/^\/+/, "").replace(/\/+$/, "");
  s = s.replace(/\.html$/i, "");
  // A multi-segment path keys on its first segment (e.g. "history/42" -> "history").
  s = s.split("/")[0];
  return s.toLowerCase() || "index";
}

// SANITISE + RESOLVE a raw route to a safe, same-origin destination path — or null.
// This is the heart of the security boundary. Returns null (caller must treat as "do
// nothing") for anything that is not a known, path-like, same-origin screen:
//   - not a non-empty string,
//   - carries a URL scheme (javascript:, http:, data:, …),
//   - is protocol-relative ("//host") or uses backslashes ("\\host", "/\host"),
//   - or whose first path segment is not a key in OSK_DEEPLINK_ALLOWLIST.
// On success returns the fixed, in-repo page path from the allowlist (never any part of the
// attacker-influenced input beyond selecting WHICH known page).
function oskDeepLinkResolveRoute(route) {
  if (typeof route !== "string") {
    return null;
  }
  var trimmed = route.trim();
  if (trimmed === "") {
    return null;
  }
  // Reject any scheme, protocol-relative, or backslash-obfuscated target outright — these
  // are the open-redirect / arbitrary-navigation vectors and never a legitimate in-app route.
  if (
    oskDeepLinkHasScheme(trimmed) ||
    trimmed.indexOf("//") === 0 ||
    trimmed.indexOf("\\") !== -1 ||
    trimmed.indexOf("://") !== -1
  ) {
    return null;
  }
  // Look the SCREEN KEY (first path segment, sans query/hash/.html) up in the allowlist.
  var key = oskDeepLinkPageKey(trimmed);
  // A bare "/" trims to "" then pageKey yields "index"; but we want "/" to mean the app
  // home, so special-case the root before the "index" fallback swallows it.
  if (trimmed.replace(/^\/+/, "").replace(/[?#].*$/, "") === "") {
    return OSK_DEEPLINK_ALLOWLIST[""];
  }
  if (Object.prototype.hasOwnProperty.call(OSK_DEEPLINK_ALLOWLIST, key)) {
    return OSK_DEEPLINK_ALLOWLIST[key];
  }
  return null;
}

// ---------------------------------------------------------------------------
// Router factory. Given an injected environment (so the same logic runs headlessly in
// web/e2e/deep-link.simulation.cjs with a fake storage + fake navigate), returns the deep-
// link API the browser bootstrap and the push client's onRoute seam call into:
//   resolveRoute(route)   -> string|null   pure allowlist resolve (exposed for callers/tests)
//   handleTapRoute(route) -> string|null   on a tap: navigate now if ready, else persist
//   consumePendingRoute() -> string|null   on app load: read+clear a stored route, navigate
//   pendingRoute()        -> string|null   peek the stored route without consuming (tests)
//   clearPendingRoute()   -> void          drop any stored route
//
// deps:
//   storage     — localStorage-like { getItem, setItem, removeItem }; optional (null-safe).
//   navigate    — (path) => void            performs the actual navigation (location.assign).
//   currentPath — () => string              the current location.pathname (for "already there?").
//   isReady     — () => boolean             can we navigate right now? (document parsed).
//                 Default: always true. When false at tap time we DEFER via persistence.
//   logger      — console-like { info?, warn? }; default silent.
// ---------------------------------------------------------------------------
function oskCreateDeepLinkRouter(deps) {
  deps = deps || {};
  var storage = deps.storage || null;
  var navigate = typeof deps.navigate === "function" ? deps.navigate : function () {};
  var currentPath = typeof deps.currentPath === "function" ? deps.currentPath : function () {
    return "";
  };
  var isReady = typeof deps.isReady === "function" ? deps.isReady : function () {
    return true;
  };
  var logger = deps.logger || {};
  function logInfo(msg) {
    if (typeof logger.info === "function") {
      logger.info(msg);
    }
  }
  function logWarn(msg, extra) {
    if (typeof logger.warn === "function") {
      logger.warn(msg, extra);
    }
  }

  // Safe storage wrappers — private/again-disabled storage (or a browser with cookies off)
  // makes localStorage throw on access; deep-linking must degrade to "navigate-only" then,
  // never crash a notification tap.
  function readPending() {
    if (!storage || typeof storage.getItem !== "function") {
      return null;
    }
    try {
      var v = storage.getItem(OSK_DEEPLINK_STORAGE_KEY);
      return typeof v === "string" && v.trim() !== "" ? v : null;
    } catch (e) {
      return null;
    }
  }
  function writePending(path) {
    if (!storage || typeof storage.setItem !== "function") {
      return;
    }
    try {
      storage.setItem(OSK_DEEPLINK_STORAGE_KEY, path);
    } catch (e) {
      /* storage full/disabled — fall back to navigate-only, never throw */
    }
  }
  function clearPending() {
    if (!storage || typeof storage.removeItem !== "function") {
      return;
    }
    try {
      storage.removeItem(OSK_DEEPLINK_STORAGE_KEY);
    } catch (e) {
      /* ignore */
    }
  }

  // Navigate to a resolved page path unless we are already on it (avoid a pointless reload).
  // Returns true if it actually navigated.
  function goTo(path) {
    if (oskDeepLinkPageKey(path) === oskDeepLinkPageKey(currentPath())) {
      logInfo("[deep-link] already on target page (" + path + ") — no navigation");
      return false;
    }
    logInfo("[deep-link] navigating to " + path);
    navigate(path);
    return true;
  }

  // A tap happened (app RUNNING or COLD start). Resolve the route through the allowlist,
  // then either navigate now (document ready) or persist it for the next load to consume
  // (cold start / not-yet-ready). An unknown/hostile/blank route is a SAFE no-op.
  function handleTapRoute(route) {
    var path = oskDeepLinkResolveRoute(route);
    if (!path) {
      // Either no route at all, or one that failed the allowlist/sanitiser — do nothing.
      logInfo("[deep-link] tap with no navigable route — ignored");
      return null;
    }
    if (isReady()) {
      // App is up and this page can navigate — go straight there.
      goTo(path);
    } else {
      // Cold start: the launching tap arrived before the first page is ready to navigate.
      // Stash it; consumePendingRoute() on the next load (below) will pick it up. We do NOT
      // also navigate here, so there is nothing stale left behind once consumed.
      logInfo("[deep-link] not ready — persisting pending route " + path);
      writePending(path);
    }
    return path;
  }

  // Called ONCE on every app load (the browser bootstrap below). If a route was persisted by
  // a cold-start tap, read+CLEAR it first (so it can never loop or go stale) and navigate to
  // it — unless we are already on that page, in which case clearing is all that's needed.
  function consumePendingRoute() {
    var stored = readPending();
    if (!stored) {
      return null;
    }
    // Clear BEFORE navigating so a persisted route is one-shot even if navigation reloads
    // the page mid-call.
    clearPending();
    // Defence in depth: never trust even our own storage blindly. We persist already-resolved
    // "/x.html" paths, so re-validate by confirming the stored path's page key is still an
    // allowlisted screen before navigating; drop anything else.
    var key = oskDeepLinkPageKey(stored);
    if (!Object.prototype.hasOwnProperty.call(OSK_DEEPLINK_ALLOWLIST, key)) {
      logWarn("[deep-link] discarding unrecognised pending route", stored);
      return null;
    }
    logInfo("[deep-link] consuming pending cold-start route " + stored);
    goTo(stored);
    return stored;
  }

  return {
    resolveRoute: oskDeepLinkResolveRoute,
    handleTapRoute: handleTapRoute,
    consumePendingRoute: consumePendingRoute,
    pendingRoute: readPending,
    clearPendingRoute: clearPending,
  };
}

// ===========================================================================
// NODE EXPORT — expose the pure helpers + factory for the headless simulation.
// `typeof module` is "undefined" in a classic browser <script>, so this is a no-op there.
// ===========================================================================
if (typeof module !== "undefined" && module.exports) {
  module.exports = {
    OSK_DEEPLINK_ROUTE_KEY: OSK_DEEPLINK_ROUTE_KEY,
    OSK_DEEPLINK_STORAGE_KEY: OSK_DEEPLINK_STORAGE_KEY,
    OSK_DEEPLINK_ALLOWLIST: OSK_DEEPLINK_ALLOWLIST,
    extractRoute: oskDeepLinkExtractRoute,
    resolveRoute: oskDeepLinkResolveRoute,
    pageKey: oskDeepLinkPageKey,
    createDeepLinkRouter: oskCreateDeepLinkRouter,
  };
}

// ===========================================================================
// BROWSER BOOTSTRAP — runs ONLY in a real browser (guarded by `typeof window`).
// Builds the real router from window.localStorage + window.location, publishes it on
// window.OSKDeepLink (so push-client.js's onRoute seam and any other module can reuse it),
// and CONSUMES any cold-start pending route on load so the user lands on the right screen.
// In a plain browser with no push, nothing ever taps, so this is inert beyond that consume.
// ===========================================================================
if (typeof window !== "undefined") {
  (function () {
    "use strict";

    var router = oskCreateDeepLinkRouter({
      storage: (function () {
        try {
          return window.localStorage;
        } catch (e) {
          // Accessing localStorage can throw in some privacy modes — degrade to null so the
          // router runs navigate-only.
          return null;
        }
      })(),
      navigate: function (path) {
        // Same-origin, allowlisted path only (resolveRoute guaranteed it). assign() keeps a
        // history entry so the OS back button behaves; the page then reloads at the target.
        window.location.assign(path);
      },
      currentPath: function () {
        return window.location && window.location.pathname ? window.location.pathname : "";
      },
      // On load the document is parsed by the time this foot-of-body script runs, so a tap
      // arriving now can navigate immediately; a tap arriving DURING parse (readyState
      // "loading") defers to persistence.
      isReady: function () {
        return document.readyState === "interactive" || document.readyState === "complete";
      },
      logger: window.console,
    });

    // Public handle so push-client.js (OSK-150) can route taps into this, and other modules
    // can trigger a deep-link. Idempotent: last include wins, harmless.
    window.OSKDeepLink = router;

    // COLD START: if the app was launched by a tap that persisted a route before this page
    // could navigate, consume it now (read+clear+navigate). No-op when nothing is pending —
    // which is every normal load and every plain-browser visit.
    function consumeOnce() {
      try {
        router.consumePendingRoute();
      } catch (e) {
        /* deep-link housekeeping must never break page startup */
      }
    }
    if (document.readyState === "interactive" || document.readyState === "complete") {
      consumeOnce();
    } else {
      document.addEventListener("DOMContentLoaded", consumeOnce, { once: true });
    }
  })();
}
