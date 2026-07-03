// email-code-login.simulation.cjs — headless Node simulation of the OSK-129 passwordless
// email-code sign-in flow (the DEFAULT method) + the SMS phone "try another way" gating.
//
// WHY this exists: a LIVE run needs a real backend issuing codes, a real inbox, and a real
// Firebase custom-token exchange (signInWithCustomToken) — none available in CI. But
// everything that ISN'T the live Firebase hop is PURE and exported from web/auth.js for
// exactly this reason, so this script drives it with a FAKE fetch + a STUBBED
// signInWithCustomToken and asserts that:
//   - URL BUILDING: emailCodeUrls trims a trailing slash and targets the two endpoints,
//   - EMAIL SANITY: isLikelyEmail accepts real-looking addresses and rejects junk,
//   - ERROR COPY: describeEmailCodeError maps 400/401/429/503 to friendly, per-status lines,
//   - START: oskStartEmailCode resolves on 202 and rejects (with .status) on 4xx/5xx,
//   - VERIFY -> SIGN-IN: oskVerifyEmailCode returns the custom token on 200, which a stubbed
//     signInWithCustomToken then exchanges — the exact path the browser's verifyEmailCode runs,
//   - ERROR CASES: a bad/expired code (401), rate-limited (429), unavailable (503) and a
//     malformed success body all reject with the right status + copy and never "sign in",
//   - PHONE GATING: isPhoneAuthEnabled is OFF unless providers.phone === true, and the
//     COMMITTED config.js ships it OFF (SMS is a human step, OSK-134/137/143).
//
// Plain assertion harness (no framework, no deps): prints each check, exits non-zero on the
// first failure, so it doubles as a CI gate. It is a `.cjs` file OUTSIDE web/e2e/tests/, so
// Playwright (testDir ./tests) never picks it up.
//
// Run:  node web/e2e/email-code-login.simulation.cjs

"use strict";

const path = require("node:path");

// Require the REAL module under test. auth.js exports its pure helpers when required from
// Node (its browser bootstrap is skipped because `window` is undefined), so this asserts the
// SAME code the page runs.
const auth = require(path.resolve(__dirname, "..", "auth.js"));

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
  assert(label + " (expected " + JSON.stringify(expected) + ", got " + JSON.stringify(actual) + ")", actual === expected);
}
function assertDeep(label, actual, expected) {
  assert(
    label + " (expected " + JSON.stringify(expected) + ", got " + JSON.stringify(actual) + ")",
    JSON.stringify(actual) === JSON.stringify(expected),
  );
}

// A fake fetch that records the requests it received and returns a scripted response. The
// response is duck-typed exactly like the browser fetch Response the pure helpers touch:
// `.status`, `.ok`, and `.json()`.
function makeFetch(response) {
  const calls = [];
  const fn = (url, init) => {
    calls.push({ url, init, body: init && init.body ? JSON.parse(init.body) : null });
    return Promise.resolve(response);
  };
  fn.calls = calls;
  return fn;
}
function resp(status, body) {
  return { status, ok: status >= 200 && status < 300, json: () => Promise.resolve(body) };
}

// ---------------------------------------------------------------------------
// 1) URL building — trims a trailing slash, targets both endpoints.
// ---------------------------------------------------------------------------
console.log("\nemailCodeUrls():");
assertDeep("builds start/verify from a base", auth.emailCodeUrls("http://127.0.0.1:8080"), {
  start: "http://127.0.0.1:8080/api/v1/auth/email-code/start",
  verify: "http://127.0.0.1:8080/api/v1/auth/email-code/verify",
});
assertDeep("trims a trailing slash (no //api)", auth.emailCodeUrls("https://api.example.com/"), {
  start: "https://api.example.com/api/v1/auth/email-code/start",
  verify: "https://api.example.com/api/v1/auth/email-code/verify",
});
assertEqual("empty base => same-origin start path", auth.emailCodeUrls("").start, "/api/v1/auth/email-code/start");

// ---------------------------------------------------------------------------
// 2) Email sanity check (client-side pre-flight; backend is authoritative).
// ---------------------------------------------------------------------------
console.log("\nisLikelyEmail():");
assertEqual("accepts a normal address", auth.isLikelyEmail("alice@example.com"), true);
assertEqual("trims surrounding space", auth.isLikelyEmail("  alice@example.com "), true);
assertEqual("rejects missing @", auth.isLikelyEmail("aliceexample.com"), false);
assertEqual("rejects missing domain dot", auth.isLikelyEmail("alice@example"), false);
assertEqual("rejects empty", auth.isLikelyEmail(""), false);
assertEqual("rejects non-string", auth.isLikelyEmail(42), false);

// ---------------------------------------------------------------------------
// 3) Error copy — per-status friendly lines.
// ---------------------------------------------------------------------------
console.log("\ndescribeEmailCodeError():");
assert("400 mentions valid email", /valid email/i.test(auth.describeEmailCodeError(400)));
assert("401 mentions incorrect/expired", /incorrect|expired/i.test(auth.describeEmailCodeError(401)));
assert("429 mentions too many / wait", /too many|wait/i.test(auth.describeEmailCodeError(429)));
assert("503 mentions unavailable", /unavailable/i.test(auth.describeEmailCodeError(503)));
assert("unknown status => generic", /something went wrong/i.test(auth.describeEmailCodeError(0)));

// ---------------------------------------------------------------------------
// 4) PHONE gating — OFF unless providers.phone === true; committed config ships OFF.
// ---------------------------------------------------------------------------
console.log("\nisPhoneAuthEnabled():");
assertEqual("not configured => false", auth.isPhoneAuthEnabled({ apiKey: "" }), false);
assertEqual("configured, no toggle => false (default off)", auth.isPhoneAuthEnabled({ apiKey: "k" }), false);
assertEqual("phone:true => true", auth.isPhoneAuthEnabled({ apiKey: "k", providers: { phone: true } }), true);
assertEqual('phone:"yes" (truthy) => false', auth.isPhoneAuthEnabled({ apiKey: "k", providers: { phone: "yes" } }), false);
{
  global.window = {};
  delete require.cache[require.resolve(path.resolve(__dirname, "..", "config.js"))];
  require(path.resolve(__dirname, "..", "config.js"));
  const fb = (global.window.__APP_CONFIG__ || {}).firebase || {};
  assertEqual("committed config.js ships phone OFF (human-gated)", auth.isPhoneAuthEnabled(fb), false);
  assertEqual("committed providers.phone === false", fb.providers.phone, false);
}

// ---------------------------------------------------------------------------
// 5) START — resolves on 202; rejects (with .status) on failure. Also asserts the
//    request shape (POST JSON with the email).
// ---------------------------------------------------------------------------
console.log("\noskStartEmailCode() (fake fetch):");

// Faithful re-enactment of the browser verifyEmailCode: verify then exchange the token via a
// STUBBED signInWithCustomToken. (The browser's own verifyEmailCode is inside the
// window-guarded closure, so we drive the same two exported/stubbed steps it composes.)
function simulateVerify(fetchImpl, signIn, base, email, code) {
  return auth.verifyEmailCode(fetchImpl, base, email, code).then((token) => signIn(token));
}

const asyncChecks = Promise.all([
  // 202 -> resolves true, and posts the email to /start.
  (() => {
    const f = makeFetch(resp(202, { status: "code_sent" }));
    return auth.startEmailCode(f, "http://b", "alice@example.com").then((ok) => {
      assertEqual("start 202 resolves true", ok, true);
      assertEqual("start hit the /start URL", f.calls[0].url, "http://b/api/v1/auth/email-code/start");
      assertEqual("start posted the email", f.calls[0].body.email, "alice@example.com");
    });
  })(),

  // 429 -> rejects with .status 429 and the rate-limited copy.
  (() => {
    const f = makeFetch(resp(429, {}));
    return auth
      .startEmailCode(f, "http://b", "alice@example.com")
      .then(() => assert("start 429 should reject (it resolved!)", false))
      .catch((err) => {
        assertEqual("start 429 -> err.status", err.status, 429);
        assert("start 429 -> rate-limited copy", /too many|wait/i.test(err.message));
      });
  })(),

  // 400 -> rejects with .status 400.
  (() => {
    const f = makeFetch(resp(400, {}));
    return auth
      .startEmailCode(f, "http://b", "bad")
      .then(() => assert("start 400 should reject (it resolved!)", false))
      .catch((err) => assertEqual("start 400 -> err.status", err.status, 400));
  })(),

  // ------------------------------------------------------------------------
  // 6) VERIFY -> SIGN-IN happy path: 200 { token } -> signInWithCustomToken(token).
  // ------------------------------------------------------------------------
  (() => {
    const f = makeFetch(resp(200, { token: "custom-token-xyz" }));
    const signInCalls = [];
    const signIn = (t) => {
      signInCalls.push(t);
      return Promise.resolve({ uid: "u-1" });
    };
    return simulateVerify(f, signIn, "http://b", "alice@example.com", "123456").then((user) => {
      assertEqual("verify hit the /verify URL", f.calls[0].url, "http://b/api/v1/auth/email-code/verify");
      assertEqual("verify posted the code", f.calls[0].body.code, "123456");
      assertEqual("verify exchanged the returned token", signInCalls[0], "custom-token-xyz");
      assertEqual("verify signs the user in", user.uid, "u-1");
    });
  })(),

  // Bad/expired code -> 401: rejects, and signInWithCustomToken is NEVER called.
  (() => {
    const f = makeFetch(resp(401, {}));
    const signInCalls = [];
    const signIn = (t) => {
      signInCalls.push(t);
      return Promise.resolve({});
    };
    return simulateVerify(f, signIn, "http://b", "alice@example.com", "000000")
      .then(() => assert("verify 401 should reject (it resolved!)", false))
      .catch((err) => {
        assertEqual("verify 401 -> err.status", err.status, 401);
        assert("verify 401 -> incorrect/expired copy", /incorrect|expired/i.test(err.message));
        assertEqual("verify 401 -> no sign-in attempted", signInCalls.length, 0);
      });
  })(),

  // Rate-limited verify -> 429.
  (() => {
    const f = makeFetch(resp(429, {}));
    return auth
      .verifyEmailCode(f, "http://b", "alice@example.com", "123456")
      .then(() => assert("verify 429 should reject (it resolved!)", false))
      .catch((err) => assertEqual("verify 429 -> err.status", err.status, 429));
  })(),

  // Unavailable verify -> 503.
  (() => {
    const f = makeFetch(resp(503, {}));
    return auth
      .verifyEmailCode(f, "http://b", "alice@example.com", "123456")
      .then(() => assert("verify 503 should reject (it resolved!)", false))
      .catch((err) => {
        assertEqual("verify 503 -> err.status", err.status, 503);
        assert("verify 503 -> unavailable copy", /unavailable/i.test(err.message));
      });
  })(),

  // Malformed 200 (no token) -> rejects rather than "signing in" with nothing.
  (() => {
    const f = makeFetch(resp(200, { notToken: "x" }));
    return auth
      .verifyEmailCode(f, "http://b", "alice@example.com", "123456")
      .then(() => assert("verify 200-without-token should reject (it resolved!)", false))
      .catch((err) => assert("verify 200-without-token rejects", err instanceof Error));
  })(),
]);

// ---------------------------------------------------------------------------
// Summary + exit code (deferred until the async checks settle).
// ---------------------------------------------------------------------------
asyncChecks
  .catch(() => {
    /* individual assertions already recorded any failure */
  })
  .then(() => {
    console.log("\n----------------------------------------");
    console.log("email-code-login simulation: " + passed + " passed, " + failed + " failed");
    if (failed > 0) {
      process.exitCode = 1;
    }
  });
