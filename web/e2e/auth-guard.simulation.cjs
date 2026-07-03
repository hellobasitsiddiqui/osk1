// auth-guard.simulation.cjs — headless Node simulation of the OSK-74 web auth guard.
//
// WHY this exists: live sign-in cannot be exercised in CI/locally without the human
// Firebase Web App apiKey (OSK-92) and firebase-scoped ADC (OSK-38). But the guard's
// show/hide DECISION logic is PURE (no DOM, no network, no Firebase) — web/auth.js
// exports it for exactly this reason. This script requires those pure helpers and drives
// them with FAKE auth-states, asserting that:
//   - signed-out    => the sign-in affordance shows, protected content is hidden,
//   - signed-in     => protected content shows, the sign-in affordance is hidden,
//   - not-configured (empty apiKey — the committed default until OSK-92) => a clear
//     notice shows and BOTH the sign-in form and the protected content stay hidden,
//   - loading / error states behave sensibly, and
//   - applyGuardView toggles a set of fake DOM elements' `.hidden` flags correctly.
//
// It is a plain assertion harness (no test framework, no deps): it prints each check and
// exits non-zero on the first failure, so it doubles as a CI-friendly gate. It is a
// `.cjs` file OUTSIDE web/e2e/tests/, so Playwright (testDir ./tests) never picks it up.
//
// Run:  node web/e2e/auth-guard.simulation.cjs

"use strict";

const path = require("node:path");
// Require the REAL module under test. auth.js is a classic browser script that also
// exports its pure helpers when required from Node (its browser bootstrap is skipped
// because `window` is undefined here). So this asserts the SAME code the page runs.
const auth = require(path.resolve(__dirname, "..", "auth.js"));

let passed = 0;
let failed = 0;

// Minimal assert: record + print pass/fail, never throw (so we see ALL failures).
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

// ---------------------------------------------------------------------------
// 1) isFirebaseConfigured — apiKey presence is the single source of truth.
// ---------------------------------------------------------------------------
console.log("\nisFirebaseConfigured():");
assertEqual("missing config => not configured", auth.isFirebaseConfigured(undefined), false);
assertEqual("empty apiKey => not configured", auth.isFirebaseConfigured({ apiKey: "" }), false);
assertEqual("whitespace apiKey => not configured", auth.isFirebaseConfigured({ apiKey: "   " }), false);
assertEqual("non-string apiKey => not configured", auth.isFirebaseConfigured({ apiKey: 123 }), false);
assertEqual("real apiKey => configured", auth.isFirebaseConfigured({ apiKey: "AIzaSyExample" }), true);

// ---------------------------------------------------------------------------
// 2) computeGuardView — one assertion group per auth state.
// ---------------------------------------------------------------------------
console.log("\ncomputeGuardView() — NOT CONFIGURED (empty apiKey, the OSK-92 gate):");
{
  const v = auth.computeGuardView({ configured: false, ready: true, user: null, error: null });
  assertEqual("mode", v.mode, "not-configured");
  assertEqual("sign-in hidden", v.showSignIn, false);
  assertEqual("protected content hidden", v.showApp, false);
  assertEqual("notice shown", v.showNotice, true);
  assert("notice mentions the human gate", /OSK-92/.test(v.noticeText));
}

console.log("\ncomputeGuardView() — LOADING (configured, SDK not settled yet):");
{
  const v = auth.computeGuardView({ configured: true, ready: false, user: null, error: null });
  assertEqual("mode", v.mode, "loading");
  assertEqual("sign-in hidden", v.showSignIn, false);
  assertEqual("protected content hidden", v.showApp, false);
  assertEqual("notice shown", v.showNotice, true);
}

console.log("\ncomputeGuardView() — ERROR (configured, SDK failed to load):");
{
  const v = auth.computeGuardView({ configured: true, ready: true, user: null, error: "network blocked" });
  assertEqual("mode", v.mode, "error");
  assertEqual("sign-in hidden", v.showSignIn, false);
  assertEqual("protected content hidden", v.showApp, false);
  assertEqual("notice shown", v.showNotice, true);
  assert("notice surfaces the error text", /network blocked/.test(v.noticeText));
}

console.log("\ncomputeGuardView() — SIGNED OUT (configured, ready, no user):");
{
  const v = auth.computeGuardView({ configured: true, ready: true, user: null, error: null });
  assertEqual("mode", v.mode, "signed-out");
  assertEqual("sign-in SHOWN", v.showSignIn, true);
  assertEqual("protected content hidden", v.showApp, false);
  assertEqual("notice hidden", v.showNotice, false);
}

console.log("\ncomputeGuardView() — SIGNED IN (configured, ready, user present):");
{
  const fakeUser = { uid: "abc123", email: "user@example.com" };
  const v = auth.computeGuardView({ configured: true, ready: true, user: fakeUser, error: null });
  assertEqual("mode", v.mode, "signed-in");
  assertEqual("sign-in hidden", v.showSignIn, false);
  assertEqual("protected content SHOWN", v.showApp, true);
  assertEqual("notice hidden", v.showNotice, false);
}

// ---------------------------------------------------------------------------
// 3) applyGuardView — drive a set of FAKE DOM elements and assert the `.hidden`
//    flags + notice text. Duck-typed elements (just `.hidden` / `.textContent`),
//    exactly what applyGuardView touches — no jsdom, no browser needed.
// ---------------------------------------------------------------------------
function fakeEls() {
  return {
    signIn: { hidden: true },
    app: { hidden: true },
    notice: { hidden: true },
    noticeText: { textContent: "" },
  };
}

console.log("\napplyGuardView() — signed OUT reveals the sign-in form only:");
{
  const els = fakeEls();
  auth.applyGuardView(
    auth.computeGuardView({ configured: true, ready: true, user: null, error: null }),
    els,
  );
  assertEqual("sign-in visible", els.signIn.hidden, false);
  assertEqual("protected content hidden", els.app.hidden, true);
  assertEqual("notice hidden", els.notice.hidden, true);
}

console.log("\napplyGuardView() — signed IN reveals the protected content only:");
{
  const els = fakeEls();
  auth.applyGuardView(
    auth.computeGuardView({ configured: true, ready: true, user: { uid: "u1" }, error: null }),
    els,
  );
  assertEqual("sign-in hidden", els.signIn.hidden, true);
  assertEqual("protected content visible", els.app.hidden, false);
  assertEqual("notice hidden", els.notice.hidden, true);
}

console.log("\napplyGuardView() — NOT CONFIGURED reveals the notice only, hides everything else:");
{
  const els = fakeEls();
  auth.applyGuardView(
    auth.computeGuardView({ configured: false, ready: true, user: null, error: null }),
    els,
  );
  assertEqual("sign-in hidden", els.signIn.hidden, true);
  assertEqual("protected content hidden", els.app.hidden, true);
  assertEqual("notice visible", els.notice.hidden, false);
  assert("notice text populated", typeof els.noticeText.textContent === "string" && els.noticeText.textContent.length > 0);
}

console.log("\napplyGuardView() — tolerates a partial DOM (missing elements are skipped):");
{
  // Only pass `app`; the others are absent. Must not throw.
  let threw = false;
  try {
    auth.applyGuardView({ showApp: true, showSignIn: true, showNotice: true, noticeText: "x" }, { app: { hidden: true } });
  } catch (e) {
    threw = true;
  }
  assertEqual("no throw on partial DOM", threw, false);
}

// ---------------------------------------------------------------------------
// 4) Pinned SDK version is exported (single source of truth).
// ---------------------------------------------------------------------------
console.log("\nSDK version pin:");
assert("OSK_FIREBASE_SDK_VERSION is a pinned x.y.z", /^\d+\.\d+\.\d+$/.test(auth.OSK_FIREBASE_SDK_VERSION));

// ---------------------------------------------------------------------------
// Summary + exit code.
// ---------------------------------------------------------------------------
console.log("\n----------------------------------------");
console.log("auth-guard simulation: " + passed + " passed, " + failed + " failed");
if (failed > 0) {
  process.exitCode = 1;
}
