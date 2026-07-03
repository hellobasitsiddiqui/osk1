// terms.js — OpenSkeleton terms/privacy acceptance gate (OSK-78).
//
// WHAT: a self-contained, config-driven GATE for the protected /app page. When a
//   signed-in user "enters the app" it compares the version of the terms/privacy the
//   user last accepted (`termsAcceptedVersion`, an OSK-69 lifecycle field returned by
//   `GET /api/v1/me`) against the CURRENT required version (window.__APP_CONFIG__.
//   termsVersion). If they DIFFER — including the null / never-accepted case — it shows a
//   blocking acceptance overlay (current version + Terms/Privacy links + an "Accept"
//   action) on top of the app, and keeps the app blocked until the user accepts. On
//   Accept it calls `PATCH /api/v1/me/lifecycle` with `{ termsAcceptedVersion: <current> }`
//   and the caller's Firebase ID token (`Authorization: Bearer …`); the SERVER stamps
//   `termsAcceptedAt` (the client never sends a timestamp). On success the gate dismisses
//   and the app is revealed. 401 / HTTP / offline errors surface inline (never a broken
//   or silently-trapped page); a signed-out visitor is left to the OSK-74 auth guard,
//   which shows the sign-in affordance — the terms gate never blocks a signed-out user.
//
// WHY it is a plain (non-module) script AND Node-requirable (UMD-ish), exactly like
//   web/auth.js and web/history.js:
//   - The web pages load their scripts as plain, non-hashed <script src> tags so CD can
//     inject config at deploy time without a bundler. app.html loads this as
//     <script src="/terms.js"> at the FOOT of the body (a single one-line include).
//   - Everything that is a PURE data->data transform (does a user need to accept? which
//     view state? classifying the /me + PATCH responses; building the gate DOM against an
//     injectable `document`) runs identically under `node`/`require` with ZERO browser —
//     so the whole gate behaviour is unit-simulatable headlessly (live sign-in is
//     human-gated behind the Firebase apiKey, OSK-92). See web/e2e/terms.simulation.cjs,
//     which drives THESE helpers with a fake DOM + a stubbed fetch.
//   - The pure helpers are exported via `module.exports` when required from Node; the
//     browser bootstrap at the bottom is skipped when `window` is absent.
//
// COMPOSITION WITH THE OTHER FIRST-LOGIN GATES (intended order: terms -> profile-completion
//   -> onboarding tour): this module is deliberately INDEPENDENT and IDEMPOTENT so the
//   ordering with OSK-136 (profile completion) and OSK-82 (onboarding tour) is not brittle.
//   It owns ONLY its own injected overlay + styles; it reads/writes nothing the other gates
//   touch. It blocks the whole viewport at a HIGH z-index (OSK_TERMS_Z, above the alert
//   banner and above the other gates), so when several gates would show at once, TERMS wins
//   the top of the stack and is resolved first; once accepted it removes itself from the
//   stack and the next gate (profile completion, then the tour) is free to take over. No
//   gate depends on another having run, so any subset can be present in any combination.
//
// XSS-safe: every dynamic string (version label, links, error text) is written with
//   textContent / setAttribute / createElement only — never innerHTML.
//
// NO SECRETS: the only network calls are to the config-driven apiBaseUrl with a short-lived
//   Firebase ID token minted client-side; nothing sensitive is embedded here.

// ===========================================================================
// PURE, ENVIRONMENT-AGNOSTIC HELPERS
// These take plain data and return plain data (or build nodes against an injected
// `document`). They touch no globals, no network and no Firebase, so they run
// identically in the browser and under `node`/`require` — which is what makes the
// gate/accept/error behaviour unit-simulatable without a browser.
// ===========================================================================

// Default destinations for the Terms / Privacy links shown in the gate. These are
// RELATIVE app paths (real pages are a follow-up); an operator can override either via an
// optional window.__APP_CONFIG__.termsUrl / privacyUrl without touching this file. Kept as
// constants (single source of truth) rather than magic strings sprinkled through the DOM
// builder.
var OSK_TERMS_DEFAULT_TERMS_URL = "/terms";
var OSK_TERMS_DEFAULT_PRIVACY_URL = "/privacy";

// z-index for the blocking overlay. Deliberately ABOVE the OSK-151 alert banner (1000) and
// above the sibling first-login gates, so terms sits at the TOP of the gate stack and is
// resolved first (see the COMPOSITION note in the header).
var OSK_TERMS_Z = 2000;

// Does this signed-in user need to (re)accept the current terms? Compares the version they
// last accepted to the CURRENT required version.
//   - currentVersion empty/null  => the gate is INERT: there is nothing to accept, so we
//     never block (safe to ship the module before real terms copy / a version exist).
//   - accepted !== current       => needs acceptance. This covers null / undefined /
//     never-accepted (accepted is null) AND an out-of-date prior acceptance.
// Defensive against a missing user object / non-string fields.
function oskNeedsTermsAcceptance(user, currentVersion) {
  var current = currentVersion == null ? "" : String(currentVersion).trim();
  if (current === "") {
    return false; // no current version configured -> nothing to gate on
  }
  var accepted =
    user && user.termsAcceptedVersion != null ? String(user.termsAcceptedVersion) : null;
  return accepted !== current;
}

// Map the gate's STATE snapshot to a VIEW descriptor. Pure: same input -> same output, so
// the show/hide + busy + error behaviour is trivially assertable.
//
// state = {
//   signedIn:        boolean,     // is a Firebase user present? (from OSKAuth.getState)
//   needsAcceptance: boolean,     // from oskNeedsTermsAcceptance (only meaningful when signedIn)
//   submitting:      boolean,     // is a PATCH /me/lifecycle in flight?
//   error:           string|null, // last accept/lookup error to surface in the gate
// }
//
// returns { mode: "hidden"|"gate"|"submitting", showGate, busy, errorText }
function oskComputeTermsView(state) {
  var s = state || {};

  // Signed out (or auth not yet settled) -> the OSK-74 guard owns the screen and shows the
  // sign-in affordance. The terms gate stays hidden and NEVER blocks a signed-out visitor.
  if (!s.signedIn) {
    return { mode: "hidden", showGate: false, busy: false, errorText: "" };
  }

  // Signed in and the accepted version differs from the current one -> block with the gate.
  if (s.needsAcceptance) {
    return {
      mode: s.submitting ? "submitting" : "gate",
      showGate: true,
      busy: !!s.submitting,
      errorText: s.error ? String(s.error) : "",
    };
  }

  // Signed in and versions match (or nothing to gate on) -> no gate; the app is free.
  return { mode: "hidden", showGate: false, busy: false, errorText: "" };
}

// Apply a VIEW descriptor to a set of DOM-ish elements. Deliberately duck-typed — it only
// sets `.hidden` / `.disabled` / `.textContent`, so the Node simulation can pass plain
// stand-ins and assert the result (no jsdom, no browser). Any element may be omitted.
//
// els = { gate, acceptBtn, error } (each optional)
function oskApplyTermsView(view, els) {
  var v = view || {};
  var e = els || {};
  if (e.gate) {
    e.gate.hidden = !v.showGate;
  }
  if (e.acceptBtn) {
    e.acceptBtn.disabled = !!v.busy;
  }
  if (e.error) {
    e.error.textContent = v.errorText || "";
    e.error.hidden = !v.errorText;
  }
  return v;
}

// Turn an accept/lookup RESULT descriptor into a human-readable error line for the gate.
// Pure + centralised so the browser handler and the tests agree on the wording. Returns ""
// for the success / no-error statuses.
function oskTermsErrorText(result) {
  var r = result || {};
  switch (r.status) {
    case "unauthorized":
      // 401 (AC): the token was rejected/expired. Prompt a re-auth rather than a dead gate.
      return "Your session isn't authorized (401). Try signing out and back in.";
    case "no-session":
      return "No active session. Please sign in again.";
    case "http-error":
      return "Couldn't save your acceptance (HTTP " + (r.httpStatus || "?") + "). Please try again.";
    case "network-error":
      return "Couldn't reach the backend. Please try again.";
    default:
      return "";
  }
}

// Fetch the caller's profile and classify the outcome. Injectable deps keep it browser-free
// and testable (same shape as history.js's fetchHistory):
//   opts.getToken   — () => Promise<string|null> (browser: OSKAuth.getIdToken)
//   opts.apiBaseUrl — backend base URL (browser: config.js apiBaseUrl)
//   opts.fetchImpl  — fetch-compatible fn (browser: window.fetch; test: a stub)
// Never throws: any thrown/rejected error resolves to { status: "network-error" }.
// returns { status: "ok", user } | "no-session" | "unauthorized" | "http-error" | "network-error"
function oskFetchMe(opts) {
  var o = opts || {};
  var getToken = o.getToken;
  var fetchImpl = o.fetchImpl;
  var base = (o.apiBaseUrl || "").replace(/\/+$/, ""); // trim trailing slashes -> no "//api"
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
          return { status: "ok", user: body && typeof body === "object" ? body : null };
        });
      });
    })
    .catch(function () {
      return { status: "network-error" };
    });
}

// Record the user's acceptance of the CURRENT version:
//   PATCH /api/v1/me/lifecycle  { termsAcceptedVersion: <version> }
// with the Bearer token. The SERVER stamps `termsAcceptedAt` — the client NEVER sends a
// timestamp (only the version). Injectable deps mirror oskFetchMe.
//   opts.version — the current terms version being accepted (from config.termsVersion)
// Tolerates a missing/garbage response body on success (the endpoint may or may not echo
// the updated user). Never throws: rejects/errors resolve to { status: "network-error" }.
// returns { status: "ok", user } | "no-session" | "unauthorized" | "http-error" | "network-error"
function oskAcceptTerms(opts) {
  var o = opts || {};
  var getToken = o.getToken;
  var fetchImpl = o.fetchImpl;
  var version = o.version;
  var base = (o.apiBaseUrl || "").replace(/\/+$/, "");
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
        // ONLY the version — never a timestamp. The server stamps termsAcceptedAt.
        body: JSON.stringify({ termsAcceptedVersion: version }),
        cache: "no-store",
      }).then(function (r) {
        if (r.status === 401) {
          return { status: "unauthorized" };
        }
        if (!r.ok) {
          return { status: "http-error", httpStatus: r.status };
        }
        // Success. The body (updated user) is a bonus, not a requirement — parse it
        // defensively so a 200 with no/garbage body still counts as accepted.
        return Promise.resolve()
          .then(function () {
            return typeof r.json === "function" ? r.json() : null;
          })
          .then(function (body) {
            return { status: "ok", user: body && typeof body === "object" ? body : null };
          })
          .catch(function () {
            return { status: "ok", user: null };
          });
      });
    })
    .catch(function () {
      return { status: "network-error" };
    });
}

// Build the blocking gate overlay against an injected `document`. Uses only createElement /
// textContent / setAttribute / appendChild, so it is (a) strictly XSS-safe and (b) the Node
// simulation can pass a tiny fake `document` and assert the structure/text (version + links)
// without jsdom or a browser. Starts HIDDEN; the bootstrap reveals it via applyTermsView.
//
// opts = { version, termsUrl, privacyUrl }
// returns { root, versionValue, termsLink, privacyLink, error, acceptBtn }
function oskBuildGate(doc, opts) {
  var o = opts || {};
  var version = o.version == null ? "" : String(o.version);
  var termsUrl = o.termsUrl || OSK_TERMS_DEFAULT_TERMS_URL;
  var privacyUrl = o.privacyUrl || OSK_TERMS_DEFAULT_PRIVACY_URL;

  // Full-viewport backdrop that traps interaction with the app underneath.
  var root = doc.createElement("div");
  root.className = "osk-terms-gate";
  root.setAttribute("role", "dialog");
  root.setAttribute("aria-modal", "true");
  root.setAttribute("aria-labelledby", "osk-terms-title");
  root.hidden = true;

  var card = doc.createElement("div");
  card.className = "osk-terms-gate__card";

  var title = doc.createElement("h2");
  title.className = "osk-terms-gate__title";
  title.setAttribute("id", "osk-terms-title");
  title.textContent = "Review our terms";
  card.appendChild(title);

  var body = doc.createElement("p");
  body.className = "osk-terms-gate__body";
  body.textContent =
    "Please review and accept our latest Terms of Service and Privacy Policy to continue.";
  card.appendChild(body);

  // Current version line (displayed so the user knows exactly what they're accepting).
  var versionLine = doc.createElement("p");
  versionLine.className = "osk-terms-gate__version";
  var versionLabel = doc.createElement("span");
  versionLabel.className = "osk-terms-gate__version-label";
  versionLabel.textContent = "Current version: ";
  var versionValue = doc.createElement("span");
  versionValue.className = "osk-terms-gate__version-value";
  versionValue.textContent = version || "(unversioned)";
  versionLine.appendChild(versionLabel);
  versionLine.appendChild(versionValue);
  card.appendChild(versionLine);

  // Terms / Privacy links (open in a new tab so the user doesn't lose the gate).
  var links = doc.createElement("p");
  links.className = "osk-terms-gate__links";

  var termsLink = doc.createElement("a");
  termsLink.className = "osk-terms-gate__link osk-terms-gate__link--terms";
  termsLink.setAttribute("href", termsUrl);
  termsLink.setAttribute("target", "_blank");
  termsLink.setAttribute("rel", "noopener");
  termsLink.textContent = "Terms of Service";
  links.appendChild(termsLink);

  var sep = doc.createElement("span");
  sep.className = "osk-terms-gate__sep";
  sep.setAttribute("aria-hidden", "true");
  sep.textContent = " · ";
  links.appendChild(sep);

  var privacyLink = doc.createElement("a");
  privacyLink.className = "osk-terms-gate__link osk-terms-gate__link--privacy";
  privacyLink.setAttribute("href", privacyUrl);
  privacyLink.setAttribute("target", "_blank");
  privacyLink.setAttribute("rel", "noopener");
  privacyLink.textContent = "Privacy Policy";
  links.appendChild(privacyLink);

  card.appendChild(links);

  // Inline error line (401 / HTTP / offline). Hidden until set.
  var error = doc.createElement("p");
  error.className = "osk-terms-gate__error";
  error.setAttribute("role", "alert");
  error.hidden = true;
  card.appendChild(error);

  // The Accept action.
  var acceptBtn = doc.createElement("button");
  acceptBtn.className = "osk-terms-gate__accept";
  acceptBtn.setAttribute("type", "button");
  acceptBtn.textContent = "Accept and continue";
  card.appendChild(acceptBtn);

  root.appendChild(card);

  return {
    root: root,
    versionValue: versionValue,
    termsLink: termsLink,
    privacyLink: privacyLink,
    error: error,
    acceptBtn: acceptBtn,
  };
}

// The overlay's themed CSS as a string. Kept as a pure function (single source of truth,
// assertable) that the bootstrap injects once into <head>. Every colour is a SHARED palette
// token (var(--panel)/(--fg)/(--accent)/(--border)/(--down)) so it re-skins for the OSK-127
// sketch theme automatically, and it collapses gracefully at the shared ~640px breakpoint.
function oskTermsStyles() {
  return [
    ".osk-terms-gate{",
    "  position:fixed;inset:0;z-index:" + OSK_TERMS_Z + ";",
    "  display:flex;align-items:center;justify-content:center;",
    "  padding:1.25rem;",
    "  background:rgba(1,4,9,0.72);",
    "  -webkit-backdrop-filter:blur(2px);backdrop-filter:blur(2px);",
    "}",
    ".osk-terms-gate[hidden]{display:none;}",
    ".osk-terms-gate__card{",
    "  width:100%;max-width:30rem;",
    "  border:1px solid var(--border,#30363d);border-radius:12px;",
    "  background:var(--panel,#161b22);color:var(--fg,#e6edf3);",
    "  padding:1.5rem 1.6rem;",
    "  box-shadow:0 12px 40px rgba(0,0,0,0.5);",
    "  font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;",
    "}",
    ".osk-terms-gate__title{font-size:1.2rem;margin:0 0 0.5rem;letter-spacing:-0.01em;}",
    ".osk-terms-gate__body{margin:0 0 0.9rem;color:var(--fg,#e6edf3);line-height:1.5;}",
    ".osk-terms-gate__version{",
    "  margin:0 0 0.6rem;font-size:0.88rem;color:var(--muted,#8b949e);",
    "}",
    ".osk-terms-gate__version-value{",
    "  font-family:ui-monospace,SFMono-Regular,'SF Mono',Menlo,Consolas,monospace;",
    "  color:var(--fg,#e6edf3);",
    "}",
    ".osk-terms-gate__links{margin:0 0 1rem;font-size:0.9rem;}",
    ".osk-terms-gate__link{color:var(--accent,#4c8bf5);text-decoration:none;}",
    ".osk-terms-gate__link:hover{text-decoration:underline;}",
    ".osk-terms-gate__link:focus-visible{outline:2px solid var(--accent,#4c8bf5);outline-offset:2px;}",
    ".osk-terms-gate__sep{color:var(--muted,#8b949e);}",
    ".osk-terms-gate__error{",
    "  margin:0 0 0.9rem;color:var(--down,#f85149);font-size:0.85rem;",
    "}",
    ".osk-terms-gate__error[hidden]{display:none;}",
    ".osk-terms-gate__accept{",
    "  display:inline-flex;align-items:center;justify-content:center;",
    "  width:100%;min-height:44px;",
    "  font:inherit;font-weight:600;color:#fff;",
    "  background:var(--accent,#4c8bf5);border:1px solid transparent;border-radius:8px;",
    "  padding:0.55rem 1rem;cursor:pointer;",
    "  -webkit-appearance:none;appearance:none;-webkit-tap-highlight-color:transparent;",
    "}",
    ".osk-terms-gate__accept:hover:not([disabled]){filter:brightness(1.08);}",
    ".osk-terms-gate__accept:focus-visible{outline:2px solid var(--accent,#4c8bf5);outline-offset:2px;}",
    ".osk-terms-gate__accept[disabled]{opacity:0.6;cursor:default;}",
    // OSK-127 sketch theme: paper-ify the card + button to match the hand-drawn surfaces.
    "[data-theme='sketch'] .osk-terms-gate__card{",
    "  border:1.5px solid var(--border);",
    "  border-radius:255px 15px 225px 15px / 15px 225px 15px 255px;",
    "  box-shadow:2px 3px 0 rgba(22,40,59,0.13);",
    "}",
    "[data-theme='sketch'] .osk-terms-gate__accept{",
    "  border-radius:14px 225px 14px 225px / 225px 14px 225px 14px;",
    "  box-shadow:1px 2px 0 rgba(22,40,59,0.13);",
    "}",
    // Shared ~640px phone breakpoint (mirrors index/status/ops/help/app).
    "@media (max-width:640px){",
    "  .osk-terms-gate{padding:1rem;align-items:flex-end;}",
    "  .osk-terms-gate__card{padding:1.2rem;}",
    "}",
  ].join("\n");
}

// ---------------------------------------------------------------------------
// Node export. Exposed only under CommonJS (`typeof module` is "undefined" in a classic
// browser <script>, so this is a no-op there). Lets web/e2e/terms.simulation.cjs assert the
// SAME code the page runs.
// ---------------------------------------------------------------------------
if (typeof module !== "undefined" && module.exports) {
  module.exports = {
    TERMS_Z: OSK_TERMS_Z,
    DEFAULT_TERMS_URL: OSK_TERMS_DEFAULT_TERMS_URL,
    DEFAULT_PRIVACY_URL: OSK_TERMS_DEFAULT_PRIVACY_URL,
    needsTermsAcceptance: oskNeedsTermsAcceptance,
    computeTermsView: oskComputeTermsView,
    applyTermsView: oskApplyTermsView,
    termsErrorText: oskTermsErrorText,
    fetchMe: oskFetchMe,
    acceptTerms: oskAcceptTerms,
    buildGate: oskBuildGate,
    termsStyles: oskTermsStyles,
  };
}

// ===========================================================================
// BROWSER BOOTSTRAP
// Runs ONLY in a real browser (guarded by `typeof window` + `typeof document`). Injects the
// overlay + its styles, subscribes to window.OSKAuth (web/auth.js), and when the signed-in
// user is out of date on the terms version, blocks the app with the gate until they accept.
// Loaded at the FOOT of app.html so the DOM + window.OSKAuth already exist. Fully
// self-contained: it mutates NO existing page markup (only appends its own nodes), so it
// composes cleanly with the parallel first-login gates.
// ===========================================================================
if (typeof window !== "undefined" && typeof document !== "undefined") {
  (function () {
    "use strict";

    var cfg = window.__APP_CONFIG__ || {};
    var A = window.OSKAuth;

    // No auth module (auth.js failed to load) -> do nothing. app.html's own guard shows a
    // clear fallback notice; the terms gate has nothing to gate without an auth state.
    if (!A) {
      return;
    }

    // The current required version (string). "" => the gate is inert (never blocks), but we
    // still wire everything up so setting termsVersion later + a reload activates it.
    var version =
      cfg.termsVersion == null ? "" : String(cfg.termsVersion);

    // --- Inject styles once ------------------------------------------------
    if (!document.getElementById("osk-terms-styles")) {
      var styleEl = document.createElement("style");
      styleEl.id = "osk-terms-styles";
      styleEl.textContent = oskTermsStyles();
      (document.head || document.documentElement).appendChild(styleEl);
    }

    // --- Build + mount the (hidden) gate overlay ---------------------------
    var gate = oskBuildGate(document, {
      version: version,
      termsUrl: cfg.termsUrl || OSK_TERMS_DEFAULT_TERMS_URL,
      privacyUrl: cfg.privacyUrl || OSK_TERMS_DEFAULT_PRIVACY_URL,
    });
    document.body.appendChild(gate.root);

    var els = { gate: gate.root, acceptBtn: gate.acceptBtn, error: gate.error };

    // --- Local state + render ---------------------------------------------
    var st = { signedIn: false, needsAcceptance: false, submitting: false, error: null };
    function apply() {
      oskApplyTermsView(oskComputeTermsView(st), els);
    }

    // Load /me and decide whether to block. Fail OPEN on a lookup we can't complete
    // (network/HTTP/401/no-session): we never trap the user behind a gate we couldn't
    // verify — the OSK-74 guard already handles signed-out, and a genuine mismatch is
    // re-checked on the next visit. Only a confirmed "ok" with a differing version blocks.
    function checkTerms() {
      oskFetchMe({
        getToken: function () {
          return A.getIdToken();
        },
        apiBaseUrl: cfg.apiBaseUrl,
        fetchImpl: window.fetch ? window.fetch.bind(window) : null,
      }).then(function (res) {
        st.needsAcceptance =
          res.status === "ok" ? oskNeedsTermsAcceptance(res.user, version) : false;
        apply();
      });
    }

    // --- Accept action -----------------------------------------------------
    gate.acceptBtn.addEventListener("click", function () {
      if (st.submitting) {
        return; // guard against a double-tap firing two PATCHes
      }
      st.submitting = true;
      st.error = null;
      apply(); // disables the button while in flight

      oskAcceptTerms({
        getToken: function () {
          return A.getIdToken();
        },
        apiBaseUrl: cfg.apiBaseUrl,
        version: version,
        fetchImpl: window.fetch ? window.fetch.bind(window) : null,
      }).then(function (res) {
        st.submitting = false;
        if (res.status === "ok") {
          // Accepted: dismiss the gate + reveal the app. If the endpoint echoed the updated
          // user, re-derive from it (idempotent); otherwise assume the current version.
          st.needsAcceptance = res.user
            ? oskNeedsTermsAcceptance(res.user, version)
            : false;
          st.error = null;
        } else {
          st.error = oskTermsErrorText(res); // 401 / HTTP / offline surfaced inline
        }
        apply();
      });
    });

    // --- Auth-state driven loop -------------------------------------------
    // (Re)check on the transition INTO signed-in; hide + reset on signed-out. Mirrors the
    // app.html / history.js render-loop pattern so the gate reacts to sign-in/out.
    var wasSignedIn = false;
    function onAuth() {
      var state = A.getState();
      st.signedIn = !!(state && state.user);
      if (st.signedIn) {
        if (!wasSignedIn) {
          // Fresh sign-in: reset, keep the gate hidden while /me is in flight (no flash),
          // then re-check.
          st.needsAcceptance = false;
          st.submitting = false;
          st.error = null;
          apply();
          checkTerms();
        }
        wasSignedIn = true;
      } else {
        // Signed out: never block. Reset everything.
        st.needsAcceptance = false;
        st.submitting = false;
        st.error = null;
        wasSignedIn = false;
        apply();
      }
    }

    apply(); // initial paint (hidden)
    A.onAuthStateChanged(onAuth);
    A.ready.then(onAuth).catch(onAuth);
  })();
}
