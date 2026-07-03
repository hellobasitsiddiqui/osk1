// auth.js — OpenSkeleton web auth module + protected-route guard core (OSK-74).
//
// WHAT: the single place the web front-end talks to Firebase Authentication. It
//   - reads the Firebase Web App config from config.js (window.__APP_CONFIG__.firebase),
//   - LAZILY loads the Firebase JS SDK (v10 modular) from gstatic at a PINNED version,
//   - initialises Firebase and exposes a tiny, dependency-free auth API on
//     `window.OSKAuth`: signInWithEmailPassword, signInWithGoogle, signOut,
//     onAuthStateChanged(cb) and getIdToken() (for `Authorization: Bearer` calls), and
//   - ships the PURE guard-decision helpers (computeGuardView / applyGuardView) that the
//     protected /app page uses to show the app only when signed in and a sign-in
//     affordance when signed out.
//
// WHY it is a plain (non-module) script AND Node-requirable (UMD-ish):
//   - The other web pages load their scripts as plain, non-hashed <script src> tags so
//     CD can inject config at deploy time without a bundler (see index.html / config.js).
//     auth.js follows the same convention: <script src="/auth.js"> on the protected page.
//   - The Firebase SDK itself is v10 *modular* (ESM on gstatic). We pull it in with a
//     DYNAMIC `import()` (an expression that is valid in a classic script) rather than a
//     top-level `import` — so this file stays a classic script, `node --check auth.js`
//     passes, and the heavy SDK is only fetched when auth is actually configured.
//   - The pure decision helpers are additionally exported via `module.exports` when this
//     file is required from Node (the browser bootstrap is skipped when `window` is
//     absent). That lets the guard's show/hide logic be unit-simulated with a fake
//     auth-state in Node, with ZERO browser — see web/e2e/auth-guard.simulation.cjs.
//
// GRACEFUL "auth not configured" (a hard requirement): if apiKey is empty/missing (the
//   committed default until the human registers the Firebase Web App — OSK-92), auth.js
//   NEVER crashes and never touches the network. It resolves to a clear "not-configured"
//   state; the guard renders an explanatory notice and keeps protected content hidden.
//
// NO SECRETS: the Firebase web apiKey is a PUBLIC client identifier (not a classic
//   secret); nothing sensitive is embedded here. Access is enforced by Firebase Auth and
//   the backend verifying the ID token on /api/v1/me — never by hiding this file.

// ---------------------------------------------------------------------------
// Pinned Firebase JS SDK version. Kept as a single constant so there is exactly
// one number to bump. gstatic serves the modular ESM build at this exact path:
//   https://www.gstatic.com/firebasejs/<VERSION>/firebase-app.js
//   https://www.gstatic.com/firebasejs/<VERSION>/firebase-auth.js
// Pinning (not "latest") makes the bytes we load reproducible and lets Firebase
// Hosting / the browser cache them immutably.
// ---------------------------------------------------------------------------
var OSK_FIREBASE_SDK_VERSION = "10.14.1";

// ===========================================================================
// PURE, ENVIRONMENT-AGNOSTIC HELPERS
// These take plain data and return plain data (or mutate plain objects). They
// touch no globals, no network and no Firebase, so they run identically in the
// browser and under `node`/`require` — which is exactly what makes the guard's
// show/hide behaviour unit-testable without a browser.
// ===========================================================================

// Is a Firebase Web App config usable? "Usable" == a non-empty apiKey. The other
// fields are prefilled project constants; apiKey is the value the human injects
// (OSK-92), so its presence is the single source of truth for "configured".
// Defensive against a missing config object / non-string / whitespace-only key.
function oskIsFirebaseConfigured(firebaseCfg) {
  return !!(
    firebaseCfg &&
    typeof firebaseCfg.apiKey === "string" &&
    firebaseCfg.apiKey.trim() !== ""
  );
}

// Map a full auth STATE snapshot to a VIEW descriptor for the guard. Pure: given
// the same state it always returns the same plain object, so it is trivial to
// assert in a Node simulation.
//
// state = {
//   configured: boolean,     // is a Firebase apiKey present? (from oskIsFirebaseConfigured)
//   ready:      boolean,     // has auth settled? (SDK loaded + first auth callback, OR not-configured)
//   user:       object|null, // the signed-in Firebase user, or null when signed out
//   error:      string|null, // SDK load / init error message, if any
// }
//
// returns {
//   mode:       "not-configured" | "error" | "loading" | "signed-out" | "signed-in",
//   showSignIn: boolean,   // render the email/password + Google sign-in affordance
//   showApp:    boolean,   // render the protected content
//   showNotice: boolean,   // render the status notice line (below)
//   noticeText: string,    // human-readable explanation for the current mode
// }
function oskComputeGuardView(state) {
  var s = state || {};

  // 1) Not configured — the committed default until OSK-92. Explain, hide everything
  //    interactive. This is a first-class state, NOT an error.
  if (!s.configured) {
    return {
      mode: "not-configured",
      showSignIn: false,
      showApp: false,
      showNotice: true,
      noticeText:
        "Sign-in is not configured yet. A Firebase Web App must be registered " +
        "to enable authentication (see OSK-92). Protected content is unavailable " +
        "until then.",
    };
  }

  // 2) Configured but the SDK failed to load/init — degrade to a clear notice rather
  //    than a blank/broken page.
  if (s.error) {
    return {
      mode: "error",
      showSignIn: false,
      showApp: false,
      showNotice: true,
      noticeText: "Could not initialise sign-in: " + s.error,
    };
  }

  // 3) Configured, SDK still loading / first auth state not yet known.
  if (!s.ready) {
    return {
      mode: "loading",
      showSignIn: false,
      showApp: false,
      showNotice: true,
      noticeText: "Checking your sign-in status…",
    };
  }

  // 4) Ready + signed in — reveal the protected content, hide the sign-in form.
  if (s.user) {
    return {
      mode: "signed-in",
      showSignIn: false,
      showApp: true,
      showNotice: false,
      noticeText: "",
    };
  }

  // 5) Ready + signed out — show the sign-in affordance, hide protected content.
  return {
    mode: "signed-out",
    showSignIn: true,
    showApp: false,
    showNotice: false,
    noticeText: "",
  };
}

// Apply a VIEW descriptor to a set of DOM-ish elements by toggling their `hidden`
// flag and (for the notice) its text. Deliberately duck-typed: it only sets
// `.hidden` / `.textContent`, so the Node simulation can pass plain
// `{ hidden: true, textContent: "" }` stand-ins and assert the result — no jsdom,
// no browser. Any element may be omitted (guarded), so partial DOMs are safe.
//
// els = { signIn, app, notice, noticeText } (each optional)
function oskApplyGuardView(view, els) {
  var v = view || {};
  var e = els || {};
  if (e.signIn) { e.signIn.hidden = !v.showSignIn; }
  if (e.app) { e.app.hidden = !v.showApp; }
  if (e.notice) { e.notice.hidden = !v.showNotice; }
  if (e.noticeText) { e.noticeText.textContent = v.noticeText || ""; }
}

// ---------------------------------------------------------------------------
// Node export. When this file is `require()`d (CommonJS), expose the pure helpers
// so the guard logic can be simulated without a browser. `typeof module` is
// "undefined" in a classic browser <script>, so this block is a no-op there.
// ---------------------------------------------------------------------------
if (typeof module !== "undefined" && module.exports) {
  module.exports = {
    OSK_FIREBASE_SDK_VERSION: OSK_FIREBASE_SDK_VERSION,
    isFirebaseConfigured: oskIsFirebaseConfigured,
    computeGuardView: oskComputeGuardView,
    applyGuardView: oskApplyGuardView,
  };
}

// ===========================================================================
// BROWSER BOOTSTRAP
// Everything below runs ONLY in a real browser (guarded by `typeof window`).
// It wires window.OSKAuth: the async Firebase-backed auth API plus the pure
// helpers above (re-exposed so a page's inline guard script can reach them).
// ===========================================================================
if (typeof window !== "undefined") {
  (function () {
    "use strict";

    var cfg = (window.__APP_CONFIG__ || {}).firebase || {};
    var configured = oskIsFirebaseConfigured(cfg);

    // Live auth state, mutated as the SDK loads and auth changes. `oskComputeGuardView`
    // consumes exactly this shape.
    var state = {
      configured: configured,
      ready: false,
      user: null,
      error: null,
    };

    // Firebase handles, populated once the SDK loads. `authMod` is the modular
    // firebase-auth namespace (GoogleAuthProvider, signInWithPopup, …); `auth` is the
    // initialised Auth instance whose `.currentUser` we read for tokens.
    var authMod = null;
    var auth = null;

    // Subscribers to auth-state changes (the guard registers one here). Each is called
    // with the current Firebase user (or null). A Set makes unsubscribe trivial.
    var subscribers = new Set();
    function notify() {
      subscribers.forEach(function (cb) {
        try { cb(state.user); } catch (e) { /* a bad subscriber must not break others */ }
      });
    }

    // A promise that resolves once auth has SETTLED — i.e. the SDK has loaded and the
    // first auth callback has fired, OR we determined auth is not configured / errored.
    // The guard awaits this so it can render the not-configured / error / signed-out
    // states even when no auth-state event will ever arrive.
    var resolveReady;
    var readyPromise = new Promise(function (resolve) { resolveReady = resolve; });
    var readySettled = false;
    function markReady() {
      state.ready = true;
      if (!readySettled) {
        readySettled = true;
        resolveReady(state);
      }
    }

    // Lazily import the PINNED modular Firebase SDK from gstatic, initialise it, and
    // subscribe to auth changes. Runs at most once. If auth is not configured we skip
    // the network entirely and settle immediately in the not-configured state.
    var loadStarted = false;
    function ensureLoaded() {
      if (loadStarted) { return readyPromise; }
      loadStarted = true;

      // Not configured (empty apiKey — the committed default until OSK-92): do NOT load
      // the SDK, do NOT hit the network. Settle now; the guard shows its notice.
      if (!configured) {
        markReady();
        notify();
        return readyPromise;
      }

      var base = "https://www.gstatic.com/firebasejs/" + OSK_FIREBASE_SDK_VERSION + "/";
      // Dynamic import() keeps this a classic script while still pulling the ESM SDK.
      Promise.all([
        import(base + "firebase-app.js"),
        import(base + "firebase-auth.js"),
      ])
        .then(function (mods) {
          var appMod = mods[0];
          authMod = mods[1];
          var app = appMod.initializeApp({
            apiKey: cfg.apiKey,
            authDomain: cfg.authDomain,
            projectId: cfg.projectId,
            messagingSenderId: cfg.messagingSenderId,
            appId: cfg.appId,
          });
          auth = authMod.getAuth(app);
          // The first callback fires as soon as Firebase restores (or fails to restore)
          // a session — that is our "ready" signal; subsequent ones are sign-in/out.
          authMod.onAuthStateChanged(auth, function (user) {
            state.user = user || null;
            state.error = null;
            markReady();
            notify();
          });
        })
        .catch(function (err) {
          // SDK failed to load/init (offline, blocked CDN, bad config). Degrade to a
          // clear error state instead of a broken page.
          state.error = (err && err.message) ? err.message : String(err);
          markReady();
          notify();
        });

      return readyPromise;
    }

    // Kick off loading immediately so the guard settles as soon as possible. (Callers
    // that need to await it can still use `OSKAuth.ready`.)
    ensureLoaded();

    // --- Public API --------------------------------------------------------

    // Sign in with email + password. Rejects with a clear error if auth is not
    // configured or the SDK failed to load, so the caller can surface it in the UI.
    function signInWithEmailPassword(email, password) {
      return ensureLoaded().then(function () {
        if (!auth || !authMod) { throw new Error("Auth is not configured."); }
        return authMod
          .signInWithEmailAndPassword(auth, email, password)
          .then(function (cred) { return cred.user; });
      });
    }

    // Sign in with Google via a popup (GoogleAuthProvider).
    function signInWithGoogle() {
      return ensureLoaded().then(function () {
        if (!auth || !authMod) { throw new Error("Auth is not configured."); }
        var provider = new authMod.GoogleAuthProvider();
        return authMod
          .signInWithPopup(auth, provider)
          .then(function (cred) { return cred.user; });
      });
    }

    // Sign out. Resolves harmlessly (no-op) if auth was never configured/loaded.
    function signOut() {
      return ensureLoaded().then(function () {
        if (!auth || !authMod) { return; }
        return authMod.signOut(auth);
      });
    }

    // Subscribe to auth-state changes. The callback is invoked with the current user
    // (or null) on every change, AND once with the current value as soon as auth is
    // ready — so a late subscriber still gets the initial state. Returns an unsubscribe
    // function.
    function onAuthStateChanged(cb) {
      if (typeof cb !== "function") { return function () {}; }
      subscribers.add(cb);
      // Fire the current value once auth has settled (async, so the caller has finished
      // wiring up before the first call).
      readyPromise.then(function () {
        if (subscribers.has(cb)) {
          try { cb(state.user); } catch (e) { /* ignore a bad subscriber */ }
        }
      });
      return function unsubscribe() { subscribers.delete(cb); };
    }

    // Get a fresh Firebase ID token for `Authorization: Bearer <token>` calls, or null
    // when signed out / not configured. `forceRefresh` forces a token refresh.
    function getIdToken(forceRefresh) {
      return ensureLoaded().then(function () {
        if (!auth || !auth.currentUser) { return null; }
        return auth.currentUser.getIdToken(!!forceRefresh);
      });
    }

    // A snapshot of the current auth state for the guard's pure view computation.
    // Returns a fresh copy so callers can't mutate our internal state.
    function getState() {
      return {
        configured: state.configured,
        ready: state.ready,
        user: state.user,
        error: state.error,
      };
    }

    window.OSKAuth = {
      // Metadata / config.
      SDK_VERSION: OSK_FIREBASE_SDK_VERSION,
      isConfigured: function () { return configured; },

      // Auth API.
      ready: readyPromise,
      getState: getState,
      onAuthStateChanged: onAuthStateChanged,
      signInWithEmailPassword: signInWithEmailPassword,
      signInWithGoogle: signInWithGoogle,
      signOut: signOut,
      getIdToken: getIdToken,

      // Pure guard helpers, re-exposed so the protected page's inline script can render
      // without duplicating the decision logic (same functions the Node sim asserts).
      computeGuardView: oskComputeGuardView,
      applyGuardView: oskApplyGuardView,
    };
  })();
}
