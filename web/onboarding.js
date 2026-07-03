// onboarding.js — OpenSkeleton first-login onboarding wiring (OSK-82).
//
// WHAT: ties the existing onboarding PRODUCT TOUR (web/tour.js, OSK-105) to the
//   SERVER-side onboarding lifecycle flag (OSK-69) so onboarding is recorded per USER,
//   not just per browser. Two jobs:
//
//     1. RECORD COMPLETION — when the tour reaches its end (tour.js fires the
//        `osk:tour:finished` DOM event, an OSK-82 hook), PATCH
//        /api/v1/me/lifecycle with { onboardingCompleted: true } using the caller's
//        Firebase ID token (`Authorization: Bearer <token>`, OSK-74). Errors never block
//        the user: a 401 / network failure / a page with no auth simply leaves a small
//        "pending sync" marker in localStorage and the next auth-capable load retries.
//
//     2. DRIVE AUTO-START FROM THE SERVER FLAG — for a SIGNED-IN user on a page that can
//        reach the backend (OSKAuth present + Firebase configured), auto-start the tour
//        ONCE only when GET /api/v1/me.onboardingCompleted is false; once it is true, never
//        auto-start again (the "?" launcher still replays it on demand). When signed out /
//        auth not configured / no OSKAuth on the page, we DON'T interfere — tour.js's
//        existing localStorage first-run behaviour stands.
//
// HOW IT COMPOSES WITH tour.js (minimal + additive, no restructuring — see the PR body):
//   - tour.js exposes three tiny additive seams for us: it fires `osk:tour:finished` on
//     completion; it SKIPS its own localStorage auto-start when we set
//     `window.__oskSuppressAutoStart = true` synchronously before its boot(); and it
//     exposes an imperative surface on `window.OSKTour` (startTour / markFirstRunDone /
//     isFirstRunDone / loadProgress / currentPageKey / the pure decideOnLoad + STORAGE).
//   - We take ownership of the first-run auto-start ONLY on pages where we can consult the
//     backend (Firebase configured AND window.OSKAuth present — in practice /app). On the
//     other tour pages we never set the suppress flag, so tour.js behaves exactly as before.
//
// COMPOSE ORDER (first-login sequence — documented, not hard-coupled): the intended
//   first-login order is terms acceptance (OSK-78) -> profile completion (OSK-136) ->
//   this onboarding tour. Those gates live in sibling tickets editing the same pages
//   concurrently, so this module stays INDEPENDENT and IDEMPOTENT rather than importing
//   them: it only auto-starts for a signed-in user whose server flag is false, records
//   completion once, and is safe to run repeatedly. When the gates land, onboarding simply
//   runs after them because it keys off the signed-in state they also require.
//
// HOUSE STYLE (mirrors web/auth.js / web/history.js):
//   - Vanilla, no bundler, no framework, no CDN. Loaded as a plain <script src> at the
//     foot of each tour page, right after tour.js.
//   - DUAL-USE: the PURE logic (config check, the PATCH/GET request+classify, the
//     auto-start DECISION, completion recording) is exported for a headless Node
//     simulation (web/e2e/onboarding.simulation.cjs) with a fake DOM + stubbed fetch; the
//     browser bootstrap at the bottom is skipped when `window` is absent, so `require()`ing
//     this file in Node runs NONE of the DOM code. Live sign-in is human-gated behind the
//     Firebase apiKey (OSK-92), so the browser path can't be exercised in CI yet.
//   - GRACEFUL: every network path is try/never-throw; a missing config, blocked storage,
//     absent OSKAuth/OSKTour, or a down backend all degrade quietly.
//
// NO SECRETS: the only network call is to the config-driven apiBaseUrl with a short-lived
//   Firebase ID token minted client-side; nothing sensitive is embedded here.

// ===========================================================================
// PURE, ENVIRONMENT-AGNOSTIC HELPERS
// These take plain data (and injectable fetch/getToken) and return plain data. They
// touch no globals, no window and no Firebase, so they run identically in the browser
// and under `node`/`require` — which is what makes the record/decide behaviour
// unit-simulatable without a browser.
// ===========================================================================

// localStorage keys owned by THIS module. Namespaced + versioned (same convention as
// tour.js's osk.tour.* keys). Kept as a map so the Node simulation asserts the exact same
// name the browser uses.
//   pendingSync — "1" while a tour completion still needs recording server-side (set when
//                 the tour finished on a page that couldn't PATCH — no auth, signed out, or
//                 the PATCH failed; cleared once a PATCH succeeds). The retry-on-next-load
//                 seam the ticket allows.
var OSK_ONBOARDING_STORAGE = {
  pendingSync: "osk.onboarding.pendingsync.v1",
};

// Is a Firebase Web App config usable? "Usable" == a non-empty apiKey — the single value a
// human injects (OSK-92). Mirrors auth.js's oskIsFirebaseConfigured exactly (a 6-line pure
// check, duplicated rather than reaching into auth.js so this module stays independent and
// Node-requireable without loading auth.js). Defensive against a missing/garbage config.
function oskOnbIsConfigured(firebaseCfg) {
  return !!(
    firebaseCfg &&
    typeof firebaseCfg.apiKey === "string" &&
    firebaseCfg.apiKey.trim() !== ""
  );
}

// Normalise the OSK-69 lifecycle field from a GET /api/v1/me body. Treats a missing /
// non-boolean value as NOT completed (false) — the safe default: an unknown flag means
// "onboarding not yet recorded", so a genuinely new user still gets the tour rather than
// being silently skipped.
function oskOnbReadCompleted(meBody) {
  return !!(meBody && meBody.onboardingCompleted === true);
}

// PATCH /api/v1/me/lifecycle with { onboardingCompleted: true } (or a caller-supplied
// body) using the caller's Firebase ID token. Injectable deps keep it browser-free +
// testable (same shape as history.js's oskFetchHistory):
//   opts.getToken   — () => Promise<string|null> (browser: OSKAuth.getIdToken)
//   opts.apiBaseUrl — backend base URL (browser: config.js apiBaseUrl)
//   opts.fetchImpl  — fetch-compatible fn (browser: window.fetch; test: a stub)
//   opts.body       — the lifecycle patch (default { onboardingCompleted: true })
// Classifies the outcome and NEVER throws — any thrown/rejected error (offline, CORS,
// backend down, token error) resolves to { status: "network-error" }. When there is no
// token (signed out / not configured) it resolves to { status: "no-session" } and does
// NOT call fetch.
//   returns { status: "ok"|"no-session"|"unauthorized"|"http-error"|"network-error",
//             httpStatus?: number }
function oskOnbPatchLifecycle(opts) {
  var o = opts || {};
  var getToken = o.getToken;
  var fetchImpl = o.fetchImpl;
  var base = (o.apiBaseUrl || "").replace(/\/+$/, ""); // trim trailing slashes -> no "//api"
  var body = o.body || { onboardingCompleted: true };
  return Promise.resolve()
    .then(function () {
      return typeof getToken === "function" ? getToken() : null;
    })
    .then(function (token) {
      if (!token) {
        return { status: "no-session" };
      }
      return fetchImpl(base + "/api/v1/me/lifecycle", {
        method: "PATCH",
        headers: {
          Authorization: "Bearer " + token,
          "Content-Type": "application/json",
          Accept: "application/json",
        },
        body: JSON.stringify(body),
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

// GET /api/v1/me and read the OSK-69 onboardingCompleted flag. Same injectable deps +
// never-throw contract as oskOnbPatchLifecycle.
//   returns { status: "ok"|"no-session"|"unauthorized"|"http-error"|"network-error",
//             onboardingCompleted?: boolean, httpStatus?: number }
// On any non-ok outcome onboardingCompleted is omitted (the caller treats "server unknown"
// as a best-effort local fallback rather than assuming either value).
function oskOnbFetchState(opts) {
  var o = opts || {};
  var getToken = o.getToken;
  var fetchImpl = o.fetchImpl;
  var base = (o.apiBaseUrl || "").replace(/\/+$/, "");
  return Promise.resolve()
    .then(function () {
      return typeof getToken === "function" ? getToken() : null;
    })
    .then(function (token) {
      if (!token) {
        return { status: "no-session" };
      }
      return fetchImpl(base + "/api/v1/me", {
        headers: {
          Authorization: "Bearer " + token,
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
        return r.json().then(function (body) {
          return { status: "ok", onboardingCompleted: oskOnbReadCompleted(body) };
        });
      });
    })
    .catch(function () {
      return { status: "network-error" };
    });
}

// THE on-load decision (pure). Given whether this module OWNS the auto-start on this page,
// the signed-in state, what the backend said, whether a completion is pending, and what
// tour.js's OWN pure decideOnLoad would do from localStorage (its "resume"/"autostart"/
// "idle" action), decide what onboarding.js should do. Centralising it here makes every
// branch trivially assertable in the Node simulation.
//
//   in  = {
//     owns:               boolean, // this page can consult the backend (configured + OSKAuth)
//     signedIn:           boolean, // a Firebase session/token is available
//     serverKnown:        boolean, // GET /me succeeded (so serverCompleted is trustworthy)
//     serverCompleted:    boolean, // GET /me.onboardingCompleted (only when serverKnown)
//     pendingCompletion:  boolean, // a prior completion still needs recording (pending marker)
//     tourAction:         "resume"|"autostart"|"idle", // tour.js's OWN localStorage decision
//   }
//   out = {
//     mode:            string,  // a label for logging/tests
//     autoStart:       boolean, // call OSKTour.startTour(pageKey, true) now
//     recordCompleted: boolean, // PATCH { onboardingCompleted: true } now (flush the pending sync)
//     markLocalDone:   boolean, // set tour.js's first-run flag so a later SIGN-OUT won't re-nag
//   }
function oskOnbComputePlan(input) {
  var i = input || {};
  var idle = { autoStart: false, recordCompleted: false, markLocalDone: false };

  // Not our page to drive — tour.js keeps its normal localStorage behaviour.
  if (!i.owns) {
    return assign({ mode: "passive" }, idle);
  }

  // Signed out (or no token): fall back to tour.js's OWN localStorage auto-start decision.
  // We suppressed tour.js's auto-start to own it, so we must replicate it here — a
  // signed-out first-run visitor still gets the tour once, exactly as before.
  if (!i.signedIn) {
    return assign({ mode: "signed-out-fallback" }, idle, {
      autoStart: i.tourAction === "autostart",
    });
  }

  // Signed in, but GET /me failed (offline / 401 / backend down): don't gamble on the
  // server flag. Best-effort local fallback so the user isn't worse off than without this
  // module; the completion path will retry-on-next-load if it ever finishes.
  if (!i.serverKnown) {
    return assign({ mode: "server-unknown-fallback" }, idle, {
      autoStart: i.tourAction === "autostart",
    });
  }

  // A completion is queued (finished on a page that couldn't PATCH): flush it now, and
  // mark local done so a subsequent sign-out doesn't re-nag. Never auto-start — they just
  // finished it.
  if (i.pendingCompletion) {
    return assign({ mode: "sync-pending" }, idle, {
      recordCompleted: true,
      markLocalDone: true,
    });
  }

  // Server says onboarding is DONE: never auto-start. Also set the local first-run flag so
  // if this user later signs out, tour.js won't nag them on the same browser.
  if (i.serverCompleted) {
    return assign({ mode: "already-onboarded" }, idle, { markLocalDone: true });
  }

  // Server says NOT done. Auto-start ONCE — but only if tour.js's own localStorage
  // decision was "autostart" (a brand-new first run). If it was "resume" (a cross-page
  // walkthrough already in flight, which tour.js handles itself) or "idle" (paused here, or
  // the tour's already run once on this browser), we leave it be rather than re-nag.
  if (i.tourAction === "autostart") {
    return assign({ mode: "autostart" }, idle, { autoStart: true, markLocalDone: true });
  }
  return assign({ mode: i.tourAction === "resume" ? "resuming" : "idle" }, idle);
}

// Record a tour completion. Pure-ish: it performs the network via injected deps but touches
// no storage — it returns an ACTION the caller reflects onto the pending marker. This is
// the single place "the tour finished -> tell the backend" lives, so the simulation can
// assert "completing the tour PATCHes { onboardingCompleted: true } + Bearer" directly.
//   in  = { configured, hasAuth, getToken, apiBaseUrl, fetchImpl }
//     configured — Firebase apiKey present (else there's no backend/user to record against)
//     hasAuth    — window.OSKAuth is on THIS page (else we can't get a token here)
//   out = Promise<{ patched: boolean, pending: boolean, skipped: boolean, status?: string }>
//     patched — a PATCH succeeded; caller CLEARS the pending marker
//     pending — couldn't record now (no auth here / signed out / 401 / error); caller SETS
//               the pending marker so the next auth-capable, signed-in load retries
//     skipped — not configured; nothing to do (no server to record against)
function oskOnbRecordCompletion(input) {
  var i = input || {};
  // Not configured -> there is no backend/user; tour.js already set the local first-run
  // flag, so there's simply nothing to sync.
  if (!i.configured) {
    return Promise.resolve({ patched: false, pending: false, skipped: true });
  }
  // No auth on this page (e.g. the walkthrough finished on /help, which has no OSKAuth):
  // queue it for the next page that can reach the backend.
  if (!i.hasAuth) {
    return Promise.resolve({ patched: false, pending: true, skipped: false });
  }
  return oskOnbPatchLifecycle({
    getToken: i.getToken,
    apiBaseUrl: i.apiBaseUrl,
    fetchImpl: i.fetchImpl,
  }).then(function (res) {
    if (res.status === "ok") {
      return { patched: true, pending: false, skipped: false, status: res.status };
    }
    // no-session (signed out) / unauthorized (401) / http-error / network-error: keep it
    // queued and retry on the next load. The user is never blocked.
    return { patched: false, pending: true, skipped: false, status: res.status };
  });
}

// Tiny Object.assign shim (kept local so the module has zero deps and runs on old engines).
function assign(target) {
  for (var a = 1; a < arguments.length; a++) {
    var src = arguments[a];
    if (!src) { continue; }
    for (var k in src) {
      if (Object.prototype.hasOwnProperty.call(src, k)) { target[k] = src[k]; }
    }
  }
  return target;
}

// ---------------------------------------------------------------------------
// Node export. Exposed only under CommonJS (`typeof module` is "undefined" in a classic
// browser <script>, so this is a no-op there). Lets web/e2e/onboarding.simulation.cjs
// assert the SAME code the page runs.
// ---------------------------------------------------------------------------
if (typeof module !== "undefined" && module.exports) {
  module.exports = {
    STORAGE: OSK_ONBOARDING_STORAGE,
    isConfigured: oskOnbIsConfigured,
    readCompleted: oskOnbReadCompleted,
    patchLifecycle: oskOnbPatchLifecycle,
    fetchState: oskOnbFetchState,
    computePlan: oskOnbComputePlan,
    recordCompletion: oskOnbRecordCompletion,
  };
}

// ===========================================================================
// BROWSER BOOTSTRAP
// Runs ONLY in a real browser (guarded by `typeof window`/`document`). Loaded at the FOOT
// of each tour page, right after tour.js. Wires the two jobs above onto the live tour +
// OSKAuth. Every step is guarded so a missing dependency degrades quietly.
// ===========================================================================
if (typeof window !== "undefined" && typeof document !== "undefined") {
  (function () {
    "use strict";

    var cfg = window.__APP_CONFIG__ || {};
    var firebaseCfg = cfg.firebase || {};
    var configured = oskOnbIsConfigured(firebaseCfg);
    var A = window.OSKAuth; // present only on pages that load auth.js (/app, /history, /admin)

    // We OWN the first-run auto-start only where we can actually consult the backend:
    // Firebase configured AND OSKAuth on this page. Everywhere else tour.js keeps its own
    // localStorage behaviour untouched.
    var owns = configured && !!A;

    // SIGNAL OWNERSHIP SYNCHRONOUSLY, before tour.js's boot() runs (both are parser-blocking
    // <script>s, so this top-level assignment lands before the DOMContentLoaded that fires
    // boot()). This tells tour.js to skip its localStorage auto-start so we can drive it
    // from the server flag without a flash of the tour appearing then being suppressed.
    if (owns) {
      try { window.__oskSuppressAutoStart = true; } catch (e) { /* ignore */ }
    }

    // --- tiny storage wrappers (never throw if localStorage is blocked) --------
    function lsGet(key) {
      try { return window.localStorage.getItem(key); } catch (e) { return null; }
    }
    function lsSet(key, val) {
      try { window.localStorage.setItem(key, val); } catch (e) { /* ignore */ }
    }
    function lsRemove(key) {
      try { window.localStorage.removeItem(key); } catch (e) { /* ignore */ }
    }
    function isPending() { return lsGet(OSK_ONBOARDING_STORAGE.pendingSync) === "1"; }
    function setPending() { lsSet(OSK_ONBOARDING_STORAGE.pendingSync, "1"); }
    function clearPending() { lsRemove(OSK_ONBOARDING_STORAGE.pendingSync); }

    var fetchImpl = window.fetch ? window.fetch.bind(window) : null;
    function getToken() { return A && A.getIdToken ? A.getIdToken() : Promise.resolve(null); }
    var Tour = window.OSKTour || null;

    // Are we an automated browser (Playwright/WebDriver)? Onboarding is for humans; auto-
    // popping a full-screen backdrop over an automated session fights the e2e specs. Mirrors
    // tour.js's own webdriver guard.
    var automated = false;
    try { automated = navigator.webdriver === true; } catch (e) { /* assume human */ }

    // Small, quiet logger (best-effort telemetry; never noisy).
    function log(msg) {
      try { if (window.console && window.console.debug) { window.console.debug("[onboarding] " + msg); } }
      catch (e) { /* ignore */ }
    }

    // Mark tour.js's first-run flag done (reconcile local with the server so a later sign-out
    // on the same browser doesn't re-nag). Prefer the exposed helper; fall back to writing the
    // key directly if the imperative surface isn't there.
    function markLocalDone() {
      if (Tour && typeof Tour.markFirstRunDone === "function") { Tour.markFirstRunDone(); return; }
      if (Tour && Tour.STORAGE && Tour.STORAGE.firstRun) { lsSet(Tour.STORAGE.firstRun, "1"); }
    }

    // Compute tour.js's OWN localStorage decision for THIS page, reusing its exported pure
    // helpers so we never duplicate its logic. Returns "resume" | "autostart" | "idle".
    function tourActionFor(pageKey) {
      if (!Tour || typeof Tour.decideOnLoad !== "function") { return "idle"; }
      var hasSteps = !!(Tour.TOURS && Tour.TOURS[pageKey] && Tour.TOURS[pageKey].length);
      var progress = null;
      try {
        progress = Tour.loadProgress ? Tour.loadProgress()
          : (Tour.parseProgress && Tour.STORAGE ? Tour.parseProgress(lsGet(Tour.STORAGE.progress)) : null);
      } catch (e) { progress = null; }
      var firstRunDone = false;
      try { firstRunDone = Tour.isFirstRunDone ? Tour.isFirstRunDone()
        : (Tour.STORAGE && lsGet(Tour.STORAGE.firstRun) === "1"); } catch (e) { /* ignore */ }
      return Tour.decideOnLoad({
        progress: progress,
        firstRunDone: firstRunDone,
        pageKey: pageKey,
        hasSteps: hasSteps,
        config: Tour.tourConfig ? Tour.tourConfig(window.__APP_CONFIG__) : { enabled: true, autoStartFirstRun: true },
      }).action;
    }

    function currentPageKey() {
      if (Tour && typeof Tour.currentPageKey === "function") { return Tour.currentPageKey(); }
      if (Tour && typeof Tour.normalizePageKey === "function") {
        return Tour.normalizePageKey(window.location.pathname);
      }
      return null;
    }

    // --- JOB 1: record completion when the tour finishes -----------------------
    // tour.js fires this on `window` when the tour reaches its end (any page). We record it
    // server-side if we can here, else queue it for the next auth-capable load. Guarded so a
    // failure never breaks anything.
    window.addEventListener("osk:tour:finished", function () {
      oskOnbRecordCompletion({
        configured: configured,
        hasAuth: !!A,
        getToken: getToken,
        apiBaseUrl: cfg.apiBaseUrl,
        fetchImpl: fetchImpl,
      }).then(function (res) {
        if (res.patched) { clearPending(); log("completion recorded server-side"); }
        else if (res.pending) { setPending(); log("completion queued for next load"); }
        // res.skipped (not configured): nothing to do.
      }).catch(function () { /* never let recording break the page */ });
    });

    // --- JOB 2: drive auto-start from the server flag (only where we own it) ----
    if (!owns) {
      // Nothing else to do here: tour.js runs its normal localStorage auto-start, and Job 1
      // above will still queue any completion that happens on this page.
      return;
    }

    // We own auto-start on this page. Wait for auth to settle, then run the plan. We use
    // A.ready so we act on the settled signed-in/out state (not a transient one). Any error
    // path still runs the plan (which falls back to the local decision).
    function run() {
      // Resolve the signed-in state via a fresh token (null == signed out / not configured).
      getToken().then(function (token) {
        var signedIn = !!token;
        if (!signedIn) {
          runPlan({ signedIn: false, serverKnown: false, serverCompleted: false });
          return;
        }
        // Signed in: ask the backend for the onboarding flag.
        oskOnbFetchState({
          getToken: getToken,
          apiBaseUrl: cfg.apiBaseUrl,
          fetchImpl: fetchImpl,
        }).then(function (state) {
          var serverKnown = state.status === "ok";
          runPlan({
            signedIn: true,
            serverKnown: serverKnown,
            serverCompleted: serverKnown ? state.onboardingCompleted : false,
          });
        });
      }).catch(function () {
        // Token/auth error: fall back to the local decision (as if signed out).
        runPlan({ signedIn: false, serverKnown: false, serverCompleted: false });
      });
    }

    function runPlan(serverState) {
      var pageKey = currentPageKey();
      var plan = oskOnbComputePlan({
        owns: true,
        signedIn: serverState.signedIn,
        serverKnown: serverState.serverKnown,
        serverCompleted: serverState.serverCompleted,
        pendingCompletion: isPending(),
        tourAction: tourActionFor(pageKey),
      });
      log("plan=" + plan.mode);

      // Flush a queued completion (finished earlier on a page with no auth).
      if (plan.recordCompleted) {
        oskOnbPatchLifecycle({
          getToken: getToken,
          apiBaseUrl: cfg.apiBaseUrl,
          fetchImpl: fetchImpl,
        }).then(function (res) {
          if (res.status === "ok") { clearPending(); log("pending completion flushed"); }
          // else keep the marker and retry next load.
        }).catch(function () { /* ignore */ });
      }

      // Reconcile the local first-run flag with the server (so a later sign-out won't re-nag).
      if (plan.markLocalDone) { markLocalDone(); }

      // Auto-start once — never for an automated browser (mirrors tour.js's own guard).
      if (plan.autoStart && !automated) {
        if (Tour && typeof Tour.startTour === "function") {
          markLocalDone(); // record the one-time first-run immediately, like tour.js does
          Tour.startTour(pageKey, true);
          log("auto-started the tour from the server flag");
        }
      }
    }

    if (A && A.ready && typeof A.ready.then === "function") {
      A.ready.then(run).catch(run);
    } else {
      run();
    }
  })();
}
