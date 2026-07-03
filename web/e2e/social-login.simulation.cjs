// social-login.simulation.cjs — headless Node simulation of the OSK-131 additional
// federated / social logins (Google, Facebook, Apple, …).
//
// WHY this exists: a LIVE run of Facebook/Apple sign-in is impossible in CI — it needs
// each provider ENABLED in the Firebase console (client IDs/secrets, a HUMAN step) plus a
// real popup + OAuth round-trip. But everything that ISN'T the live OAuth hop is PURE and
// exported from web/auth.js for exactly this reason, so this script drives it with a
// FAKE DOM + a STUBBED Firebase namespace and asserts that:
//   - CONFIG-GATING: enabledProviders / isProviderEnabled reflect
//     config.firebase.providers (Google on by default, Facebook/Apple only when toggled),
//     and the COMMITTED config.js ships "Google only" (Facebook/Apple are human-gated),
//   - BUTTON RENDERING: applyProviderVisibility shows exactly the enabled providers'
//     buttons (toggles `.hidden` on fake button elements) and hides the rest,
//   - PER-PROVIDER SIGN-IN CALL: the pure provider builder constructs the RIGHT Firebase
//     provider for each id (GoogleAuthProvider / FacebookAuthProvider /
//     OAuthProvider("apple.com") with the right scopes) and a stubbed signInWithPopup
//     resolves with the user — the exact path the browser's signInWithProvider runs, and
//   - ERROR HANDLING: describeAuthError maps the codes the popup/OAuth flow produces —
//     including the two the ticket calls out, account-exists-with-different-credential
//     and popup-closed-by-user — to friendly, provider-personalised copy.
//
// It is a plain assertion harness (no framework, no deps): it prints each check and exits
// non-zero on the first failure, so it doubles as a CI gate. It is a `.cjs` file OUTSIDE
// web/e2e/tests/, so Playwright (testDir ./tests) never picks it up.
//
// Run:  node web/e2e/social-login.simulation.cjs

"use strict";

const path = require("node:path");

// Require the REAL module under test. auth.js is a classic browser script that also
// exports its pure helpers (+ the shared provider builder) when required from Node — its
// browser bootstrap is skipped because `window` is undefined at require time. So this
// asserts the SAME code the page runs.
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
  assert(
    label + " (expected " + JSON.stringify(expected) + ", got " + JSON.stringify(actual) + ")",
    actual === expected,
  );
}

function assertDeep(label, actual, expected) {
  assert(
    label + " (expected " + JSON.stringify(expected) + ", got " + JSON.stringify(actual) + ")",
    JSON.stringify(actual) === JSON.stringify(expected),
  );
}

// Ids of the enabled providers, in order — the shape most assertions compare.
function enabledIds(firebaseCfg) {
  return auth.enabledProviders(firebaseCfg).map((p) => p.id);
}

// ---------------------------------------------------------------------------
// 1) The provider REGISTRY is the single source of truth (order + labels).
// ---------------------------------------------------------------------------
console.log("\nprovider registry (AUTH_PROVIDERS):");
assertDeep(
  "registry ids in render order",
  auth.AUTH_PROVIDERS.map((p) => p.id),
  ["google", "facebook", "apple"],
);
assertEqual("google label", auth.findProvider("google").label, "Google");
assertEqual("facebook label", auth.findProvider("facebook").label, "Facebook");
assertEqual("apple label", auth.findProvider("apple").label, "Apple");
assertEqual("lookup is case/space tolerant", auth.findProvider("  APPLE ").id, "apple");
assertEqual("unknown id => null", auth.findProvider("twitter"), null);
assertEqual("non-string id => null", auth.findProvider(42), null);

// ---------------------------------------------------------------------------
// 2) CONFIG-GATING — enabledProviders / isProviderEnabled reflect the toggles.
//    Both gates must hold: a real apiKey AND the provider switched on.
// ---------------------------------------------------------------------------
console.log("\nconfig-gating (isProviderEnabled / enabledProviders):");

// Not configured at all (empty apiKey — the OSK-92 gate): NOTHING is offered, even if a
// providers map says otherwise. No broken buttons before auth exists.
assertDeep(
  "not configured => no providers, even with toggles on",
  enabledIds({ apiKey: "", providers: { google: true, facebook: true, apple: true } }),
  [],
);
assertEqual("not configured => google not enabled", auth.isProviderEnabled({ apiKey: "" }, "google"), false);

// Configured, no providers map => Google defaults ON, others OFF (backwards compatible
// with the pre-OSK-131 "Google only" behaviour).
assertDeep("configured, no map => google only", enabledIds({ apiKey: "k" }), ["google"]);

// Configured with the FULL committed-shape map: google on, facebook/apple off.
assertDeep(
  "google:true, facebook:false, apple:false => google only",
  enabledIds({ apiKey: "k", providers: { google: true, facebook: false, apple: false } }),
  ["google"],
);

// All three toggled on => all three, in registry order.
assertDeep(
  "all toggles on => google, facebook, apple",
  enabledIds({ apiKey: "k", providers: { google: true, facebook: true, apple: true } }),
  ["google", "facebook", "apple"],
);

// A subset: facebook + apple on, google explicitly off => exactly those two, order kept.
assertDeep(
  "facebook + apple on, google off => facebook, apple",
  enabledIds({ apiKey: "k", providers: { google: false, facebook: true, apple: true } }),
  ["facebook", "apple"],
);

// STRICT true only — a truthy-but-wrong value never enables a provider (would pop a flow
// that fails). "yes" / 1 must NOT count.
assertEqual('facebook: "yes" (truthy) => NOT enabled', auth.isProviderEnabled({ apiKey: "k", providers: { facebook: "yes" } }, "facebook"), false);
assertEqual("facebook: 1 (truthy) => NOT enabled", auth.isProviderEnabled({ apiKey: "k", providers: { facebook: 1 } }, "facebook"), false);

// An UNKNOWN provider toggled on is ignored (a typo can't render a broken button).
assertDeep(
  "unknown toggle 'twitter:true' is ignored",
  enabledIds({ apiKey: "k", providers: { google: true, twitter: true } }),
  ["google"],
);

// Explicitly turning Google off is honoured.
assertEqual("google:false => google not enabled", auth.isProviderEnabled({ apiKey: "k", providers: { google: false } }, "google"), false);

// ---------------------------------------------------------------------------
// 3) The COMMITTED config.js must ship "Google only" — Facebook/Apple stay human-gated
//    until an operator enables them in the Firebase console AND flips the toggle.
// ---------------------------------------------------------------------------
console.log("\ncommitted config.js default:");
{
  // config.js sets `window.__APP_CONFIG__ = {…}`. Give it a fake window, then require it.
  global.window = {};
  require(path.resolve(__dirname, "..", "config.js"));
  const fb = (global.window.__APP_CONFIG__ || {}).firebase || {};
  assert("config.firebase.providers block exists", fb.providers && typeof fb.providers === "object");
  assertDeep("committed default enables Google only", enabledIds(fb), ["google"]);
  assertEqual("committed facebook toggle is false (human-gated)", fb.providers.facebook, false);
  assertEqual("committed apple toggle is false (human-gated)", fb.providers.apple, false);
}

// ---------------------------------------------------------------------------
// 4) BUTTON RENDERING — applyProviderVisibility toggles fake buttons' `.hidden`.
//    Duck-typed elements (only `.hidden`), exactly what the helper touches — no jsdom.
// ---------------------------------------------------------------------------
console.log("\napplyProviderVisibility() — reveals only enabled providers' buttons:");
function fakeButtons() {
  return {
    google: { hidden: true },
    facebook: { hidden: true },
    apple: { hidden: true },
  };
}

{
  // Committed default (Google only): google shown, facebook/apple hidden.
  const btns = fakeButtons();
  const shown = auth.applyProviderVisibility(
    { apiKey: "k", providers: { google: true, facebook: false, apple: false } },
    btns,
  );
  assertEqual("shown count", shown, 1);
  assertEqual("google button visible", btns.google.hidden, false);
  assertEqual("facebook button hidden", btns.facebook.hidden, true);
  assertEqual("apple button hidden", btns.apple.hidden, true);
}

{
  // All enabled: every button revealed.
  const btns = fakeButtons();
  const shown = auth.applyProviderVisibility(
    { apiKey: "k", providers: { google: true, facebook: true, apple: true } },
    btns,
  );
  assertEqual("shown count", shown, 3);
  assertEqual("google visible", btns.google.hidden, false);
  assertEqual("facebook visible", btns.facebook.hidden, false);
  assertEqual("apple visible", btns.apple.hidden, false);
}

{
  // Not configured: EVERY provider button stays hidden.
  const btns = fakeButtons();
  const shown = auth.applyProviderVisibility({ apiKey: "" }, btns);
  assertEqual("shown count", shown, 0);
  assertEqual("google hidden", btns.google.hidden, true);
  assertEqual("facebook hidden", btns.facebook.hidden, true);
  assertEqual("apple hidden", btns.apple.hidden, true);
}

{
  // Partial DOM (a page that renders only some buttons) must not throw / must skip the
  // absent ones.
  let threw = false;
  try {
    const only = { google: { hidden: true } };
    const shown = auth.applyProviderVisibility({ apiKey: "k", providers: { google: true, facebook: true } }, only);
    assertEqual("only-present button counted", shown, 1);
    assertEqual("present google shown", only.google.hidden, false);
  } catch (e) {
    threw = true;
  }
  assertEqual("no throw on partial button map", threw, false);
}

// ---------------------------------------------------------------------------
// 5) PER-PROVIDER SIGN-IN CALL — build the RIGHT Firebase provider via the shared
//    pure builder, then drive a STUBBED signInWithPopup. This mirrors exactly what the
//    browser's signInWithProvider does: oskBuildProviderWith(authMod, id) ->
//    signInWithPopup(auth, provider) -> cred.user. No SDK, no network.
// ---------------------------------------------------------------------------
console.log("\nper-provider sign-in call (stubbed Firebase):");

// A stub of the firebase-auth module namespace. Each provider class records its id +
// scopes so we can assert the right one was constructed. signInWithPopup records the
// provider it was handed and resolves with a fake credential.
function makeAuthStub() {
  const calls = [];
  function GoogleAuthProvider() {
    this.providerId = "google.com";
    this.scopes = [];
    this.addScope = (s) => this.scopes.push(s);
  }
  function FacebookAuthProvider() {
    this.providerId = "facebook.com";
    this.scopes = [];
    this.addScope = (s) => this.scopes.push(s);
  }
  function OAuthProvider(id) {
    this.providerId = id;
    this.scopes = [];
    this.addScope = (s) => this.scopes.push(s);
  }
  return {
    calls,
    GoogleAuthProvider,
    FacebookAuthProvider,
    OAuthProvider,
    signInWithPopup(authInstance, provider) {
      calls.push(provider);
      return Promise.resolve({ user: { uid: "u-" + provider.providerId, providerId: provider.providerId } });
    },
  };
}

// Faithful re-enactment of the browser signInWithProvider using the EXPORTED pure builder
// + the stub's signInWithPopup. (The browser's own signInWithProvider is inside the
// window-guarded closure, so we drive the same two exported/stubbed steps it composes.)
function simulateSignIn(ns, firebaseCfg, id) {
  const desc = auth.findProvider(id);
  if (!desc) { return Promise.reject(new Error("Unknown sign-in provider: " + id)); }
  if (!auth.isProviderEnabled(firebaseCfg, desc.id)) {
    return Promise.reject(new Error(desc.label + " sign-in is not enabled."));
  }
  const provider = auth.buildProviderWith(ns, desc.id);
  if (!provider) { return Promise.reject(new Error("Unknown sign-in provider: " + id)); }
  return ns.signInWithPopup({}, provider).then((cred) => ({ provider, user: cred.user }));
}

const allOn = { apiKey: "k", providers: { google: true, facebook: true, apple: true } };

// Collect the per-provider assertions, then run them all, then print the summary.
const signInChecks = Promise.all([
  // GOOGLE
  (() => {
    const ns = makeAuthStub();
    return simulateSignIn(ns, allOn, "google").then((r) => {
      assertEqual("google: constructed GoogleAuthProvider", r.provider.providerId, "google.com");
      assertEqual("google: signInWithPopup called once", ns.calls.length, 1);
      assertEqual("google: resolves with a user", r.user.providerId, "google.com");
    });
  })(),

  // FACEBOOK — FacebookAuthProvider + email scope.
  (() => {
    const ns = makeAuthStub();
    return simulateSignIn(ns, allOn, "facebook").then((r) => {
      assertEqual("facebook: constructed FacebookAuthProvider", r.provider.providerId, "facebook.com");
      assertDeep("facebook: requested email scope", r.provider.scopes, ["email"]);
      assertEqual("facebook: resolves with a user", r.user.providerId, "facebook.com");
    });
  })(),

  // APPLE — generic OAuthProvider("apple.com") + email & name scopes.
  (() => {
    const ns = makeAuthStub();
    return simulateSignIn(ns, allOn, "apple").then((r) => {
      assertEqual("apple: constructed OAuthProvider('apple.com')", r.provider.providerId, "apple.com");
      assertDeep("apple: requested email + name scopes", r.provider.scopes, ["email", "name"]);
      assertEqual("apple: resolves with a user", r.user.providerId, "apple.com");
    });
  })(),

  // DISABLED provider is REFUSED before any popup — config gate is load-bearing.
  (() => {
    const ns = makeAuthStub();
    return simulateSignIn(ns, { apiKey: "k", providers: { facebook: false } }, "facebook")
      .then(() => assert("disabled facebook should reject (it resolved!)", false))
      .catch((err) => {
        assert("disabled facebook rejects", /not enabled/.test(err.message));
        assertEqual("disabled facebook: no popup opened", ns.calls.length, 0);
      });
  })(),

  // UNKNOWN provider is REFUSED (never builds / pops).
  (() => {
    const ns = makeAuthStub();
    return simulateSignIn(ns, allOn, "twitter")
      .then(() => assert("unknown provider should reject (it resolved!)", false))
      .catch((err) => {
        assert("unknown provider rejects", /Unknown sign-in provider/.test(err.message));
        assertEqual("unknown provider: no popup opened", ns.calls.length, 0);
      });
  })(),
]);

// ---------------------------------------------------------------------------
// 6) ERROR HANDLING — describeAuthError maps the codes the OAuth flow produces.
// ---------------------------------------------------------------------------
console.log("\ndescribeAuthError() — friendly, provider-personalised copy:");
assert(
  "account-exists-with-different-credential => 'different sign-in method' + provider",
  /different sign-in method/.test(
    auth.describeAuthError({ code: "auth/account-exists-with-different-credential" }, "Facebook"),
  ) && /Facebook/.test(auth.describeAuthError({ code: "auth/account-exists-with-different-credential" }, "Facebook")),
);
assert(
  "popup-closed-by-user => 'cancelled' copy",
  /cancelled/i.test(auth.describeAuthError({ code: "auth/popup-closed-by-user" })),
);
assert(
  "popup-blocked => mentions blocked popup",
  /blocked/i.test(auth.describeAuthError({ code: "auth/popup-blocked" })),
);
assert(
  "cancelled-popup-request => 'one popup at a time'",
  /one sign-in popup/i.test(auth.describeAuthError({ code: "auth/cancelled-popup-request" })),
);
assert(
  "operation-not-allowed => 'isn't enabled' + provider (Apple)",
  /isn't enabled/.test(auth.describeAuthError({ code: "auth/operation-not-allowed" }, "Apple")) &&
    /Apple/.test(auth.describeAuthError({ code: "auth/operation-not-allowed" }, "Apple")),
);
assert(
  "unauthorized-domain => mentions authorised domain",
  /authorised domain/i.test(auth.describeAuthError({ code: "auth/unauthorized-domain" })),
);
assert(
  "network-request-failed => mentions network",
  /network/i.test(auth.describeAuthError({ code: "auth/network-request-failed" })),
);
assertEqual(
  "unknown code => falls back to the raw message",
  auth.describeAuthError({ code: "auth/weird", message: "boom" }),
  "boom",
);
assertEqual(
  "no message + no code => generic per-provider line",
  auth.describeAuthError({}, "Google"),
  "Google sign-in failed.",
);

// ---------------------------------------------------------------------------
// Summary + exit code (deferred until the async sign-in checks settle).
// ---------------------------------------------------------------------------
signInChecks
  .catch(() => { /* individual assertions already recorded any failure */ })
  .then(() => {
    console.log("\n----------------------------------------");
    console.log("social-login simulation: " + passed + " passed, " + failed + " failed");
    if (failed > 0) {
      process.exitCode = 1;
    }
  });
