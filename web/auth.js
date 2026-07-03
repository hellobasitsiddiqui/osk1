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

// ===========================================================================
// FEDERATED / SOCIAL SIGN-IN PROVIDERS (OSK-131)
// Google (OSK-74) plus Facebook and Apple, structured so adding a fourth is
// trivial: one entry in the registry below, one <button data-provider> in the
// sign-in UI, and one config toggle. Everything here is PURE (no globals, no
// network, no Firebase) — the actual provider OBJECTS are built in the browser
// bootstrap where the SDK exists. Keeping the metadata + gating pure is what lets
// web/e2e/social-login.simulation.cjs assert config-gating and error mapping with
// zero browser.
// ===========================================================================

// The registry of federated providers the web app KNOWS how to offer. PURE
// metadata only:
//   id    — the stable key used in config toggles, `data-provider`, and
//           signInWithProvider(id). Lower-case, matches the Firebase provider id
//           where one exists (google.com / facebook.com / apple.com).
//   label — the human name rendered on the button ("Continue with <label>").
// Order here is the order buttons render in. Google is first because it is the
// provider wired at bring-up (OSK-74).
var OSK_AUTH_PROVIDERS = [
  { id: "google", label: "Google" },
  { id: "facebook", label: "Facebook" },
  { id: "apple", label: "Apple" },
];

// Look up a provider descriptor by id (case-insensitive, whitespace-tolerant).
// Returns the descriptor object, or null for an unknown id — callers use null to
// refuse to render / build an unknown provider (never a broken button).
function oskFindProvider(id) {
  if (typeof id !== "string") { return null; }
  var key = id.trim().toLowerCase();
  for (var i = 0; i < OSK_AUTH_PROVIDERS.length; i++) {
    if (OSK_AUTH_PROVIDERS[i].id === key) { return OSK_AUTH_PROVIDERS[i]; }
  }
  return null;
}

// Is a given federated provider ENABLED for this config? BOTH gates must hold:
//   1) auth is configured at all (a real apiKey — the OSK-92 gate), AND
//   2) the provider is switched on in config.firebase.providers.
// The `providers` map is OPTIONAL and defensive:
//   - when the map (or the specific key) is ABSENT, Google defaults ON (it is the
//     provider enabled in the Firebase console at bring-up, OSK-74) and every
//     other provider defaults OFF — because enabling Facebook/Apple needs a HUMAN
//     Firebase-console step (client IDs/secrets) that cannot be done from code;
//   - a toggle is honoured ONLY when strictly === true, so a truthy-but-wrong
//     value (1, "yes") never accidentally shows a button that would fail;
//   - a toggle for an id NOT in the registry is ignored by callers via
//     oskFindProvider, so a typo can never render a broken button.
// This is the single source of truth for "should this provider's button show?".
function oskIsProviderEnabled(firebaseCfg, id) {
  if (!oskIsFirebaseConfigured(firebaseCfg)) { return false; }
  var desc = oskFindProvider(id);
  if (!desc) { return false; }
  var providers = firebaseCfg && firebaseCfg.providers;
  if (providers && typeof providers === "object" && desc.id in providers) {
    return providers[desc.id] === true;
  }
  // No explicit toggle for this provider: Google on by default, others off.
  return desc.id === "google";
}

// The ordered list of ENABLED provider descriptors for a config — exactly the
// buttons the sign-in UI should offer. Pure, so the sim asserts the gating.
function oskEnabledProviders(firebaseCfg) {
  var out = [];
  for (var i = 0; i < OSK_AUTH_PROVIDERS.length; i++) {
    if (oskIsProviderEnabled(firebaseCfg, OSK_AUTH_PROVIDERS[i].id)) {
      out.push(OSK_AUTH_PROVIDERS[i]);
    }
  }
  return out;
}

// Show only the ENABLED providers' buttons. `buttonsById` maps provider id ->
// a DOM-ish element (the sign-in UI's static <button data-provider> nodes). Sets
// each element's `.hidden` (true = hidden) and returns the count shown. Duck-typed
// — it only touches `.hidden` — so the Node sim passes plain `{ hidden: bool }`
// stand-ins and asserts the result with no jsdom. Missing elements are skipped
// (a page may not render every provider), so partial maps are safe.
function oskApplyProviderVisibility(firebaseCfg, buttonsById) {
  var b = buttonsById || {};
  var shown = 0;
  for (var i = 0; i < OSK_AUTH_PROVIDERS.length; i++) {
    var id = OSK_AUTH_PROVIDERS[i].id;
    var el = b[id];
    if (!el) { continue; }
    var on = oskIsProviderEnabled(firebaseCfg, id);
    el.hidden = !on;
    if (on) { shown++; }
  }
  return shown;
}

// Map a Firebase Auth failure (or any thrown value) to a SHORT, user-facing
// message. Centralised so every provider button AND the email/password form
// surface the same friendly copy for the same failure. It recognises the codes
// the popup/OAuth flow actually produces — including the two the ticket calls out
// (account-exists-with-different-credential, popup-closed-by-user) — and falls
// back to the raw message (or a generic line) for anything unrecognised. PURE, so
// the sim asserts the mapping directly. `providerLabel` personalises the copy
// (e.g. "Apple sign-in isn't enabled…"); it defaults sensibly when omitted.
function oskDescribeAuthError(err, providerLabel) {
  var who = providerLabel ? providerLabel : "That provider";
  var code = err && err.code ? String(err.code) : "";
  switch (code) {
    case "auth/account-exists-with-different-credential":
      return (
        "You already have an account with this email using a different sign-in " +
        "method. Sign in the way you did originally, then link " + who +
        " from your account settings."
      );
    case "auth/popup-closed-by-user":
      return "Sign-in was cancelled — the popup was closed before it finished.";
    case "auth/cancelled-popup-request":
      return "Only one sign-in popup can be open at a time. Try again.";
    case "auth/popup-blocked":
      return "Your browser blocked the sign-in popup. Allow popups for this site and try again.";
    case "auth/operation-not-allowed":
      return who + " sign-in isn't enabled for this project yet.";
    case "auth/unauthorized-domain":
      return "This site isn't an authorised domain for sign-in (add it in the Firebase console).";
    case "auth/network-request-failed":
      return "A network error interrupted sign-in. Check your connection and try again.";
    default:
      return err && err.message ? String(err.message) : who + " sign-in failed.";
  }
}

// Build the modular Firebase provider OBJECT for a given id, using a SUPPLIED
// Firebase-auth namespace (`authNs`). In the browser this is the real firebase-auth
// module; in the Node sim it is a STUB — which is the whole point: this function is
// pure w.r.t. globals (it reads only its arguments), so the sim exercises the EXACT
// construction the browser runs, with no SDK and no network.
//   - Google / Facebook have dedicated provider classes.
//   - Apple — and any future OIDC/OAuth provider — uses the generic OAuthProvider
//     keyed by its well-known id ("apple.com").
// Requested scopes (email, and name for Apple) mirror what the app wants back.
// Returns null for an unknown id (so the caller refuses rather than popping a broken
// flow) or when `authNs` is absent.
function oskBuildProviderWith(authNs, id) {
  if (!authNs) { return null; }
  var desc = oskFindProvider(id);
  if (!desc) { return null; }
  switch (desc.id) {
    case "google":
      return new authNs.GoogleAuthProvider();
    case "facebook": {
      var fb = new authNs.FacebookAuthProvider();
      if (fb && typeof fb.addScope === "function") { fb.addScope("email"); }
      return fb;
    }
    case "apple": {
      // Apple only returns name/email on the FIRST consent, so the backend must
      // persist them then — request both here.
      var ap = new authNs.OAuthProvider("apple.com");
      if (ap && typeof ap.addScope === "function") {
        ap.addScope("email");
        ap.addScope("name");
      }
      return ap;
    }
    default:
      return null;
  }
}

// ===========================================================================
// AVATAR OBJECT PATH (OSK-83)
// The single, PURE place that decides WHERE in Cloud Storage a user's avatar is
// written: always under the caller's OWN `users/{uid}/avatar/…` prefix, so the
// committed storage.rules (owner-only write) authorise it and the backend's
// server-side ownership check (AvatarObject) accepts it. Keeping it here (not in
// the page) means the browser upload path below and the Node simulation build the
// exact same path, and the uid always comes from the SDK's authenticated user —
// never a caller-supplied value.
// ===========================================================================

// The image extensions an avatar object may carry, mirrored on the backend
// (AvatarObject.ALLOWED_EXTENSIONS) and in storage.rules. Value = the canonical
// extension actually written (jpg/jpeg both allowed as input).
var OSK_AVATAR_EXTENSIONS = { png: "png", jpg: "jpg", jpeg: "jpeg", webp: "webp", gif: "gif" };

// Fallback extension per MIME type, used when the filename has no usable extension
// (e.g. a camera capture named "image"). Firebase sends image/jpeg for .jpg files.
var OSK_AVATAR_TYPE_EXT = { "image/png": "png", "image/jpeg": "jpg", "image/webp": "webp", "image/gif": "gif" };

// Build the storage object path for a user's avatar: `users/<uid>/avatar/<ms>.<ext>`.
// The timestamp makes each upload a fresh object (no overwrite race, and the new
// download URL changes so caches don't serve a stale avatar). The extension is taken
// from the filename when it is a known image type, else from the MIME type, else
// defaults to png — so the path always ends in an allowed image extension the backend
// accepts. Pure: deterministic given (uid, file, nowMs); no globals/DOM/SDK.
//   uid    — the authenticated user's uid (caller passes auth.currentUser.uid).
//   file   — a File-like object; only `.name` and `.type` (strings) are read.
//   nowMs  — the upload timestamp in epoch millis (Date.now() in the browser).
function oskAvatarObjectPath(uid, file, nowMs) {
  var f = file || {};
  var name = typeof f.name === "string" ? f.name : "";
  var type = typeof f.type === "string" ? f.type.toLowerCase() : "";

  var ext = "";
  var dot = name.lastIndexOf(".");
  if (dot >= 0 && dot < name.length - 1) {
    var raw = name.slice(dot + 1).toLowerCase().replace(/[^a-z0-9]/g, "");
    if (OSK_AVATAR_EXTENSIONS[raw]) { ext = OSK_AVATAR_EXTENSIONS[raw]; }
  }
  if (!ext && OSK_AVATAR_TYPE_EXT[type]) { ext = OSK_AVATAR_TYPE_EXT[type]; }
  if (!ext) { ext = "png"; }

  var stamp = typeof nowMs === "number" && isFinite(nowMs) ? Math.trunc(nowMs) : 0;
  var safeUid = String(uid == null ? "" : uid);
  return "users/" + safeUid + "/avatar/" + stamp + "." + ext;
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
    // OSK-131 — federated / social providers (pure helpers).
    AUTH_PROVIDERS: OSK_AUTH_PROVIDERS,
    findProvider: oskFindProvider,
    isProviderEnabled: oskIsProviderEnabled,
    enabledProviders: oskEnabledProviders,
    applyProviderVisibility: oskApplyProviderVisibility,
    describeAuthError: oskDescribeAuthError,
    buildProviderWith: oskBuildProviderWith,
    // OSK-83 — avatar upload (pure path builder; the browser upload lives on OSKAuth).
    avatarObjectPath: oskAvatarObjectPath,
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
    // initialised Auth instance whose `.currentUser` we read for tokens; `app` is the
    // initialised FirebaseApp, kept so the Storage SDK (OSK-83 avatar upload) can bind
    // to the SAME app — a second initializeApp would not share this app's auth state,
    // so uploads would be unauthenticated and the storage rules would deny them.
    var authMod = null;
    var auth = null;
    var app = null;

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
          app = appMod.initializeApp({
            apiKey: cfg.apiKey,
            authDomain: cfg.authDomain,
            projectId: cfg.projectId,
            storageBucket: cfg.storageBucket,
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

    // OSK-131: GENERIC federated sign-in via popup for any KNOWN + ENABLED provider
    // id ("google" | "facebook" | "apple" | …). This is the single entry point the
    // sign-in UI calls with a button's `data-provider`. It rejects with a clear error
    // when auth isn't configured, the id is unknown, the provider isn't enabled in
    // config, or the SDK failed to load — so the UI can surface a friendly message via
    // OSKAuth.describeAuthError. On success it resolves with the Firebase user. The
    // provider object is built by the pure oskBuildProviderWith (shared with the sim).
    function signInWithProvider(id) {
      return ensureLoaded().then(function () {
        if (!auth || !authMod) { throw new Error("Auth is not configured."); }
        var desc = oskFindProvider(id);
        if (!desc) { throw new Error("Unknown sign-in provider: " + id); }
        if (!oskIsProviderEnabled(cfg, desc.id)) {
          throw new Error(desc.label + " sign-in is not enabled.");
        }
        var provider = oskBuildProviderWith(authMod, desc.id);
        if (!provider) { throw new Error("Unknown sign-in provider: " + id); }
        return authMod
          .signInWithPopup(auth, provider)
          .then(function (cred) { return cred.user; });
      });
    }

    // OSK-131: thin named wrappers (parallel to signInWithGoogle) so callers can be
    // explicit if they prefer. All three converge on the same popup path.
    function signInWithFacebook() { return signInWithProvider("facebook"); }
    function signInWithApple() { return signInWithProvider("apple"); }

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

    // OSK-83: upload an avatar image straight to Cloud Storage under the CALLER's own
    // `users/{uid}/avatar/…` area, then return { objectPath, downloadUrl } for the
    // backend to reflect as the user's photoURL (PUT /api/v1/me/avatar). The bytes go
    // directly to Storage — never through our backend — authorised by this signed-in
    // user's ID token against the committed storage.rules (owner-only write, image +
    // size limited). The Storage SDK is lazily imported from the SAME pinned gstatic
    // build as auth, and bound to THIS app instance so it carries the live auth state.
    // Rejects with a clear error when not configured / signed out, so the caller can
    // surface it. Validation of the file (type/size) is the caller's job before this.
    function uploadAvatar(file) {
      return ensureLoaded().then(function () {
        if (!auth || !authMod || !app) { throw new Error("Auth is not configured."); }
        var user = auth.currentUser;
        if (!user) { throw new Error("You must be signed in to upload an avatar."); }
        var base = "https://www.gstatic.com/firebasejs/" + OSK_FIREBASE_SDK_VERSION + "/";
        return import(base + "firebase-storage.js").then(function (storageMod) {
          var storage = storageMod.getStorage(app);
          var objectPath = oskAvatarObjectPath(user.uid, file, Date.now());
          var storageRef = storageMod.ref(storage, objectPath);
          // Preserve the picked file's content type so it renders correctly AND so the
          // storage.rules contentType check (image/*) passes on the write.
          var metadata = { contentType: (file && file.type) || "application/octet-stream" };
          return storageMod
            .uploadBytes(storageRef, file, metadata)
            .then(function () { return storageMod.getDownloadURL(storageRef); })
            .then(function (downloadUrl) {
              return { objectPath: objectPath, downloadUrl: downloadUrl };
            });
        });
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
      // OSK-131 — additional federated providers. `signInWithProvider(id)` is the
      // generic path the sign-in UI uses; the named wrappers are conveniences.
      signInWithFacebook: signInWithFacebook,
      signInWithApple: signInWithApple,
      signInWithProvider: signInWithProvider,
      signOut: signOut,
      getIdToken: getIdToken,
      // OSK-83 — direct-to-Storage avatar upload (returns { objectPath, downloadUrl }).
      uploadAvatar: uploadAvatar,

      // OSK-131 — provider config helpers, bound to THIS page's firebase config so the
      // sign-in UI can gate + render buttons without re-reading config. Same pure
      // functions the Node sim asserts (see the module.exports above).
      AUTH_PROVIDERS: OSK_AUTH_PROVIDERS,
      enabledProviders: function () { return oskEnabledProviders(cfg); },
      isProviderEnabled: function (id) { return oskIsProviderEnabled(cfg, id); },
      applyProviderVisibility: function (buttonsById) {
        return oskApplyProviderVisibility(cfg, buttonsById);
      },
      describeAuthError: oskDescribeAuthError,

      // Pure guard helpers, re-exposed so the protected page's inline script can render
      // without duplicating the decision logic (same functions the Node sim asserts).
      computeGuardView: oskComputeGuardView,
      applyGuardView: oskApplyGuardView,
    };
  })();
}
