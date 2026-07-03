// badges.js — account-state badges for the OpenSkeleton web front-end (OSK-70).
//
// WHAT: for a signed-in user it reads `GET /api/v1/me` and renders a compact,
//   accessible ROW OF BADGES that summarise the caller's account state at a glance:
//     - Email verified   (emailVerified: bool — OSK-73)
//     - Age verified      (ageVerified:  bool — OSK-69)
//     - MFA               (mfaEnabled: bool|null — OSK-73)
//   Each badge shows its state UNAMBIGUOUSLY: a distinct icon (✓ / ○ / ?) AND a
//   descriptive text label ("Email verified" / "Email unverified" / "MFA unknown"),
//   so the meaning never depends on colour alone (WCAG 1.4.1). The whole item also
//   carries an `aria-label` + `title` so a screen reader / hover gets the full text.
//
//   THE MFA TRI-STATE IS THE POINT: `mfaEnabled` is `bool | null`. firebase-admin
//   9.9.0 exposes no MFA accessor, so the backend currently ALWAYS sends `null`
//   ("unknown"). This module must NOT render null as "off" — that would tell users
//   MFA is disabled when we simply don't know. So a boolean maps to verified/off and
//   `null`/`undefined` maps to a third "unknown" state that is either shown as a
//   neutral grey "MFA unknown" badge (default) or hidden entirely (config-driven).
//
// WHY it is a plain (non-module) script AND Node-requirable (like auth.js / profile.js):
//   the other web pages load their scripts as plain, non-hashed <script src> tags so CD
//   can inject config at deploy time without a bundler. badges.js follows that convention
//   (<script src="/badges.js"> in app.html / profile.html) AND exports its pure helpers +
//   a dependency-injected controller via module.exports when required from Node, so the
//   state-mapping / render / 401 / signed-out behaviours can be simulated headlessly with a
//   FAKE DOM + STUBBED fetch and ZERO browser — see web/e2e/badges.simulation.cjs. The
//   `typeof window` / `typeof module` guards keep `node --check` and `require()` from ever
//   touching the DOM or the network.
//
// XSS-safe: every dynamic value reaches the DOM only via `.textContent` / `.setAttribute`
//   through `document.createElement` — NEVER innerHTML. In practice the only /me-derived
//   inputs are the three boolean/null flags (all badge label text is static, from the spec
//   below), so no user-controlled string is ever interpolated — but the render path is
//   strictly createElement/textContent regardless, and the simulation asserts innerHTML is
//   never assigned.
//
// NO SECRETS: the backend base URL comes from config.js and the Firebase ID token from
//   auth.js; access is enforced by the backend verifying the token on /api/v1/me, never by
//   hiding this file. Signed-out or on any error, the badges simply don't render.

// ===========================================================================
// BADGE SPEC — the single source of truth for which badges exist and their copy.
// Both the pure state-mapper and the DOM renderer iterate this list, so the set of
// badges (and their labels/tooltips/icons) is defined in exactly ONE place.
//
//   key           — stable identifier, also emitted as `data-badge` for styling/tests.
//   field         — the /api/v1/me property this badge reflects.
//   tri           — true for a bool|null field (MFA) whose null/undefined is a real
//                   third "unknown" state; false for a plain boolean (email/age) where
//                   a missing/non-true value is simply "unverified".
//   on / off / unknown — per-state copy: `label` is the VISIBLE text (icon + this), and
//                   `hint` is the longer tooltip / accessible description. Icons are
//                   fixed by STATE (see OSK_BADGE_STATE_ICON) so they stay consistent.
// ===========================================================================
var OSK_BADGE_SPECS = [
  {
    key: "email",
    field: "emailVerified",
    tri: false,
    on: { label: "Email verified", hint: "Your email address has been verified." },
    off: { label: "Email unverified", hint: "Your email address isn't verified yet." },
  },
  {
    key: "age",
    field: "ageVerified",
    tri: false,
    on: { label: "Age verified", hint: "Your age has been verified." },
    off: { label: "Age unverified", hint: "Your age isn't verified yet." },
  },
  {
    key: "mfa",
    field: "mfaEnabled",
    tri: true, // bool|null — null/undefined is "unknown", NOT "off".
    on: { label: "MFA on", hint: "Multi-factor authentication is enabled." },
    off: { label: "MFA off", hint: "Multi-factor authentication is not enabled." },
    unknown: {
      label: "MFA unknown",
      hint: "Multi-factor authentication status is unavailable.",
    },
  },
];

// The three visual STATES a badge can be in, and the icon that represents each. Kept
// separate from the per-badge copy so every "verified" badge looks identical, etc.
//   verified   — the good/complete state (green): a check.
//   unverified — a neutral not-yet state (grey): a hollow ring. NOT alarming red — an
//                unverified email/age is a nudge, not an error.
//   unknown    — we genuinely don't know (grey): a question mark. Only MFA reaches this.
var OSK_BADGE_STATE_ICON = {
  verified: "✓",   // ✓ CHECK MARK
  unverified: "○", // ○ WHITE CIRCLE (hollow ring)
  unknown: "?",    // ? QUESTION MARK
};

// ===========================================================================
// PURE, ENVIRONMENT-AGNOSTIC HELPERS
// Plain-data-in, plain-data-out: no globals, no DOM, no network. They run identically
// in the browser and under `node`/`require`, which is what makes the state mapping unit-
// testable without a browser.
// ===========================================================================

// How to treat a `null`/`undefined` MFA value, resolved from the (optional) config block.
//   "show" (default) — render a neutral grey "MFA unknown" badge (transparent + honest).
//   "hide"           — omit the MFA badge entirely while its state is unknown.
// Any other value falls back to "show". Non-tri badges ignore this entirely.
function oskResolveMfaUnknownMode(badgesConfig) {
  var mode = badgesConfig && badgesConfig.mfaUnknownMode;
  return mode === "hide" ? "hide" : "show";
}

// Map ONE spec + the /me object to a rendered-badge descriptor, or `null` when the badge
// should be omitted. Returns { key, state, label, hint, icon } where `state` is one of
// "verified" | "unverified" | "unknown".
//
//   - tri badge (MFA): strict `=== true` => verified; `=== false` => unverified; anything
//     else (null / undefined / absent) => "unknown". In "hide" mode an unknown returns
//     null so the caller drops it. This is the crux: null is NEVER "off".
//   - plain badge (email/age): strict `=== true` => verified; everything else (false /
//     null / undefined / absent) => "unverified". A missing flag is conservatively shown
//     as unverified rather than invented as verified.
function oskComputeBadge(spec, me, mfaUnknownMode) {
  var value = me ? me[spec.field] : undefined;

  if (spec.tri) {
    if (value === true) {
      return { key: spec.key, state: "verified", label: spec.on.label, hint: spec.on.hint, icon: OSK_BADGE_STATE_ICON.verified };
    }
    if (value === false) {
      return { key: spec.key, state: "unverified", label: spec.off.label, hint: spec.off.hint, icon: OSK_BADGE_STATE_ICON.unverified };
    }
    // null / undefined / anything else => unknown.
    if (mfaUnknownMode === "hide") { return null; }
    return { key: spec.key, state: "unknown", label: spec.unknown.label, hint: spec.unknown.hint, icon: OSK_BADGE_STATE_ICON.unknown };
  }

  if (value === true) {
    return { key: spec.key, state: "verified", label: spec.on.label, hint: spec.on.hint, icon: OSK_BADGE_STATE_ICON.verified };
  }
  return { key: spec.key, state: "unverified", label: spec.off.label, hint: spec.off.hint, icon: OSK_BADGE_STATE_ICON.unverified };
}

// Map a whole /me object to the ORDERED list of badge descriptors to render (spec order).
// `badgesConfig` is the optional window.__APP_CONFIG__.badges block; only `mfaUnknownMode`
// is read today. A null/absent `me` yields all-"unverified"/"unknown" badges (defensive),
// but the controller never calls this without a loaded profile.
function oskComputeBadges(me, badgesConfig) {
  var mode = oskResolveMfaUnknownMode(badgesConfig);
  var out = [];
  for (var i = 0; i < OSK_BADGE_SPECS.length; i++) {
    var badge = oskComputeBadge(OSK_BADGE_SPECS[i], me, mode);
    if (badge) { out.push(badge); }
  }
  return out;
}

// ===========================================================================
// BADGES CONTROLLER — dependency-injected so it runs in the browser AND under a fake DOM
// + stubbed fetch in Node. It owns ONLY the badge row: fetch /me, render the badges into a
// mount element, and clear them on sign-out / error. The auth GATE (deciding signed-in vs
// signed-out) is the browser bootstrap's job, using the pure OSKAuth guard helpers — this
// controller assumes it is asked to `load()` only when a session should exist, and it still
// degrades gracefully (renders nothing) if that assumption is wrong.
//
// deps = {
//   doc          — a DOM-ish object exposing createElement(tag) -> element. Elements need
//                  only .textContent / .setAttribute / .appendChild here. The Node sim
//                  passes tiny stand-ins — no jsdom, no browser.
//   mount        — the container element the badges are rendered into (an <ul>). Needs
//                  .appendChild / .removeChild / .firstChild / .hidden. When empty, it is
//                  hidden so nothing (not even an empty box) shows.
//   fetchFn      — fetch(url, opts) -> Promise<{ ok, status, json() }>. Real fetch in the
//                  browser; a canned stub in the sim.
//   getIdToken   — () -> Promise<string|null>. OSKAuth.getIdToken in the browser; a fake in
//                  the sim. null => no active session (render nothing, gracefully).
//   apiBaseUrl   — backend base URL (config.js apiBaseUrl); trailing slashes trimmed.
//   badgesConfig — optional window.__APP_CONFIG__.badges (e.g. { mfaUnknownMode }).
// }
// ===========================================================================
function oskCreateBadgesController(deps) {
  var d = deps || {};
  var doc = d.doc;
  var mount = d.mount;
  var fetchFn = d.fetchFn;
  var getIdToken = d.getIdToken;
  var apiBase = String(d.apiBaseUrl || "").replace(/\/+$/, ""); // no trailing slash => no "//api"
  var badgesConfig = d.badgesConfig || {};

  // Remove every child of the mount without innerHTML (keeps us strictly XSS-safe) and
  // hide it, so a signed-out / errored / empty state shows literally nothing.
  function clear() {
    if (!mount) { return; }
    while (mount.firstChild) { mount.removeChild(mount.firstChild); }
    mount.hidden = true;
  }

  // Build one <li> badge element from a descriptor, using createElement + textContent only.
  // The <li> carries data-badge / data-state (for CSS + tests), an aria-label + title with
  // the full descriptive text, and two children: an aria-hidden icon and the visible label.
  function buildBadgeEl(badge) {
    var li = doc.createElement("li");
    li.className = "acct-badge acct-badge--" + badge.state;
    li.setAttribute("data-badge", badge.key);
    li.setAttribute("data-state", badge.state);
    // The visible label already conveys the state in words, but naming the item explicitly
    // guarantees a stable accessible name even if the visible copy is later shortened.
    li.setAttribute("aria-label", badge.label);
    li.setAttribute("title", badge.hint);

    var icon = doc.createElement("span");
    icon.className = "acct-badge__icon";
    icon.setAttribute("aria-hidden", "true"); // decorative — the label carries the meaning.
    icon.textContent = badge.icon;

    var label = doc.createElement("span");
    label.className = "acct-badge__label";
    label.textContent = badge.label;

    li.appendChild(icon);
    li.appendChild(label);
    return li;
  }

  // Render a loaded /me object as the badge row. Clears first, then appends each computed
  // badge. Hides the mount when there are no badges to show (e.g. MFA hidden and nothing
  // else — not currently possible, but defensive). Returns the descriptor list rendered.
  function render(me) {
    if (!mount) { return []; }
    while (mount.firstChild) { mount.removeChild(mount.firstChild); }
    var badges = oskComputeBadges(me, badgesConfig);
    for (var i = 0; i < badges.length; i++) {
      mount.appendChild(buildBadgeEl(badges[i]));
    }
    mount.hidden = badges.length === 0;
    return badges;
  }

  // Fetch GET /api/v1/me and render the badges. Resolves to a small outcome object so the
  // caller (and the sim) can assert the path taken WITHOUT scraping the DOM. On EVERY
  // non-success path the row is cleared (renders nothing) — the badges are a read-only
  // adornment, so a missing token / 401 / other error / network failure must never surface
  // a broken widget, it just isn't there.
  function load() {
    return Promise.resolve()
      .then(function () { return getIdToken ? getIdToken() : null; })
      .then(function (token) {
        if (!token) { clear(); return { ok: false, reason: "no-token" }; }
        return fetchFn(apiBase + "/api/v1/me", {
          headers: { Authorization: "Bearer " + token, Accept: "application/json" },
          cache: "no-store",
        }).then(function (res) {
          if (res.status === 401) { clear(); return { ok: false, status: 401 }; }
          if (!res.ok) { clear(); return { ok: false, status: res.status }; }
          return res.json().then(
            function (me) {
              var badges = render(me && typeof me === "object" ? me : {});
              return { ok: true, status: res.status, badges: badges };
            },
            function () {
              // 2xx but an unreadable body — treat as "nothing to show" rather than crash.
              clear();
              return { ok: false, reason: "bad-json", status: res.status };
            }
          );
        });
      })
      .catch(function () {
        // Network / CORS / backend-down: degrade to nothing, never throw.
        clear();
        return { ok: false, reason: "network" };
      });
  }

  // Expose the real API + the pure render for the sim (which seeds a /me directly to assert
  // the DOM output without a round-trip).
  return {
    load: load,
    render: render,
    clear: clear,
  };
}

// ---------------------------------------------------------------------------
// Node export. When required (CommonJS), expose the pure helpers + the controller factory
// so the row's behaviour can be simulated headlessly. `typeof module` is "undefined" in a
// classic browser <script>, so this block is a no-op there.
// ---------------------------------------------------------------------------
if (typeof module !== "undefined" && module.exports) {
  module.exports = {
    OSK_BADGE_SPECS: OSK_BADGE_SPECS,
    OSK_BADGE_STATE_ICON: OSK_BADGE_STATE_ICON,
    resolveMfaUnknownMode: oskResolveMfaUnknownMode,
    computeBadge: oskComputeBadge,
    computeBadges: oskComputeBadges,
    createBadgesController: oskCreateBadgesController,
  };
}

// ===========================================================================
// BROWSER BOOTSTRAP
// Runs ONLY in a real browser (guarded by `typeof window`). It idempotently loads the
// stylesheet, finds the shared mount, wires the real OSKAuth + fetch + DOM into the
// controller, and drives it from the auth state: load the badges on the transition INTO
// signed-in, clear them whenever signed out. Mirrors app.html's guard render loop but owns
// only the badge row, so it can be dropped onto any page that has a `#account-badges`
// mount + OSKAuth — no page-specific glue.
// ===========================================================================
if (typeof window !== "undefined") {
  (function () {
    "use strict";

    // Idempotently load the badges' stylesheet (web/badges.css). Injecting it here means a
    // page only needs the mount element + this <script> — no per-page <head> edit — and
    // matches the config-driven, bundler-free house style (same pattern as verify-email.js).
    function ensureStylesheet() {
      try {
        if (document.querySelector('link[data-osk="badges"]')) { return; }
        var link = document.createElement("link");
        link.rel = "stylesheet";
        link.href = "/badges.css";
        link.setAttribute("data-osk", "badges");
        (document.head || document.documentElement).appendChild(link);
      } catch (e) {
        /* stylesheet is a progressive enhancement; the badges still convey state unstyled */
      }
    }

    function boot() {
      ensureStylesheet();

      var mount = document.getElementById("account-badges");
      // No mount on this page => nothing to do.
      if (!mount) { return; }

      var A = window.OSKAuth;
      // No auth module (e.g. a public page that doesn't load auth.js): there is no signed-in
      // state to reflect, so keep the row hidden and stop.
      if (!A) { mount.hidden = true; return; }

      var cfg = window.__APP_CONFIG__ || {};
      var controller = oskCreateBadgesController({
        doc: document,
        mount: mount,
        fetchFn: window.fetch ? window.fetch.bind(window) : null,
        getIdToken: function () { return A.getIdToken(); },
        apiBaseUrl: cfg.apiBaseUrl || "",
        badgesConfig: cfg.badges || {},
      });

      // Load once on the transition INTO signed-in (not on every repaint) to avoid redundant
      // /me calls; clear whenever we're not signed in so nothing lingers after sign-out.
      var wasSignedIn = false;
      function render() {
        var view = A.computeGuardView(A.getState());
        if (view.mode === "signed-in") {
          if (!wasSignedIn) { controller.load(); }
          wasSignedIn = true;
        } else {
          controller.clear();
          wasSignedIn = false;
        }
      }

      render();
      A.onAuthStateChanged(render);
      A.ready.then(render).catch(render);
    }

    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", boot);
    } else {
      boot();
    }
  })();
}
