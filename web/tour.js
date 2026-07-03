// tour.js — OpenSkeleton onboarding PRODUCT TOUR (OSK-105).
//
// WHAT: a self-contained, dependency-free onboarding *product tour* (a.k.a. guided
// tour / onboarding walkthrough / first-run experience) for the static web pages.
// A tour is a sequence of *coachmarks* (a.k.a. hotspots / callouts / in-app
// tooltips): each coachmark is a small bubble with an arrow pointing at one UI
// element, shown over a *spotlight / backdrop* — the dimmed overlay with a cut-out
// that highlights just that element so the eye goes to one thing at a time.
//
// It delivers three things the ticket asks for:
//   1. PER-PAGE COACHMARKS  — each page has its own ordered list of steps (target
//      selector + title + body + placement), declared in the TOURS registry below.
//   2. WHOLE-SITE WALKTHROUGH — the per-page tours chain together: the last step of
//      a page offers "Continue on <next> ->", which persists progress in
//      localStorage and navigates on; the next page auto-resumes. So one tour can
//      span the whole site (index -> app -> status -> ops -> help).
//   3. PAUSABLE & REPLAYABLE — Esc / Skip / the close button PAUSE the tour
//      (position persisted); an always-present "?" launcher RE-launches it any time.
//      A brand-new visitor is auto-started ONCE (guarded by a localStorage flag) and
//      is never nagged again after that.
//
// HOW IT FITS THE HOUSE STYLE (mirrors auth.js / OSK-151 alert banner):
//   - Vanilla, no bundler, no framework, NO external/CDN request — the pages make no
//     third-party requests by contract (Firebase is lazy-loaded only when configured),
//     and this tour must keep working offline, so it is HAND-ROLLED rather than
//     pulling in Driver.js/Shepherd.js from a CDN. See the PR body for the rationale.
//   - MINIMAL page footprint: a page opts in with exactly ONE line —
//     `<script src="/tour.js"></script>`. This module injects its OWN <style> block
//     (built from palette tokens, so it re-skins with the theme incl. sketch) and its
//     OWN "?" launcher button, so no page HTML has to change beyond, at most, adding
//     an `id` to a couple of otherwise-unselectable elements.
//   - CONFIG-DRIVEN + graceful: reads the optional `window.__APP_CONFIG__.tour` block
//     but needs nothing from it; every missing target / storage-blocked / no-config
//     case degrades quietly instead of throwing.
//   - DUAL-USE like auth.js: the PURE logic (page-key normalisation, step filtering,
//     the step/pause/resume/cross-page state machine, persistence (de)serialisation)
//     is exported for a headless Node simulation (web/e2e/tour.simulation.cjs). The
//     browser render layer only runs when `window`/`document` exist, so `require()`ing
//     this file in Node executes NONE of the DOM code.
//   - XSS-safe: every dynamic string (titles/bodies) is written with textContent only.
//   - Accessible: the coachmark is a role=dialog, keyboard-driven (Esc exits,
//     Left/Right/Enter navigate), focus is moved into it and restored on exit, and the
//     backdrop does NOT trap the user (Esc always escapes; focus is not hard-locked).
//   - Respects `prefers-reduced-motion` (no smooth scroll / no transitions when set).

(function (globalRoot) {
  "use strict";

  // ===========================================================================
  // SECTION 1 — Pure constants & the per-page step registry (the "data structure")
  // ===========================================================================

  // localStorage keys. Namespaced + versioned so a future format change is a clean
  // key bump rather than a migration. Kept as a map so the Node simulation can assert
  // against the exact same names the browser uses.
  var STORAGE = {
    // JSON blob of the in-flight tour position: {active,paused,page,step,walkthrough}.
    progress: "osk.tour.v1",
    // "1" once the one-time first-run auto-start has fired (so we never nag again).
    firstRun: "osk.tour.firstrun.v1",
  };

  // The ORDER pages are visited in when the tour runs as a whole-site walkthrough.
  // The "Continue ->" button walks this list; pages missing from a build are skipped.
  var WALK_ORDER = ["index", "app", "status", "ops", "help"];

  // Map a page key to the CLEAN path used to navigate to it (matches the footer
  // links + the Firebase Hosting / static-server rewrites). Used for cross-page hops.
  var PAGE_PATH = {
    index: "/",
    app: "/app",
    status: "/status",
    ops: "/ops",
    help: "/help",
  };

  // The per-page coachmark steps. Each step is:
  //   { target, title, body, placement }
  // - target:    a CSS selector for the element to spotlight, or null for a centred
  //              "no-target" step (a plain welcome/finish card with no arrow).
  // - title/body: shown in the coachmark bubble (textContent only — safe).
  // - placement: preferred side of the target ("top" | "bottom" | "left" | "right" |
  //              "auto"). On a phone-width viewport placement is ignored and the
  //              bubble docks to the bottom of the screen (see the render layer).
  //
  // Steps whose target is absent or hidden at run time are filtered out gracefully
  // (see filterVisibleSteps) — e.g. app.html's "#auth-app" step only appears once the
  // user is signed in; for a signed-out first-run visitor it simply isn't shown.
  var TOURS = {
    index: [
      {
        target: ".logo",
        title: "Welcome to OpenSkeleton",
        body: "This is the home of your OpenSkeleton deployment. Here's a 30-second tour of the site.",
        placement: "bottom",
      },
      {
        target: ".status-link",
        title: "Explore the site",
        body: "Jump to the live Status page, the Operations dashboard, or Help from these links.",
        placement: "top",
      },
      {
        target: ".get-app",
        title: "Get the app",
        body: "Download links for the OpenSkeleton mobile app will appear here.",
        placement: "top",
      },
      {
        target: "#theme-toggle",
        title: "Switch themes",
        body: "Prefer a hand-drawn look? Toggle the sketch theme any time — your choice is remembered.",
        placement: "top",
      },
    ],
    app: [
      {
        target: "header.head",
        title: "Your account area",
        body: "This is your signed-in area, protected by Firebase Authentication. Let's walk through it.",
        placement: "bottom",
      },
      {
        target: "#auth-signin",
        title: "Sign in",
        body: "Enter your email and password here to access your account.",
        placement: "bottom",
      },
      {
        target: "#google-btn",
        title: "…or use Google",
        body: "Prefer one click? Sign in with your Google account instead.",
        placement: "top",
      },
      {
        // Only present once signed in — filtered out for a signed-out visitor, which
        // exercises the "absent/hidden target handled gracefully" path.
        target: "#auth-app",
        title: "Your profile",
        body: "Once you're signed in, your profile loads here straight from the backend.",
        placement: "bottom",
      },
      {
        target: "#theme-toggle",
        title: "Switch themes",
        body: "Toggle between the default and the hand-drawn sketch theme whenever you like.",
        placement: "top",
      },
      {
        target: 'a[href="/help"]',
        title: "Need a hand?",
        body: "The Help page has guides on signing in and managing your profile.",
        placement: "top",
      },
    ],
    status: [
      {
        target: "header.head",
        title: "Service status",
        body: "A live, at-a-glance view of whether OpenSkeleton is healthy.",
        placement: "bottom",
      },
      {
        target: "#banner",
        title: "Overall status",
        body: "The headline indicator — all systems operational, degraded, or a service outage.",
        placement: "bottom",
      },
      {
        target: "#components",
        title: "Component breakdown",
        body: "The health of each part on its own: the web front-end and the backend API.",
        placement: "top",
      },
      {
        target: "#theme-toggle",
        title: "Switch themes",
        body: "Toggle the sketch theme here — it applies across the whole site.",
        placement: "top",
      },
    ],
    ops: [
      {
        target: "header.head",
        title: "Operations",
        body: "Operational tools and quick links for running OpenSkeleton.",
        placement: "bottom",
      },
      {
        target: "#health",
        title: "Backend health",
        body: "A live probe of the backend service, refreshed automatically.",
        placement: "bottom",
      },
      {
        target: "#ops-backend-links",
        title: "Backend endpoints",
        body: "Quick links to the backend's health, version and actuator endpoints.",
        placement: "top",
      },
      {
        target: "#theme-toggle",
        title: "Switch themes",
        body: "Toggle the sketch theme here — it applies across the whole site.",
        placement: "top",
      },
    ],
    help: [
      {
        target: "header.head",
        title: "Help & guides",
        body: "Everything you need to get started with OpenSkeleton lives here.",
        placement: "bottom",
      },
      {
        target: "#help-signing-in",
        title: "Signing in",
        body: "The different ways you can sign in to your OpenSkeleton account.",
        placement: "bottom",
      },
      {
        target: "#help-support",
        title: "Get support",
        body: "Stuck on something? Contact support right from here.",
        placement: "top",
      },
      {
        target: "#theme-toggle",
        title: "Switch themes",
        body: "That's the tour! Toggle the sketch theme any time from here.",
        placement: "top",
      },
    ],
  };

  // ===========================================================================
  // SECTION 2 — Pure helpers (no DOM, no window). Exported for the Node simulation.
  // ===========================================================================

  // Read the optional config block; supply safe defaults so the tour works with an
  // empty/absent config.js `tour` key. `enabled` is the master switch; when false the
  // tour never shows and no launcher is injected. `autoStartFirstRun` gates the
  // one-time auto-start for brand-new visitors.
  function tourConfig(appConfig) {
    var t = (appConfig && appConfig.tour) || {};
    return {
      enabled: t.enabled !== false, // default ON
      autoStartFirstRun: t.autoStartFirstRun !== false, // default ON
    };
  }

  // Map a URL pathname to a page key in TOURS. Handles the clean paths ("/status")
  // AND the raw ".html" paths ("/status.html"), plus "/" and "/index.html" -> "index".
  // Returns null for any path we don't have a tour for.
  function normalizePageKey(pathname) {
    var p = String(pathname || "");
    // Strip a trailing "index.html" and any ".html" suffix, then trim slashes.
    p = p.replace(/index\.html?$/i, "").replace(/\.html?$/i, "");
    p = p.replace(/^\/+|\/+$/g, ""); // trim leading/trailing slashes
    if (p === "") { return "index"; }
    // Keep only the last path segment (defends against a base path prefix).
    var seg = p.split("/").pop();
    return Object.prototype.hasOwnProperty.call(TOURS, seg) ? seg : null;
  }

  // The next page (by key) in the walkthrough that actually has a tour, or null if
  // this is the last one. Lets us skip a page that a given build doesn't ship.
  function nextWalkPage(pageKey) {
    var i = WALK_ORDER.indexOf(pageKey);
    if (i === -1) { return null; }
    for (var j = i + 1; j < WALK_ORDER.length; j++) {
      var key = WALK_ORDER[j];
      if (TOURS[key] && TOURS[key].length) { return key; }
    }
    return null;
  }

  // Keep only the steps whose target is present + visible RIGHT NOW. `isVisible` is a
  // predicate `(target) => bool`; a null/empty target (a centred card) is always kept.
  // This is how absent/hidden targets are "handled gracefully": they just drop out of
  // the sequence instead of pointing the arrow at nothing.
  function filterVisibleSteps(steps, isVisible) {
    var out = [];
    for (var i = 0; i < steps.length; i++) {
      var s = steps[i];
      if (!s.target || isVisible(s.target)) { out.push(s); }
    }
    return out;
  }

  // Human progress label for the coachmark, e.g. "2 of 5" (1-based).
  function progressLabel(index, total) {
    return (index + 1) + " of " + total;
  }

  // ---- Persistence (de)serialisation -------------------------------------------

  // Parse the stored progress blob into a normalised object, or null if absent/bad.
  // Defensive: any malformed JSON or wrong shape resolves to null (start fresh).
  function parseProgress(raw) {
    if (!raw) { return null; }
    var obj;
    try { obj = JSON.parse(raw); } catch (e) { return null; }
    if (!obj || typeof obj !== "object") { return null; }
    if (typeof obj.page !== "string") { return null; }
    return {
      active: obj.active === true,
      paused: obj.paused === true,
      walkthrough: obj.walkthrough === true,
      page: obj.page,
      step: typeof obj.step === "number" && obj.step >= 0 ? obj.step : 0,
    };
  }

  function serializeProgress(p) {
    return JSON.stringify({
      active: p.active === true,
      paused: p.paused === true,
      walkthrough: p.walkthrough === true,
      page: p.page,
      step: typeof p.step === "number" && p.step >= 0 ? p.step : 0,
    });
  }

  // ---- The state machine (pure) ------------------------------------------------

  // Fresh progress for starting a tour on `pageKey` at step 0.
  function startProgress(pageKey, walkthrough) {
    return {
      active: true,
      paused: false,
      walkthrough: walkthrough === true,
      page: pageKey,
      step: 0,
    };
  }

  // Given the current progress + this page's visible-step count, decide what happens
  // when the user hits "Next" on the current step. Returns a COMMAND, one of:
  //   { type: "step", step }             -> advance within the page
  //   { type: "page", page, path }       -> hop to the next walkthrough page
  //   { type: "finish" }                 -> the tour is complete
  function nextCommand(progress, total) {
    if (progress.step + 1 < total) {
      return { type: "step", step: progress.step + 1 };
    }
    if (progress.walkthrough) {
      var next = nextWalkPage(progress.page);
      if (next) { return { type: "page", page: next, path: PAGE_PATH[next] }; }
    }
    return { type: "finish" };
  }

  // Index to move to on "Back" (clamped at 0 — we don't walk back across pages).
  function backIndex(progress) {
    return progress.step > 0 ? progress.step - 1 : 0;
  }

  // Mark the current position as paused (keeps page+step so it can be resumed).
  function pauseProgress(progress) {
    return {
      active: true,
      paused: true,
      walkthrough: progress.walkthrough === true,
      page: progress.page,
      step: progress.step,
    };
  }

  // Decide what to do when a page loads, from the stored state. Returns:
  //   { action: "resume"|"autostart"|"idle", step, resumable }
  // - "resume":    a live (unpaused) walkthrough expects THIS page -> show `step` now.
  // - "autostart": brand-new visitor + auto-start enabled -> begin at step 0.
  // - "idle":      do nothing on load. `resumable` is true when a PAUSED tour is
  //                parked on this page, so the launcher can say "Resume" instead of
  //                "Take the tour".
  function decideOnLoad(opts) {
    var progress = opts.progress;
    var firstRunDone = opts.firstRunDone === true;
    var pageKey = opts.pageKey;
    var hasSteps = opts.hasSteps === true;
    var config = opts.config || { enabled: true, autoStartFirstRun: true };

    if (!hasSteps || !config.enabled) {
      return { action: "idle", resumable: false, step: 0 };
    }
    if (progress && progress.active && progress.page === pageKey) {
      if (!progress.paused) {
        return { action: "resume", resumable: false, step: progress.step };
      }
      // Paused on this very page — wait for the user to click the launcher.
      return { action: "idle", resumable: true, step: progress.step };
    }
    if (!firstRunDone && config.autoStartFirstRun) {
      return { action: "autostart", resumable: false, step: 0 };
    }
    return { action: "idle", resumable: false, step: 0 };
  }

  // The pure surface, gathered once so both the Node export and window.OSKTour share it.
  var pureApi = {
    STORAGE: STORAGE,
    WALK_ORDER: WALK_ORDER,
    PAGE_PATH: PAGE_PATH,
    TOURS: TOURS,
    tourConfig: tourConfig,
    normalizePageKey: normalizePageKey,
    nextWalkPage: nextWalkPage,
    filterVisibleSteps: filterVisibleSteps,
    progressLabel: progressLabel,
    parseProgress: parseProgress,
    serializeProgress: serializeProgress,
    startProgress: startProgress,
    nextCommand: nextCommand,
    backIndex: backIndex,
    pauseProgress: pauseProgress,
    decideOnLoad: decideOnLoad,
  };

  // Export the pure surface to Node (for the simulation) the moment we can — this runs
  // in BOTH Node and the browser. The browser render layer is added below, guarded.
  if (typeof module !== "undefined" && module.exports) {
    module.exports = pureApi;
  }

  // ===========================================================================
  // SECTION 3 — Browser render layer. Only runs in a real DOM; skipped under Node.
  // ===========================================================================

  // Bail out entirely when there is no DOM (i.e. when required from Node). Everything
  // below this line touches window/document, so guarding here keeps the simulation
  // 100% pure — requiring this file executes none of it.
  if (typeof window === "undefined" || typeof document === "undefined") {
    return;
  }

  // Expose the pure API on the page too, so inline scripts / tests can reach it.
  window.OSKTour = pureApi;

  // Guard against a double include (two <script> tags) — only wire up once.
  if (window.__oskTourBooted) { return; }
  window.__oskTourBooted = true;

  // --- Tiny storage wrappers: never throw if localStorage is blocked (Safari private
  // mode, cookies-off, etc.). The tour still runs for the session; it just can't
  // persist across reloads. Mirrors the alert banner's storage handling. -----------
  function lsGet(key) {
    try { return window.localStorage.getItem(key); } catch (e) { return null; }
  }
  function lsSet(key, val) {
    try { window.localStorage.setItem(key, val); } catch (e) { /* ignore */ }
  }
  function lsRemove(key) {
    try { window.localStorage.removeItem(key); } catch (e) { /* ignore */ }
  }

  function loadProgress() { return parseProgress(lsGet(STORAGE.progress)); }
  function saveProgress(p) { lsSet(STORAGE.progress, serializeProgress(p)); }
  function clearProgress() { lsRemove(STORAGE.progress); }
  function isFirstRunDone() { return lsGet(STORAGE.firstRun) === "1"; }
  function markFirstRunDone() { lsSet(STORAGE.firstRun, "1"); }

  // Does the honest, live visibility check the pure layer expects: the element exists
  // AND actually renders a box (so `hidden` / `display:none` sections drop out).
  function isVisibleSelector(target) {
    if (!target) { return true; }
    var el;
    try { el = document.querySelector(target); } catch (e) { return false; }
    if (!el) { return false; }
    return !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length);
  }

  var reducedMotion = false;
  try {
    reducedMotion = window.matchMedia &&
      window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  } catch (e) { /* matchMedia unavailable — assume motion is fine */ }

  // --- Inject the stylesheet ONCE. Every colour is a shared palette token so the tour
  // re-skins automatically with the theme, including the sketch theme. -------------
  var STYLE_ID = "osk-tour-styles";
  function injectStyles() {
    if (document.getElementById(STYLE_ID)) { return; }
    var css = [
      /* The full-screen catcher. Transparent by default — the DIM is drawn by the
         spotlight's huge box-shadow so the highlighted element stays bright. It sits
         above the page to swallow background clicks (the page can't be misclicked mid
         tour) but Esc / Skip always escape, so the user is never trapped. */
      ".osk-tour-backdrop{position:fixed;inset:0;z-index:2147483000;background:transparent;}",
      /* For a centred no-target step there is no spotlight, so the backdrop dims itself. */
      ".osk-tour-backdrop.osk-tour-backdrop--dim{background:rgba(1,4,9,0.55);}",
      /* The spotlight: a transparent box over the target whose enormous box-shadow
         dims the rest of the screen, plus an accent ring. pointer-events:none so it
         never blocks anything underneath. */
      ".osk-tour-spot{position:fixed;z-index:2147483001;border-radius:10px;" +
        "box-shadow:0 0 0 9999px rgba(1,4,9,0.55),0 0 0 2px var(--accent,#4c8bf5);" +
        "pointer-events:none;transition:all .25s ease;}",
      ".osk-tour-spot--flat{box-shadow:0 0 0 9999px rgba(1,4,9,0.55);}",
      /* The coachmark bubble: a themed panel. role=dialog. */
      ".osk-tour-bubble{position:fixed;z-index:2147483002;max-width:22rem;width:calc(100vw - 2rem);" +
        "box-sizing:border-box;background:var(--panel,#161b22);color:var(--fg,#e6edf3);" +
        "border:1px solid var(--border,#30363d);border-radius:12px;padding:1rem 1.1rem 0.9rem;" +
        "box-shadow:0 8px 28px rgba(1,4,9,0.55);font-family:-apple-system,BlinkMacSystemFont," +
        "'Segoe UI',Roboto,Helvetica,Arial,sans-serif;line-height:1.5;transition:top .2s ease,left .2s ease;}",
      /* The little arrow that points from the bubble at the target. It's a rotated
         square sharing the bubble's fill + border; per-side classes place it. */
      ".osk-tour-arrow{position:absolute;width:12px;height:12px;background:var(--panel,#161b22);" +
        "border:1px solid var(--border,#30363d);transform:rotate(45deg);}",
      ".osk-tour-arrow--top{bottom:-7px;border-top:0;border-left:0;}",
      ".osk-tour-arrow--bottom{top:-7px;border-bottom:0;border-right:0;}",
      ".osk-tour-arrow--left{right:-7px;border-left:0;border-bottom:0;}",
      ".osk-tour-arrow--right{left:-7px;border-right:0;border-top:0;}",
      ".osk-tour-arrow--hidden{display:none;}",
      ".osk-tour-progress{font-size:0.72rem;letter-spacing:0.06em;text-transform:uppercase;" +
        "color:var(--muted,#8b949e);margin:0 0 0.35rem;}",
      ".osk-tour-title{font-size:1.02rem;margin:0 0 0.3rem;letter-spacing:-0.01em;}",
      ".osk-tour-body{font-size:0.9rem;margin:0 0 0.9rem;color:var(--fg,#e6edf3);}",
      ".osk-tour-controls{display:flex;align-items:center;gap:0.5rem;}",
      ".osk-tour-controls .osk-tour-spacer{flex:1 1 auto;}",
      /* Buttons echo app.html's .btn language: >=38px tap targets, accent primary. */
      ".osk-tour-btn{font:inherit;font-weight:600;font-size:0.85rem;min-height:38px;" +
        "border-radius:8px;border:1px solid var(--border,#30363d);padding:0.4rem 0.9rem;" +
        "background:transparent;color:var(--fg,#e6edf3);cursor:pointer;-webkit-appearance:none;" +
        "appearance:none;-webkit-tap-highlight-color:transparent;}",
      ".osk-tour-btn:hover{border-color:var(--muted,#8b949e);}",
      ".osk-tour-btn:focus-visible{outline:2px solid var(--accent,#4c8bf5);outline-offset:2px;}",
      ".osk-tour-btn--primary{background:var(--accent,#4c8bf5);color:#fff;border-color:transparent;}",
      ".osk-tour-btn--primary:hover{filter:brightness(1.08);border-color:transparent;}",
      ".osk-tour-btn--ghost{color:var(--muted,#8b949e);border-color:transparent;padding:0.4rem 0.55rem;}",
      ".osk-tour-btn--ghost:hover{color:var(--fg,#e6edf3);border-color:transparent;}",
      /* The always-present "?" launcher / replay affordance, docked bottom-right. */
      ".osk-tour-launcher{position:fixed;right:1rem;bottom:1rem;z-index:2147482000;" +
        "min-width:44px;height:44px;padding:0 0.9rem;border-radius:999px;font:inherit;" +
        "font-weight:700;font-size:0.9rem;display:inline-flex;align-items:center;gap:0.4rem;" +
        "background:var(--panel,#161b22);color:var(--fg,#e6edf3);border:1px solid var(--border,#30363d);" +
        "box-shadow:0 4px 14px rgba(1,4,9,0.4);cursor:pointer;-webkit-appearance:none;appearance:none;}",
      ".osk-tour-launcher:hover{border-color:var(--accent,#4c8bf5);}",
      ".osk-tour-launcher:focus-visible{outline:2px solid var(--accent,#4c8bf5);outline-offset:2px;}",
      ".osk-tour-launcher__q{display:inline-flex;align-items:center;justify-content:center;" +
        "width:20px;height:20px;border-radius:50%;background:var(--accent,#4c8bf5);color:#fff;" +
        "font-size:0.8rem;line-height:1;}",
      /* Phone-width: the bubble docks to the bottom of the screen as a sheet and the
         arrow is hidden — anchored positioning is unreliable on a narrow viewport. */
      "@media (max-width:640px){.osk-tour-bubble{left:0.75rem!important;right:0.75rem!important;" +
        "width:auto!important;max-width:none;bottom:0.75rem!important;top:auto!important;}" +
        ".osk-tour-arrow{display:none!important;}" +
        ".osk-tour-launcher{right:0.75rem;bottom:0.75rem;}}",
      /* Honour reduced-motion: kill transitions + the spotlight tween. */
      "@media (prefers-reduced-motion:reduce){.osk-tour-spot,.osk-tour-bubble{transition:none!important;}}",
      /* Sketch theme: wobbly hand-drawn edges, matching the panels/buttons. */
      "[data-theme='sketch'] .osk-tour-bubble{border-width:1.5px;" +
        "border-radius:255px 15px 225px 15px/15px 225px 15px 255px;box-shadow:2px 3px 0 rgba(22,40,59,0.2);}",
      "[data-theme='sketch'] .osk-tour-btn{border-radius:14px 225px 14px 225px/225px 14px 225px 14px;}",
      "[data-theme='sketch'] .osk-tour-launcher{border-radius:14px 225px 14px 225px/225px 14px 225px 14px;}",
      "[data-theme='sketch'] .osk-tour-spot{border-radius:14px;}",
    ].join("\n");
    var style = document.createElement("style");
    style.id = STYLE_ID;
    style.textContent = css;
    document.head.appendChild(style);
  }

  // --- Live tour state (browser only). `activeSteps` is this page's visible steps,
  // snapshotted when the tour starts on this page. `progress.step` indexes into it. --
  var ui = null; // { backdrop, spot, bubble, arrow, els... } while a step is showing
  var activeSteps = [];
  var progress = null; // the live, in-memory copy of what we persist
  var lastFocused = null; // element to restore focus to when the tour closes
  var launcherEl = null;

  // Build (or find) the page's visible step list from the registry.
  function computeActiveSteps(pageKey) {
    var steps = TOURS[pageKey] || [];
    return filterVisibleSteps(steps, isVisibleSelector);
  }

  // ---- Coachmark rendering ------------------------------------------------------

  function removeUi() {
    if (!ui) { return; }
    if (ui.onKey) { document.removeEventListener("keydown", ui.onKey, true); }
    if (ui.onReflow) {
      window.removeEventListener("resize", ui.onReflow);
      window.removeEventListener("scroll", ui.onReflow, true);
    }
    if (ui.backdrop && ui.backdrop.parentNode) { ui.backdrop.parentNode.removeChild(ui.backdrop); }
    if (ui.bubble && ui.bubble.parentNode) { ui.bubble.parentNode.removeChild(ui.bubble); }
    ui = null;
  }

  // Position the spotlight box over a target rect (with a little breathing-room pad).
  function positionSpot(spot, rect) {
    var pad = 6;
    spot.classList.remove("osk-tour-spot--flat");
    spot.style.top = (rect.top - pad) + "px";
    spot.style.left = (rect.left - pad) + "px";
    spot.style.width = (rect.width + pad * 2) + "px";
    spot.style.height = (rect.height + pad * 2) + "px";
  }

  // Place the bubble relative to a target rect according to `placement`, flipping to
  // the opposite side when there isn't room. Also positions the arrow. On phones the
  // stylesheet overrides all of this to a bottom sheet (see the media query).
  function positionBubble(bubble, arrow, rect, placement) {
    var vw = window.innerWidth;
    var vh = window.innerHeight;
    if (vw <= 640) {
      // The bottom-sheet layout is fully handled by CSS !important rules; just make
      // sure no stale inline coords fight it, and hide the arrow.
      bubble.style.top = "";
      bubble.style.left = "";
      arrow.className = "osk-tour-arrow osk-tour-arrow--hidden";
      return;
    }
    var bw = bubble.offsetWidth;
    var bh = bubble.offsetHeight;
    var gap = 14;
    var margin = 8;
    var place = placement || "auto";

    // Resolve "auto" and flip when a side lacks space.
    if (place === "auto") {
      place = (vh - rect.bottom > bh + gap) ? "bottom"
        : (rect.top > bh + gap) ? "top"
        : (vw - rect.right > bw + gap) ? "right" : "left";
    } else if (place === "bottom" && vh - rect.bottom < bh + gap && rect.top > bh + gap) {
      place = "top";
    } else if (place === "top" && rect.top < bh + gap && vh - rect.bottom > bh + gap) {
      place = "bottom";
    } else if (place === "right" && vw - rect.right < bw + gap && rect.left > bw + gap) {
      place = "left";
    } else if (place === "left" && rect.left < bw + gap && vw - rect.right > bw + gap) {
      place = "right";
    }

    var top, left, arrowClass, arrowLeft = "", arrowTop = "";
    var cx = rect.left + rect.width / 2;
    var cy = rect.top + rect.height / 2;
    if (place === "bottom" || place === "top") {
      left = clamp(cx - bw / 2, margin, vw - bw - margin);
      top = place === "bottom" ? rect.bottom + gap : rect.top - bh - gap;
      arrowClass = place === "bottom" ? "osk-tour-arrow--bottom" : "osk-tour-arrow--top";
      arrowLeft = clamp(cx - left - 6, 12, bw - 24) + "px";
    } else {
      top = clamp(cy - bh / 2, margin, vh - bh - margin);
      left = place === "right" ? rect.right + gap : rect.left - bw - gap;
      arrowClass = place === "right" ? "osk-tour-arrow--right" : "osk-tour-arrow--left";
      arrowTop = clamp(cy - top - 6, 12, bh - 24) + "px";
    }
    bubble.style.top = Math.round(top) + "px";
    bubble.style.left = Math.round(left) + "px";
    arrow.className = "osk-tour-arrow " + arrowClass;
    arrow.style.left = arrowLeft;
    arrow.style.top = arrowTop;
  }

  function clamp(v, lo, hi) { return v < lo ? lo : (v > hi ? hi : v); }

  // Render the coachmark for the current progress.step (index into activeSteps).
  // Rebuilds the bubble each step (simple + robust). Handles a target that vanished
  // between the snapshot and now by skipping forward gracefully.
  function renderStep() {
    var total = activeSteps.length;
    if (total === 0) { finishTour(); return; }
    if (progress.step >= total) { progress.step = total - 1; }
    if (progress.step < 0) { progress.step = 0; }

    var step = activeSteps[progress.step];

    // If a targeted element disappeared since the snapshot, skip it (forward, then
    // backward) so we never point at nothing. If none remain, finish gracefully.
    var targetEl = step.target ? safeQuery(step.target) : null;
    if (step.target && !isVisibleEl(targetEl)) {
      if (!skipToVisible()) { finishTour(); }
      return;
    }

    injectStyles();
    removeUi(); // clear any prior step's DOM

    var backdrop = document.createElement("div");
    backdrop.className = "osk-tour-backdrop";
    if (!step.target) { backdrop.classList.add("osk-tour-backdrop--dim"); }

    var spot = document.createElement("div");
    spot.className = "osk-tour-spot";
    if (!step.target) { spot.style.display = "none"; }

    var bubble = buildBubble(step, total);
    backdrop.appendChild(spot);

    document.body.appendChild(backdrop);
    document.body.appendChild(bubble);

    ui = {
      backdrop: backdrop,
      spot: spot,
      bubble: bubble,
      arrow: bubble.__arrow,
      targetEl: targetEl,
      placement: step.placement,
    };

    // Reflow handler: keep the spotlight + bubble glued to the target on scroll/resize.
    ui.onReflow = function () { reflow(); };
    window.addEventListener("resize", ui.onReflow);
    window.addEventListener("scroll", ui.onReflow, true);

    // Keyboard: Esc pauses/exits, arrows navigate. Captured so it works even when the
    // focus is on a page control behind the backdrop.
    ui.onKey = function (ev) {
      if (ev.key === "Escape") { ev.preventDefault(); pauseTour(); }
      else if (ev.key === "ArrowRight") { ev.preventDefault(); goNext(); }
      else if (ev.key === "ArrowLeft") { ev.preventDefault(); goBack(); }
    };
    document.addEventListener("keydown", ui.onKey, true);

    // Bring the target into view, then place everything once layout has settled.
    if (targetEl && targetEl.scrollIntoView) {
      try {
        targetEl.scrollIntoView({
          block: "center",
          inline: "nearest",
          behavior: reducedMotion ? "auto" : "smooth",
        });
      } catch (e) { targetEl.scrollIntoView(); }
    }
    window.requestAnimationFrame(function () {
      reflow();
      // Move focus into the coachmark for keyboard users (primary button).
      var primary = bubble.querySelector(".osk-tour-btn--primary");
      if (primary && primary.focus) { try { primary.focus(); } catch (e) { /* ignore */ } }
    });

    // Persist the live position each time we show a step (so a refresh resumes here).
    progress.paused = false;
    progress.active = true;
    saveProgress(progress);
    syncLauncher();
  }

  // Recompute geometry for the current step.
  function reflow() {
    if (!ui) { return; }
    if (!ui.targetEl) { return; } // centred step: CSS centres the bubble via defaults
    if (!isVisibleEl(ui.targetEl)) { if (!skipToVisible()) { finishTour(); } return; }
    var rect = ui.targetEl.getBoundingClientRect();
    positionSpot(ui.spot, rect);
    positionBubble(ui.bubble, ui.arrow, rect, ui.placement);
  }

  // Build the bubble DOM (title/body/progress/controls). textContent only (XSS-safe).
  function buildBubble(step, total) {
    var bubble = document.createElement("div");
    bubble.className = "osk-tour-bubble";
    bubble.setAttribute("role", "dialog");
    bubble.setAttribute("aria-modal", "false"); // does NOT trap: Esc always escapes
    bubble.setAttribute("aria-labelledby", "osk-tour-title");
    bubble.setAttribute("aria-describedby", "osk-tour-body");

    var arrow = document.createElement("div");
    arrow.className = "osk-tour-arrow osk-tour-arrow--hidden";
    bubble.appendChild(arrow);
    bubble.__arrow = arrow;

    var prog = document.createElement("p");
    prog.className = "osk-tour-progress";
    prog.setAttribute("aria-live", "polite");
    prog.textContent = progressLabel(progress.step, total);
    bubble.appendChild(prog);

    var title = document.createElement("h2");
    title.className = "osk-tour-title";
    title.id = "osk-tour-title";
    title.textContent = step.title || "";
    bubble.appendChild(title);

    var body = document.createElement("p");
    body.className = "osk-tour-body";
    body.id = "osk-tour-body";
    body.textContent = step.body || "";
    bubble.appendChild(body);

    var controls = document.createElement("div");
    controls.className = "osk-tour-controls";

    // Skip (pauses the tour). A ghost, left-aligned so it reads as the quiet exit.
    var skip = document.createElement("button");
    skip.type = "button";
    skip.className = "osk-tour-btn osk-tour-btn--ghost";
    skip.textContent = "Skip";
    skip.addEventListener("click", function () { pauseTour(); });
    controls.appendChild(skip);

    var spacer = document.createElement("span");
    spacer.className = "osk-tour-spacer";
    controls.appendChild(spacer);

    // Back (hidden on the first step of the page).
    if (progress.step > 0) {
      var back = document.createElement("button");
      back.type = "button";
      back.className = "osk-tour-btn";
      back.textContent = "Back";
      back.addEventListener("click", function () { goBack(); });
      controls.appendChild(back);
    }

    // Next / Continue / Done, depending on where we are.
    var cmd = nextCommand(progress, total);
    var next = document.createElement("button");
    next.type = "button";
    next.className = "osk-tour-btn osk-tour-btn--primary";
    if (cmd.type === "step") { next.textContent = "Next"; }
    else if (cmd.type === "page") { next.textContent = "Continue on " + pageName(cmd.page) + " →"; }
    else { next.textContent = "Done"; }
    next.addEventListener("click", function () { goNext(); });
    controls.appendChild(next);

    bubble.appendChild(controls);
    return bubble;
  }

  // Friendly name for a page key, used on the "Continue on X" button.
  function pageName(key) {
    return ({ index: "Home", app: "Account", status: "Status", ops: "Ops", help: "Help" })[key] || key;
  }

  function safeQuery(sel) {
    try { return document.querySelector(sel); } catch (e) { return null; }
  }
  function isVisibleEl(el) {
    return !!(el && (el.offsetWidth || el.offsetHeight || (el.getClientRects && el.getClientRects().length)));
  }

  // Skip from the current index to the nearest visible-targeted step (forward first,
  // then backward). Returns true and re-renders if one was found, false if none.
  function skipToVisible() {
    var total = activeSteps.length;
    for (var i = progress.step + 1; i < total; i++) {
      if (!activeSteps[i].target || isVisibleEl(safeQuery(activeSteps[i].target))) {
        progress.step = i; renderStep(); return true;
      }
    }
    for (var j = progress.step - 1; j >= 0; j--) {
      if (!activeSteps[j].target || isVisibleEl(safeQuery(activeSteps[j].target))) {
        progress.step = j; renderStep(); return true;
      }
    }
    return false;
  }

  // ---- Tour lifecycle -----------------------------------------------------------

  // Start (or restart) the tour on THIS page. `walkthrough` chains onward at the end.
  function startTour(pageKey, walkthrough) {
    activeSteps = computeActiveSteps(pageKey);
    if (activeSteps.length === 0) { return; }
    lastFocused = document.activeElement;
    progress = startProgress(pageKey, walkthrough);
    renderStep();
  }

  // Resume a persisted, unpaused tour on this page at `step`.
  function resumeTour(pageKey, step) {
    activeSteps = computeActiveSteps(pageKey);
    if (activeSteps.length === 0) { clearProgress(); return; }
    lastFocused = document.activeElement;
    var stored = loadProgress();
    progress = startProgress(pageKey, stored ? stored.walkthrough : true);
    progress.step = Math.min(step, activeSteps.length - 1);
    renderStep();
  }

  function goNext() {
    if (!progress) { return; }
    var total = activeSteps.length;
    var cmd = nextCommand(progress, total);
    if (cmd.type === "step") {
      progress.step = cmd.step;
      renderStep();
    } else if (cmd.type === "page") {
      // Hand off to the next page: persist the new position, then navigate. The next
      // page's boot() sees an active, unpaused walkthrough parked on it and resumes.
      progress = startProgress(cmd.page, true);
      saveProgress(progress);
      removeUi();
      window.location.href = cmd.path;
    } else {
      finishTour();
    }
  }

  function goBack() {
    if (!progress) { return; }
    progress.step = backIndex(progress);
    renderStep();
  }

  // Pause = quiet exit that REMEMBERS where we were, so the launcher can resume it.
  function pauseTour() {
    if (progress) {
      progress = pauseProgress(progress);
      saveProgress(progress);
    }
    teardown();
  }

  // Finish = the tour completed. Clear progress so the launcher goes back to "Take the
  // tour", and make sure first-run never fires again.
  function finishTour() {
    clearProgress();
    markFirstRunDone();
    progress = null;
    teardown();
    // OSK-82 hook: announce completion so a sibling module (web/onboarding.js) can record
    // onboarding-completed SERVER-side (PATCH /api/v1/me/lifecycle). Additive + best-effort.
    emitTourEvent("osk:tour:finished");
  }

  // OSK-82 hook: fire a DOM CustomEvent on `window` so sibling modules can observe the
  // tour's lifecycle without tour.js knowing anything about them. Fully guarded — a
  // missing CustomEvent constructor or a throwing listener never breaks the tour, so this
  // is purely additive and safe to no-op. Kept tiny + generic (a `name` arg) so future
  // lifecycle events reuse it.
  function emitTourEvent(name) {
    try {
      var ev;
      if (typeof window.CustomEvent === "function") {
        ev = new window.CustomEvent(name);
      } else if (document.createEvent) {
        ev = document.createEvent("CustomEvent");
        ev.initCustomEvent(name, false, false, null);
      } else {
        return;
      }
      window.dispatchEvent(ev);
    } catch (e) { /* recording is best-effort — never let it break the tour */ }
  }

  function teardown() {
    removeUi();
    // Restore focus to whatever was focused before (or the launcher) — keyboard users
    // aren't dumped at the top of the document.
    var focusTarget = (lastFocused && lastFocused.focus) ? lastFocused : launcherEl;
    if (focusTarget && focusTarget.focus) { try { focusTarget.focus(); } catch (e) { /* ignore */ } }
    syncLauncher();
  }

  // ---- The "?" launcher (replay affordance) -------------------------------------

  function buildLauncher() {
    var btn = document.createElement("button");
    btn.type = "button";
    btn.id = "osk-tour-launcher";
    btn.className = "osk-tour-launcher";
    var q = document.createElement("span");
    q.className = "osk-tour-launcher__q";
    q.setAttribute("aria-hidden", "true");
    q.textContent = "?";
    var label = document.createElement("span");
    label.className = "osk-tour-launcher__label";
    btn.appendChild(q);
    btn.appendChild(label);
    btn.addEventListener("click", function () {
      var stored = loadProgress();
      var here = currentPageKey();
      if (stored && stored.active && stored.paused && stored.page === here) {
        // A paused tour is parked here — resume it.
        resumeTour(here, stored.step);
      } else {
        // Otherwise (re)start the whole-site walkthrough from this page.
        startTour(here, true);
      }
    });
    return btn;
  }

  // Keep the launcher's label in sync with whether a tour can be resumed here.
  function syncLauncher() {
    if (!launcherEl) { return; }
    var stored = loadProgress();
    var here = currentPageKey();
    var resumable = stored && stored.active && stored.paused && stored.page === here;
    var label = launcherEl.querySelector(".osk-tour-launcher__label");
    if (label) { label.textContent = resumable ? "Resume tour" : "Take the tour"; }
    launcherEl.setAttribute(
      "aria-label",
      resumable ? "Resume the product tour" : "Take the product tour"
    );
  }

  function currentPageKey() {
    // Allow an explicit override via <html data-tour-page="…"> (handy for testing /
    // pages served at an unusual path); else derive it from the URL.
    var explicit = document.documentElement.getAttribute("data-tour-page");
    if (explicit && TOURS[explicit]) { return explicit; }
    return normalizePageKey(window.location.pathname);
  }

  // ---- Boot ---------------------------------------------------------------------

  function boot() {
    var cfg = tourConfig(window.__APP_CONFIG__);
    var pageKey = currentPageKey();
    var hasSteps = !!(pageKey && TOURS[pageKey] && TOURS[pageKey].length);
    if (!hasSteps || !cfg.enabled) { return; } // nothing to do on this page

    injectStyles();

    // Show the launcher (replay affordance) on every page that has a tour. This is
    // always present, even under automation, so the tour stays manually launchable.
    launcherEl = buildLauncher();
    document.body.appendChild(launcherEl);
    syncLauncher();

    // Don't spontaneously pop the tour (auto-start OR cross-page resume) for an
    // AUTOMATED browser (Playwright/WebDriver/headless bots set navigator.webdriver).
    // Onboarding is for humans; auto-showing a full-screen backdrop over an automated
    // session only fights the e2e specs and scrapers. The launcher still starts it on
    // demand, which is exactly how tour.spec.ts drives it.
    var automated = false;
    try { automated = navigator.webdriver === true; } catch (e) { /* assume human */ }
    if (automated) { return; }

    var decision = decideOnLoad({
      progress: loadProgress(),
      firstRunDone: isFirstRunDone(),
      pageKey: pageKey,
      hasSteps: hasSteps,
      config: cfg,
    });

    if (decision.action === "resume") {
      resumeTour(pageKey, decision.step);
    } else if (decision.action === "autostart") {
      // OSK-82: a sibling module (web/onboarding.js) can take OWNERSHIP of the first-run
      // auto-start so it can drive it from SERVER-side onboarding state
      // (GET /api/v1/me.onboardingCompleted) instead of the localStorage-only flag. It
      // signals that synchronously (it sets window.__oskSuppressAutoStart before boot runs,
      // only on pages where it can consult the backend — i.e. where OSKAuth is present and
      // Firebase is configured). When owned, we SKIP our localStorage auto-start and let it
      // decide + call OSKTour.startTour(). RESUME (cross-page walkthrough) and the "?"
      // launcher are untouched, and with no sibling present this is our normal behaviour.
      var externalOwnsAutoStart = false;
      try { externalOwnsAutoStart = window.__oskSuppressAutoStart === true; } catch (e) { /* ignore */ }
      if (!externalOwnsAutoStart) {
        // First-run: fire ONCE and record it immediately, so even an instant dismissal
        // never re-triggers the auto-start on any later page load.
        markFirstRunDone();
        startTour(pageKey, true);
      }
    }
    // "idle" -> do nothing; the launcher is there to start/resume on demand.
  }

  // OSK-82: expose a MINIMAL, additive imperative surface so a sibling module
  // (web/onboarding.js) can drive the first-run auto-start from server-side onboarding
  // state and reconcile the local first-run flag with it. These are the SAME functions
  // boot() uses, just made callable from outside — no existing behaviour changes, and in
  // Node these are never attached (the whole browser section is skipped). `window.OSKTour`
  // already holds the pure API (assigned above); we augment it here.
  window.OSKTour.startTour = startTour; // startTour(pageKey, walkthrough) — begin at step 0
  window.OSKTour.markFirstRunDone = markFirstRunDone; // record the one-time first-run as done
  window.OSKTour.isFirstRunDone = isFirstRunDone; // has the first-run auto-start already fired?
  window.OSKTour.loadProgress = loadProgress; // read the persisted in-flight tour position
  window.OSKTour.currentPageKey = currentPageKey; // this page's tour key (honours data-tour-page)

  // Run after the DOM is parsed so targets + document.body exist.
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", boot);
  } else {
    boot();
  }
})(typeof globalThis !== "undefined" ? globalThis : this);
