// verify-email.simulation.cjs — headless Node simulation of the OSK-75 email-verification
// banner (web/verify-email.js).
//
// WHY this exists: live sign-in cannot be exercised in CI/locally without the human
// Firebase Web App apiKey (OSK-92) and firebase-scoped ADC (OSK-38). But the banner's
// DECISION logic is PURE (show/hide from an auth snapshot; interpret a resend HTTP
// response; parse Retry-After; format a cooldown) and its CONTROLLER is built from
// INJECTED dependencies — web/verify-email.js exports both for exactly this reason. This
// script requires them and drives them with a FAKE DOM + STUBBED fetch + a FAKE Firebase
// user + FAKE timers, asserting that:
//   - the banner shows ONLY when a signed-in user's email is unverified (never when
//     verified / signed-out / loading / not-configured),
//   - Resend calls POST /api/v1/me/email/resend-verification with an Authorization:
//     Bearer token, and on 202 shows "sent" + disables Resend for a cooldown,
//   - a 429 disables Resend and shows the Retry-After remaining, and the button
//     re-enables once the (fake) clock passes the cooldown,
//   - a freshly-verified user (reload flips emailVerified) hides the banner on refresh,
//   - a 401 is handled gracefully (clear message, Resend NOT stuck disabled).
//
// It is a plain assertion harness (no framework, no deps): it prints each check and exits
// non-zero on the first-round failures, so it doubles as a CI-friendly gate. It is a
// `.cjs` file OUTSIDE web/e2e/tests/, so Playwright (testDir ./tests) never picks it up.
//
// Run:  node web/e2e/verify-email.simulation.cjs

"use strict";

const path = require("node:path");
// Require the REAL module under test. verify-email.js is a classic browser script that
// also exports its pure helpers + controller factory when required from Node (its browser
// bootstrap is skipped because `window` is undefined here). So this asserts the SAME code
// the page runs.
const V = require(path.resolve(__dirname, "..", "verify-email.js"));

let passed = 0;
let failed = 0;

function assert(label, cond) {
  if (cond) {
    passed++;
    console.log("  ok  - " + label);
  } else {
    failed++;
    console.log("  FAIL- " + label);
  }
}
function assertEqual(label, actual, expected) {
  assert(
    label + " (expected " + JSON.stringify(expected) + ", got " + JSON.stringify(actual) + ")",
    actual === expected,
  );
}
function assertMatch(label, actual, re) {
  assert(label + ' (got "' + actual + '")', typeof actual === "string" && re.test(actual));
}

// ---------------------------------------------------------------------------
// Fakes: a duck-typed DOM, an OSKAuth-like auth, a stubbed fetch, and a fake clock.
// ---------------------------------------------------------------------------

// Fake DOM elements the controller touches (.hidden/.textContent/.disabled/.className).
function fakeEls() {
  return {
    banner: { hidden: true },
    msg: { textContent: "" },
    resendBtn: { disabled: false },
    refreshBtn: { disabled: false },
    statusEl: { textContent: "", hidden: true, className: "verify-banner__status" },
  };
}

// An OSKAuth-like object over a single (mutable) user. getIdToken resolves a token when
// signed in, null when signed out — exactly what the controller checks.
function fakeAuth(user) {
  return {
    _user: user,
    getState: function () {
      return { configured: true, ready: true, error: null, user: this._user };
    },
    getIdToken: function () {
      return Promise.resolve(this._user ? "tok-" + (this._user.uid || "x") : null);
    },
  };
}

// A stubbed fetch that records calls and replays a queue of response specs
// ({ status, headers, body }). The returned Response exposes headers.get() (case-
// insensitive) and json().
function makeFetch(responses) {
  const calls = [];
  let idx = 0;
  const fn = function (url, opts) {
    calls.push({ url: url, opts: opts });
    const spec = responses[Math.min(idx, responses.length - 1)] || {};
    idx++;
    return Promise.resolve({
      status: spec.status,
      headers: {
        get: function (name) {
          const want = String(name).toLowerCase();
          const h = spec.headers || {};
          const keys = Object.keys(h);
          for (let i = 0; i < keys.length; i++) {
            if (keys[i].toLowerCase() === want) { return h[keys[i]]; }
          }
          return null;
        },
      },
      json: function () {
        return Object.prototype.hasOwnProperty.call(spec, "body")
          ? Promise.resolve(spec.body)
          : Promise.reject(new Error("no json body"));
      },
    });
  };
  fn.calls = calls;
  return fn;
}

// A controllable clock + interval so cooldown timing is deterministic. The interval
// callback is captured (not auto-fired); the test advances `now` and calls the captured
// callback (or controller.syncCooldown) to simulate ticks/expiry.
function fakeClock() {
  const c = { now: 1000000, cb: null };
  c.nowFn = function () { return c.now; };
  c.setInterval = function (cb) { c.cb = cb; return 1; };
  c.clearInterval = function () { c.cb = null; };
  c.advance = function (ms) { c.now += ms; };
  return c;
}

function makeController(auth, fetchFn, clock, extra) {
  const els = fakeEls();
  const controller = V.createVerifyEmailController(
    Object.assign(
      {
        els: els,
        auth: auth,
        fetch: fetchFn,
        apiBaseUrl: "http://api.test",
        now: clock.nowFn,
        setInterval: clock.setInterval,
        clearInterval: clock.clearInterval,
      },
      extra || {},
    ),
  );
  return { els: els, controller: controller };
}

// ===========================================================================
// 1) computeVerifyView — pure show/hide decision.
// ===========================================================================
async function run() {
  console.log("\ncomputeVerifyView():");
  assertEqual(
    "signed-in + UNVERIFIED => show",
    V.computeVerifyView({ configured: true, ready: true, user: { uid: "u", emailVerified: false } }).show,
    true,
  );
  assertEqual(
    "signed-in + VERIFIED => hide (never nag)",
    V.computeVerifyView({ configured: true, ready: true, user: { uid: "u", emailVerified: true } }).show,
    false,
  );
  assertEqual(
    "signed-OUT => hide",
    V.computeVerifyView({ configured: true, ready: true, user: null }).show,
    false,
  );
  assertEqual(
    "LOADING (not ready) => hide",
    V.computeVerifyView({ configured: true, ready: false, user: { emailVerified: false } }).show,
    false,
  );
  assertEqual(
    "NOT CONFIGURED => hide",
    V.computeVerifyView({ configured: false, ready: true, user: { emailVerified: false } }).show,
    false,
  );
  assertEqual(
    "ERROR state => hide",
    V.computeVerifyView({ configured: true, ready: true, error: "boom", user: { emailVerified: false } }).show,
    false,
  );

  // ===========================================================================
  // 2) parseRetryAfter + formatCooldown — pure helpers.
  // ===========================================================================
  console.log("\nparseRetryAfter():");
  assertEqual("delta-seconds integer", V.parseRetryAfter("45"), 45);
  assertEqual("missing header => null", V.parseRetryAfter(null), null);
  assertEqual("garbage => null", V.parseRetryAfter("soon"), null);
  {
    const now = Date.parse("2025-01-01T00:00:00Z");
    const http = "Thu, 01 Jan 2025 00:00:30 GMT";
    assertEqual("HTTP-date => seconds remaining", V.parseRetryAfter(http, now), 30);
    assertEqual("past HTTP-date => 0 (never negative)", V.parseRetryAfter("Thu, 01 Jan 2025 00:00:00 GMT", now + 5000), 0);
  }

  console.log("\nformatCooldown():");
  assertEqual("5 => 5s", V.formatCooldown(5), "5s");
  assertEqual("65 => 1m 5s", V.formatCooldown(65), "1m 5s");
  assertEqual("120 => 2m", V.formatCooldown(120), "2m");

  // ===========================================================================
  // 3) interpretResendResponse — one branch per HTTP outcome.
  // ===========================================================================
  console.log("\ninterpretResendResponse():");
  {
    const r = V.interpretResendResponse({ status: 202, body: { emailVerified: false, verificationSent: true } });
    assertEqual("202 disables Resend", r.disableResend, true);
    assertEqual("202 no live countdown", r.countdown, false);
    assertMatch("202 says sent", r.statusMessage, /sent/i);
  }
  {
    const r = V.interpretResendResponse({ status: 200, body: { emailVerified: true, verificationSent: false } });
    assertEqual("already-verified => verified", r.verified, true);
    assertEqual("already-verified => hide banner", r.hideBanner, true);
  }
  {
    const r = V.interpretResendResponse({ status: 429, retryAfterSeconds: 45 });
    assertEqual("429 disables Resend", r.disableResend, true);
    assertEqual("429 shows live countdown", r.countdown, true);
    assertEqual("429 cooldown = Retry-After", r.cooldownSeconds, 45);
    assertMatch("429 message shows remaining", r.statusMessage, /45s/);
  }
  {
    const r = V.interpretResendResponse({ status: 401 });
    assertEqual("401 does NOT disable Resend", r.disableResend, false);
    assertMatch("401 mentions 401", r.statusMessage, /401/);
  }
  {
    const r = V.interpretResendResponse({ status: 503, retryAfterSeconds: 5 });
    assertEqual("503 disables Resend (temporary)", r.disableResend, true);
    assertMatch("503 says unavailable", r.statusMessage, /unavailable/i);
  }
  {
    const r = V.interpretResendResponse({ status: 422 });
    assertEqual("422 hides banner (nothing to verify)", r.hideBanner, true);
  }
  {
    const r = V.interpretResendResponse({ noSession: true });
    assertMatch("no-session asks to sign in", r.statusMessage, /sign in/i);
  }
  {
    const r = V.interpretResendResponse({ networkError: true });
    assertMatch("network error is retryable", r.statusMessage, /try again/i);
  }

  // ===========================================================================
  // 4) Controller render — banner shows ONLY when signed-in + unverified.
  // ===========================================================================
  console.log("\ncontroller.render() — visibility from auth state:");
  {
    const clock = fakeClock();
    const { els, controller } = makeController(fakeAuth({ uid: "u", emailVerified: false }), makeFetch([]), clock);
    controller.render();
    assertEqual("unverified => banner visible", els.banner.hidden, false);
  }
  {
    const clock = fakeClock();
    const { els, controller } = makeController(fakeAuth({ uid: "u", emailVerified: true }), makeFetch([]), clock);
    controller.render();
    assertEqual("verified => banner hidden", els.banner.hidden, true);
  }
  {
    const clock = fakeClock();
    const { els, controller } = makeController(fakeAuth(null), makeFetch([]), clock);
    controller.render();
    assertEqual("signed-out => banner hidden", els.banner.hidden, true);
  }

  // ===========================================================================
  // 5) Resend — happy path (202): calls the endpoint with a Bearer token, confirms
  //    "sent", disables Resend for the cooldown.
  // ===========================================================================
  console.log("\ncontroller.resend() — 202 Accepted:");
  {
    const clock = fakeClock();
    const fetchFn = makeFetch([{ status: 202, headers: {}, body: { email: "u@x.io", emailVerified: false, verificationSent: true } }]);
    const { els, controller } = makeController(fakeAuth({ uid: "u1", emailVerified: false }), fetchFn, clock);
    controller.render();
    const result = await controller.resend();

    assertEqual("exactly one request made", fetchFn.calls.length, 1);
    assertMatch("POSTs the resend endpoint", fetchFn.calls[0].url, /\/api\/v1\/me\/email\/resend-verification$/);
    assertEqual("uses POST", fetchFn.calls[0].opts.method, "POST");
    assertMatch("sends Bearer token", fetchFn.calls[0].opts.headers.Authorization, /^Bearer tok-u1$/);
    assertEqual("result marked sent", result.sent, true);
    assertEqual("Resend disabled during cooldown", els.resendBtn.disabled, true);
    assertMatch("status confirms sent", els.statusEl.textContent, /sent/i);
    assertEqual("banner still visible (still unverified)", els.banner.hidden, false);

    // Cooldown elapses -> Resend re-enables.
    clock.advance(V.DEFAULT_COOLDOWN_SECONDS * 1000 + 1000);
    controller.syncCooldown();
    assertEqual("Resend re-enabled after cooldown", els.resendBtn.disabled, false);
  }

  // ===========================================================================
  // 6) Resend — 429 over cooldown: disabled + shows remaining; re-enables after the
  //    (fake) clock passes the Retry-After window.
  // ===========================================================================
  console.log("\ncontroller.resend() — 429 Too Many Requests (Retry-After):");
  {
    const clock = fakeClock();
    const fetchFn = makeFetch([{ status: 429, headers: { "Retry-After": "45" }, body: { title: "Too Many Requests" } }]);
    const { els, controller } = makeController(fakeAuth({ uid: "u2", emailVerified: false }), fetchFn, clock);
    controller.render();
    await controller.resend();

    assertEqual("Resend disabled on 429", els.resendBtn.disabled, true);
    assertMatch("status shows the remaining time", els.statusEl.textContent, /45s/);

    // Tick 30s -> still disabled, remaining updates.
    clock.advance(30000);
    controller.syncCooldown();
    assertEqual("still disabled mid-cooldown", els.resendBtn.disabled, true);
    assertMatch("remaining counts down", els.statusEl.textContent, /15s/);

    // Pass the window -> re-enabled.
    clock.advance(20000);
    controller.syncCooldown();
    assertEqual("Resend re-enabled after Retry-After", els.resendBtn.disabled, false);
  }

  // ===========================================================================
  // 7) Resend — 401 Unauthorized: graceful message, Resend NOT stuck disabled.
  // ===========================================================================
  console.log("\ncontroller.resend() — 401 Unauthorized:");
  {
    const clock = fakeClock();
    const fetchFn = makeFetch([{ status: 401, headers: {}, body: { title: "Unauthorized" } }]);
    const { els, controller } = makeController(fakeAuth({ uid: "u3", emailVerified: false }), fetchFn, clock);
    controller.render();
    await controller.resend();

    assertMatch("401 surfaces a clear message", els.statusEl.textContent, /401/);
    assertEqual("Resend NOT left disabled after 401", els.resendBtn.disabled, false);
    assertEqual("banner stays visible", els.banner.hidden, false);
  }

  // ===========================================================================
  // 8) Refresh — a freshly-verified user (reload flips emailVerified) hides the banner.
  // ===========================================================================
  console.log("\ncontroller.refresh() — reflect newly-verified state:");
  {
    const clock = fakeClock();
    const user = {
      uid: "u4",
      emailVerified: false,
      reload: function () { this.emailVerified = true; return Promise.resolve(); },
    };
    const auth = fakeAuth(user);
    auth.getIdToken = function () { return Promise.resolve("tok-u4"); }; // force-refresh path is harmless
    const { els, controller } = makeController(auth, makeFetch([]), clock);
    controller.render();
    assertEqual("initially visible (unverified)", els.banner.hidden, false);

    const res = await controller.refresh();
    assertEqual("refresh reports verified", res.verified, true);
    assertEqual("banner hidden after verifying", els.banner.hidden, true);
    assertEqual("refresh button re-enabled", els.refreshBtn.disabled, false);
  }

  // ===========================================================================
  // 9) Refresh — still unverified: banner stays, gentle "not verified yet" line.
  // ===========================================================================
  console.log("\ncontroller.refresh() — still unverified:");
  {
    const clock = fakeClock();
    const user = { uid: "u5", emailVerified: false, reload: function () { return Promise.resolve(); } };
    const { els, controller } = makeController(fakeAuth(user), makeFetch([]), clock);
    controller.render();
    const res = await controller.refresh();
    assertEqual("still not verified", res.verified, false);
    assertEqual("banner still visible", els.banner.hidden, false);
    assertMatch("shows not-verified-yet line", els.statusEl.textContent, /not verified/i);
  }

  // -------------------------------------------------------------------------
  console.log("\n----------------------------------------");
  console.log("verify-email simulation: " + passed + " passed, " + failed + " failed");
  if (failed > 0) {
    process.exitCode = 1;
  }
}

run().catch(function (err) {
  console.error("simulation crashed:", err && err.stack ? err.stack : err);
  process.exitCode = 1;
});
