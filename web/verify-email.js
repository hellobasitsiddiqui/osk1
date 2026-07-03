// verify-email.js — OpenSkeleton email-verification banner module (OSK-75).
//
// WHAT: a self-contained, dependency-free module that drives the "please verify your
//   email" banner shown to a SIGNED-IN user whose email is NOT yet verified. It:
//     - reads the signed-in Firebase user's `emailVerified` flag from window.OSKAuth
//       (web/auth.js — NOT modified by this ticket), so the banner shows ONLY when a
//       user is signed in AND unverified,
//     - offers a "Resend verification email" action that POSTs to the backend
//       `POST /api/v1/me/email/resend-verification` with an `Authorization: Bearer`
//       Firebase ID token (OSK-77), then reflects the outcome: 202 => "sent" + a
//       cooldown that disables Resend; 429 => shows the Retry-After remaining and keeps
//       Resend disabled; 401 => a clear "sign in again" line; 200/already-verified,
//       422, 503 and network errors all handled gracefully (never a broken banner), and
//     - offers a "I've verified — refresh" action that reloads the Firebase auth user
//       (and force-refreshes the ID token) so a freshly-verified user can clear the
//       banner without a full reload. A VERIFIED user is never nagged.
//
// WHY it mirrors web/auth.js's shape (pure exported logic + a thin browser bootstrap):
//   - The DECISION logic (when to show the banner, how to interpret a resend HTTP
//     response, how to parse `Retry-After`, how to format a cooldown) is PURE — no DOM,
//     no network, no globals — so it runs identically in the browser and under `node`,
//     and is unit-simulated headlessly with a fake DOM + stubbed fetch + fake Firebase
//     user in web/e2e/verify-email.simulation.cjs (exactly as OSK-74's guard core is).
//   - The whole controller is built by `createVerifyEmailController(deps)`, a factory
//     that takes its DOM elements, an OSKAuth-like `auth`, `fetch`, config and timers as
//     INJECTED dependencies. The browser bootstrap wires the real ones; the Node
//     simulation wires fakes into the SAME factory — so the test drives the real code.
//
// WHY it is a plain (non-module) <script src> that is ALSO Node-requirable: same
//   convention as auth.js/config.js — CD can inject config at deploy time with no
//   bundler, and `module.exports` (when required from Node) exposes the pure helpers +
//   the factory for the simulation. The browser bootstrap is skipped when `window` is
//   absent, so `node --check` and `require()` never touch the DOM.
//
// HOUSE STYLE / COEXISTENCE (OSK-151): this banner deliberately follows the OSK-151
//   alert-banner house style (shared palette tokens, same dot + surface treatment, same
//   ~640px responsive breakpoint, re-skins for the sketch theme) and is injected via a
//   shared, BYTE-IDENTICAL block across the pages (see the mount `<aside>` + this
//   <script> tag). Unlike the OSK-151 alert (position:fixed at the very top), this
//   banner sits in NORMAL FLOW at the top of the page content, so it stacks cleanly
//   BELOW the fixed alert banner (which reserves its own space via body padding-top) and
//   the two never overlap. Styles live in web/verify-email.css, which this module loads
//   itself (an idempotent <link> injection) so a page only needs the shared block — no
//   per-page <head> edit.
//
// XSS-SAFE: every dynamic string (status messages, cooldown text) is written with
//   textContent only — never innerHTML.
//
// NO SECRETS: this module holds no credentials. The Firebase ID token is obtained at
//   call time from OSKAuth and sent only as a Bearer header to the backend.

// ===========================================================================
// PURE, ENVIRONMENT-AGNOSTIC HELPERS
// These take plain data and return plain data. They touch no globals, no DOM and
// no network, so they run identically in the browser and under `node`/`require`
// — which is what makes the banner's behaviour unit-testable without a browser.
// ===========================================================================

// The client-side cooldown (seconds) applied after a SUCCESSFUL resend (HTTP 202)
// when the server does not send a Retry-After. It just disables the Resend button
// for a courteous window so a user can't hammer the endpoint; the authoritative
// cooldown is enforced server-side (a 429 with Retry-After overrides this).
var OSK_VERIFY_DEFAULT_COOLDOWN_SECONDS = 60;

// Decide whether the verify banner should be SHOWN, from an OSKAuth state snapshot
// (the same `{ configured, ready, user, error }` shape auth.js exposes via getState()).
// Pure: given the same state it always returns the same object.
//
// Show the banner ONLY when: auth is configured, has settled (ready), has NOT errored,
// a user IS signed in, and that user's email is NOT verified. Note the deliberate
// `!== true`: we show the banner unless Firebase has explicitly confirmed the address is
// verified — i.e. we err toward prompting, never toward silently assuming "verified".
// A verified user (emailVerified === true) or a signed-out/loading/not-configured state
// all yield `show: false`, so a verified user is never nagged.
function oskComputeVerifyView(state) {
  var s = state || {};
  var user = s.user || null;
  var show = !!(
    s.configured &&
    s.ready &&
    !s.error &&
    user &&
    user.emailVerified !== true
  );
  return {
    show: show,
    // `verified` is only meaningful when signed in; it lets callers distinguish
    // "signed-in + verified" (hide, nothing to do) from "signed-out" (hide, N/A).
    verified: !!(user && user.emailVerified === true),
    signedIn: !!user,
  };
}

// Parse an HTTP `Retry-After` header value into a whole number of seconds remaining,
// or null when absent/unparseable. Accepts BOTH forms the RFC allows:
//   - delta-seconds: a bare integer like "45"  -> 45
//   - an HTTP-date:  "Wed, 21 Oct 2025 07:28:00 GMT" -> ceil((date - now)/1000), min 0
// `nowMs` (default Date.now()) is injectable so the HTTP-date branch is deterministic
// under test.
function oskParseRetryAfter(headerValue, nowMs) {
  if (headerValue == null) { return null; }
  var raw = String(headerValue).trim();
  if (raw === "") { return null; }
  // delta-seconds form: all digits.
  if (/^\d+$/.test(raw)) {
    return parseInt(raw, 10);
  }
  // HTTP-date form.
  var when = Date.parse(raw);
  if (isNaN(when)) { return null; }
  var base = typeof nowMs === "number" ? nowMs : Date.now();
  return Math.max(0, Math.ceil((when - base) / 1000));
}

// Format a whole number of seconds into a short human string for the cooldown line:
//   5   -> "5s"
//   65  -> "1m 5s"
//   120 -> "2m"
function oskFormatCooldown(seconds) {
  var total = Math.max(0, Math.ceil(Number(seconds) || 0));
  if (total < 60) { return total + "s"; }
  var mins = Math.floor(total / 60);
  var secs = total % 60;
  return secs === 0 ? mins + "m" : mins + "m " + secs + "s";
}

// Map a resend HTTP outcome to a UI RESULT descriptor the controller applies to the DOM.
// Pure — no DOM, no timers — so every branch (202/200/429/401/503/422/other/no-session)
// is asserted directly in the Node simulation.
//
// input = {
//   status:            number,       // HTTP status (0/undefined for a thrown fetch)
//   retryAfterSeconds: number|null,  // parsed Retry-After (oskParseRetryAfter), if any
//   body:              object|null,  // parsed JSON body ({email,emailVerified,verificationSent})
//   noSession:         boolean,      // true when there was no ID token to send (signed out)
//   networkError:      boolean,      // true when fetch itself threw
// }
// defaultCooldownSeconds — the post-202 courtesy cooldown (see the constant above).
//
// returns {
//   tone:          "ok" | "warn" | "error",   // drives the status line colour
//   statusMessage: string,                    // the line shown under the buttons
//   disableResend: boolean,                    // start a cooldown that disables Resend?
//   cooldownSeconds: number,                   // how long (when disableResend)
//   countdown:     boolean,                    // tick the remaining time in the status line?
//   verified:      boolean,                    // did the server report the caller verified?
//   hideBanner:    boolean,                    // hide the whole banner (verified / nothing to verify)?
//   sent:          boolean,                    // was a fresh link dispatched?
// }
function oskInterpretResendResponse(input, defaultCooldownSeconds) {
  var i = input || {};
  var body = i.body || {};
  var defaultCooldown =
    typeof defaultCooldownSeconds === "number"
      ? defaultCooldownSeconds
      : OSK_VERIFY_DEFAULT_COOLDOWN_SECONDS;

  // No ID token to send (signed out between render and click). Ask them to sign in.
  if (i.noSession) {
    return {
      tone: "error",
      statusMessage: "You appear to be signed out. Please sign in again.",
      disableResend: false,
      cooldownSeconds: 0,
      countdown: false,
      verified: false,
      hideBanner: false,
      sent: false,
    };
  }

  // fetch itself threw (offline / CORS / backend down). Degrade to a clear, retryable line.
  if (i.networkError) {
    return {
      tone: "error",
      statusMessage: "Couldn't reach the server to send the email. Please try again.",
      disableResend: false,
      cooldownSeconds: 0,
      countdown: false,
      verified: false,
      hideBanner: false,
      sent: false,
    };
  }

  // The server AUTHORITATIVELY reports the caller is already verified (the 200 path,
  // where verificationSent === false). Reflect it: hide the banner, nothing to send.
  if (body.emailVerified === true) {
    return {
      tone: "ok",
      statusMessage: "Your email is already verified.",
      disableResend: false,
      cooldownSeconds: 0,
      countdown: false,
      verified: true,
      hideBanner: true,
      sent: false,
    };
  }

  switch (i.status) {
    // 202 Accepted — a fresh verification link was dispatched. Confirm + apply the
    // courtesy cooldown (no countdown text: keep the reassuring "sent" line visible).
    case 202:
      return {
        tone: "ok",
        statusMessage: "Verification email sent — check your inbox (and your spam folder).",
        disableResend: true,
        cooldownSeconds: defaultCooldown,
        countdown: false,
        verified: false,
        hideBanner: false,
        sent: true,
      };

    // 200 OK without an explicit verified body — treat as accepted; apply the cooldown.
    case 200:
      return {
        tone: "ok",
        statusMessage: "Request received.",
        disableResend: true,
        cooldownSeconds: defaultCooldown,
        countdown: false,
        verified: false,
        hideBanner: false,
        sent: false,
      };

    // 429 Too Many Requests — over the per-user cooldown. Show the remaining time and
    // keep Resend disabled until it elapses (countdown ticks the status line down).
    case 429:
      return {
        tone: "warn",
        statusMessage:
          "Please wait " +
          oskFormatCooldown(i.retryAfterSeconds || defaultCooldown) +
          " before resending.",
        disableResend: true,
        cooldownSeconds: i.retryAfterSeconds || defaultCooldown,
        countdown: true,
        verified: false,
        hideBanner: false,
        sent: false,
      };

    // 401 Unauthorized — token rejected/expired. Handle gracefully: ask them to re-auth.
    case 401:
      return {
        tone: "error",
        statusMessage: "Your session isn't authorized (401). Please sign out and back in.",
        disableResend: false,
        cooldownSeconds: 0,
        countdown: false,
        verified: false,
        hideBanner: false,
        sent: false,
      };

    // 503 Service Unavailable — the email dependency is temporarily down (Retry-After: 5).
    case 503:
      return {
        tone: "warn",
        statusMessage: "Email verification is temporarily unavailable. Please retry shortly.",
        disableResend: true,
        cooldownSeconds: i.retryAfterSeconds || 5,
        countdown: true,
        verified: false,
        hideBanner: false,
        sent: false,
      };

    // 422 Unprocessable — no email on this account to verify (e.g. phone-only sign-in).
    // Nothing the user can do here, so hide the banner rather than nag.
    case 422:
      return {
        tone: "error",
        statusMessage: "This account has no email address to verify.",
        disableResend: false,
        cooldownSeconds: 0,
        countdown: false,
        verified: false,
        hideBanner: true,
        sent: false,
      };

    // Anything else (500s, unexpected) — a clear, retryable line with the code.
    default:
      return {
        tone: "error",
        statusMessage:
          "Couldn't send the verification email (HTTP " +
          (i.status || "?") +
          "). Please try again.",
        disableResend: false,
        cooldownSeconds: 0,
        countdown: false,
        verified: false,
        hideBanner: false,
        sent: false,
      };
  }
}

// ===========================================================================
// CONTROLLER FACTORY
// Builds the banner controller from INJECTED dependencies so the exact same code
// runs in the browser (real DOM/OSKAuth/fetch/timers) and under Node (fakes). It
// only ever touches the duck-typed surface of what it is given:
//   els.*        — `.hidden`, `.textContent`, `.disabled`, `.className` (all optional)
//   auth         — `.getState()`, `.getIdToken(force)` (OSKAuth subset)
//   fetch        — a fetch-like (method/headers) returning { status, headers.get, json() }
//   apiBaseUrl   — backend base URL (no trailing slash needed; trimmed here)
//   now          — () => ms epoch (default Date.now)  [test seam]
//   setInterval / clearInterval — timer fns (default globals) [test seam]
//   defaultCooldownSeconds — post-202 courtesy cooldown (default 60)
// ===========================================================================
function oskCreateVerifyEmailController(deps) {
  var d = deps || {};
  var els = d.els || {};
  var auth = d.auth || null;
  var fetchFn = d.fetch || (typeof fetch !== "undefined" ? fetch : null);
  var apiBaseUrl = (d.apiBaseUrl || "").replace(/\/+$/, "");
  var now = d.now || function () { return Date.now(); };
  var setIntervalFn = d.setInterval || (typeof setInterval !== "undefined" ? setInterval : function () {});
  var clearIntervalFn = d.clearInterval || (typeof clearInterval !== "undefined" ? clearInterval : function () {});
  var defaultCooldown =
    typeof d.defaultCooldownSeconds === "number" ? d.defaultCooldownSeconds : OSK_VERIFY_DEFAULT_COOLDOWN_SECONDS;

  // Cooldown bookkeeping. `cooldownEnd` is an absolute ms deadline (so a re-render
  // recomputes the remaining time correctly); `intervalId` ticks the countdown line.
  var cooldownEnd = 0;
  var countdownVisible = false;
  var intervalId = null;

  // --- tiny XSS-safe DOM helpers (all guard missing elements) ----------------

  function setStatus(text, tone) {
    var el = els.statusEl;
    if (!el) { return; }
    el.textContent = text || "";
    el.hidden = !text;
    // Tone class drives the colour (ok/warn/error). Guard: fake test els may lack
    // className, and we must not clobber it with "undefined".
    if (typeof el.className === "string") {
      el.className = "verify-banner__status" + (tone ? " verify-banner__status--" + tone : "");
    }
  }

  function setResendDisabled(disabled) {
    if (els.resendBtn) { els.resendBtn.disabled = !!disabled; }
  }

  function setRefreshDisabled(disabled) {
    if (els.refreshBtn) { els.refreshBtn.disabled = !!disabled; }
  }

  // --- cooldown -------------------------------------------------------------

  function cooldownRemaining() {
    return Math.max(0, Math.ceil((cooldownEnd - now()) / 1000));
  }

  function clearCooldown() {
    cooldownEnd = 0;
    countdownVisible = false;
    if (intervalId) {
      clearIntervalFn(intervalId);
      intervalId = null;
    }
  }

  // Re-evaluate the cooldown against the clock: disable/enable Resend, and (only when a
  // countdown line was requested — the 429/503 paths) update the remaining-time text.
  // Called once per second by the interval AND once immediately when a cooldown starts.
  function syncCooldown() {
    var rem = cooldownRemaining();
    setResendDisabled(rem > 0);
    if (rem > 0) {
      if (countdownVisible) {
        setStatus("Please wait " + oskFormatCooldown(rem) + " before resending.", "warn");
      }
    } else {
      // Elapsed: stop ticking and re-enable. For a countdown line, replace it with a
      // gentle "you can resend now"; for the silent (post-202) cooldown, leave the
      // reassuring "sent" message untouched.
      if (intervalId) { clearIntervalFn(intervalId); intervalId = null; }
      if (countdownVisible) { setStatus("You can resend now.", "ok"); }
      countdownVisible = false;
    }
  }

  // Begin a cooldown of `seconds`. `opts.countdown` shows a live remaining-time line
  // (429/503); otherwise the button is simply disabled while the "sent" line stays.
  function startCooldown(seconds, opts) {
    var secs = Math.max(0, Math.ceil(Number(seconds) || 0));
    cooldownEnd = now() + secs * 1000;
    countdownVisible = !!(opts && opts.countdown);
    if (intervalId) { clearIntervalFn(intervalId); intervalId = null; }
    syncCooldown();
    if (secs > 0) {
      intervalId = setIntervalFn(syncCooldown, 1000);
    }
  }

  // --- render (show/hide from auth state) -----------------------------------

  function currentState() {
    return auth && typeof auth.getState === "function" ? auth.getState() : {};
  }

  // Show the banner iff signed-in + unverified (oskComputeVerifyView); otherwise hide it
  // and reset any transient status/cooldown so a later sign-in starts clean. Never nags
  // a verified user.
  function render() {
    var view = oskComputeVerifyView(currentState());
    if (els.banner) { els.banner.hidden = !view.show; }
    if (!view.show) {
      clearCooldown();
      setResendDisabled(false);
      setRefreshDisabled(false);
      setStatus("", null);
    }
    return view;
  }

  // --- resend ---------------------------------------------------------------

  // POST the resend request with a Bearer ID token, then apply the interpreted result.
  // Always resolves (never rejects) with the result descriptor, for easy assertion.
  function resend() {
    setResendDisabled(true);
    setStatus("Sending verification email…", "muted");

    var tokenPromise =
      auth && typeof auth.getIdToken === "function" ? auth.getIdToken() : Promise.resolve(null);

    return Promise.resolve(tokenPromise)
      .then(function (token) {
        if (!token) {
          // Signed out between render and click — no token to send.
          return { status: 0, retryAfterSeconds: null, body: null, noSession: true };
        }
        if (!fetchFn) {
          return { status: 0, retryAfterSeconds: null, body: null, networkError: true };
        }
        return fetchFn(apiBaseUrl + "/api/v1/me/email/resend-verification", {
          method: "POST",
          headers: {
            Authorization: "Bearer " + token,
            Accept: "application/json",
          },
          cache: "no-store",
        }).then(function (r) {
          var retryHeader = r && r.headers && typeof r.headers.get === "function" ? r.headers.get("Retry-After") : null;
          // The body may be JSON (200/202 ResendVerificationResponse, or problem+json
          // for the error statuses) or empty — never let a parse error break the flow.
          var jsonPromise = r && typeof r.json === "function" ? r.json() : Promise.resolve(null);
          return Promise.resolve(jsonPromise)
            .catch(function () { return null; })
            .then(function (body) {
              return {
                status: r.status,
                retryAfterSeconds: oskParseRetryAfter(retryHeader, now()),
                body: body,
              };
            });
        });
      })
      .catch(function () {
        return { status: 0, retryAfterSeconds: null, body: null, networkError: true };
      })
      .then(function (raw) {
        var result = oskInterpretResendResponse(raw, defaultCooldown);
        applyResendResult(result);
        return result;
      });
  }

  // Apply a resend RESULT to the DOM: status line, banner visibility, cooldown.
  function applyResendResult(result) {
    setStatus(result.statusMessage, result.tone);
    if (result.verified || result.hideBanner) {
      if (els.banner) { els.banner.hidden = true; }
      clearCooldown();
      setResendDisabled(false);
      return;
    }
    if (result.disableResend) {
      startCooldown(result.cooldownSeconds, { countdown: result.countdown });
    } else if (cooldownRemaining() <= 0) {
      // No cooldown and none pending — make sure Resend is usable again.
      setResendDisabled(false);
    }
  }

  // --- refresh ("I've verified — refresh") ----------------------------------

  // Reload the Firebase auth user (so its emailVerified flips once the link is clicked)
  // and force-refresh the ID token, then re-render. If now verified the banner hides;
  // otherwise a gentle "not verified yet" line is shown. Always resolves.
  function refresh() {
    setRefreshDisabled(true);
    setStatus("Checking your verification status…", "muted");

    var state = currentState();
    var user = state && state.user;
    var reloadPromise =
      user && typeof user.reload === "function" ? Promise.resolve(user.reload()) : Promise.resolve();

    return reloadPromise
      .catch(function () { /* reload failed — fall through and re-check anyway */ })
      .then(function () {
        // Force a fresh ID token so a newly-minted `email_verified` claim is picked up
        // (harmless if unavailable in a fake auth).
        if (auth && typeof auth.getIdToken === "function") {
          return Promise.resolve(auth.getIdToken(true)).catch(function () { return null; });
        }
        return null;
      })
      .then(function () {
        var view = render();
        setRefreshDisabled(false);
        if (view.show) {
          // Still signed-in + unverified.
          setStatus("Not verified yet. Check your inbox, then refresh again.", "warn");
        }
        // If verified, render() already hid the banner (and cleared the status).
        return { verified: view.verified, stillShowing: view.show };
      });
  }

  // The controller's public + test surface. `syncCooldown`/`cooldownRemaining` are
  // exposed as test seams so the simulation can advance a fake clock and assert the
  // Resend button re-enables when the cooldown elapses.
  return {
    render: render,
    resend: resend,
    refresh: refresh,
    syncCooldown: syncCooldown,
    cooldownRemaining: cooldownRemaining,
  };
}

// ---------------------------------------------------------------------------
// Node export. When this file is `require()`d (CommonJS), expose the pure helpers
// and the controller factory so the banner logic can be simulated without a browser.
// `typeof module` is "undefined" in a classic browser <script>, so this is a no-op there.
// ---------------------------------------------------------------------------
if (typeof module !== "undefined" && module.exports) {
  module.exports = {
    DEFAULT_COOLDOWN_SECONDS: OSK_VERIFY_DEFAULT_COOLDOWN_SECONDS,
    computeVerifyView: oskComputeVerifyView,
    parseRetryAfter: oskParseRetryAfter,
    formatCooldown: oskFormatCooldown,
    interpretResendResponse: oskInterpretResendResponse,
    createVerifyEmailController: oskCreateVerifyEmailController,
  };
}

// ===========================================================================
// BROWSER BOOTSTRAP
// Runs ONLY in a real browser (guarded by `typeof window`). It ensures the
// stylesheet is loaded, finds the shared mount block, wires the real OSKAuth +
// fetch + DOM into the factory above, and re-renders on every auth-state change.
// ===========================================================================
if (typeof window !== "undefined") {
  (function () {
    "use strict";

    // Idempotently load the banner's stylesheet (web/verify-email.css). Injecting it
    // here means a page only needs the shared mount block + this <script> — no per-page
    // <head> edit — and matches the config-driven, bundler-free house style.
    function ensureStylesheet() {
      try {
        if (document.querySelector('link[data-osk="verify-email"]')) { return; }
        var link = document.createElement("link");
        link.rel = "stylesheet";
        link.href = "/verify-email.css";
        link.setAttribute("data-osk", "verify-email");
        (document.head || document.documentElement).appendChild(link);
      } catch (e) {
        /* stylesheet is a progressive enhancement; the banner still functions unstyled */
      }
    }

    function boot() {
      ensureStylesheet();

      var mount = document.getElementById("verify-email-banner");
      // No mount on this page => nothing to do (the shared block wasn't injected here).
      if (!mount) { return; }

      var A = window.OSKAuth;
      // No auth module on this page (e.g. a public page that doesn't load auth.js): there
      // is no signed-in state to reflect, so keep the banner hidden and stop. This is why
      // the shared block is safe to inject site-wide — it only ever activates where a user
      // can actually be signed in.
      if (!A) { mount.hidden = true; return; }

      var cfg = window.__APP_CONFIG__ || {};
      var els = {
        banner: mount,
        msg: document.getElementById("verify-email-msg"),
        resendBtn: document.getElementById("verify-email-resend"),
        refreshBtn: document.getElementById("verify-email-refresh"),
        statusEl: document.getElementById("verify-email-status"),
      };

      var controller = oskCreateVerifyEmailController({
        els: els,
        auth: A,
        fetch: window.fetch ? window.fetch.bind(window) : null,
        apiBaseUrl: cfg.apiBaseUrl || "",
        setInterval: window.setInterval.bind(window),
        clearInterval: window.clearInterval.bind(window),
      });

      if (els.resendBtn) {
        els.resendBtn.addEventListener("click", function () { controller.resend(); });
      }
      if (els.refreshBtn) {
        els.refreshBtn.addEventListener("click", function () { controller.refresh(); });
      }

      // Paint immediately (hidden until auth settles), then on every auth-state change and
      // once auth has settled — covering the not-configured/error states that never emit
      // an auth event.
      controller.render();
      A.onAuthStateChanged(function () { controller.render(); });
      if (A.ready && typeof A.ready.then === "function") {
        A.ready.then(function () { controller.render(); }).catch(function () { controller.render(); });
      }
    }

    // Run after the DOM is parsed so the mount + buttons exist (this <script> may be in
    // the body foot, but guard for early execution just in case).
    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", boot);
    } else {
      boot();
    }
  })();
}
