// profile.simulation.cjs — headless Node simulation of the OSK-86 edit-profile page.
//
// WHY this exists: live sign-in + a live backend cannot be exercised in CI/locally without
// the human Firebase Web App apiKey (OSK-92) and firebase-scoped ADC (OSK-38). But the
// profile page's core behaviour — loading the profile, DIRTY-TRACKING the form, building a
// PATCH of ONLY the changed fields, rendering the backend's 400 validation errors inline,
// and degrading gracefully on 401 — is all pure/DOM-shaped logic that web/profile.js
// exports for exactly this reason. This script requires those exports and drives them with
// a FAKE DOM + a STUBBED fetch + a fake token source, asserting that:
//   - load (200)      => every input is populated from GET /api/v1/me and the form shows,
//   - load (401)      => a graceful "not authorized" notice shows and the form stays hidden,
//   - save (changed)  => the PATCH body contains ONLY the changed fields (age as a NUMBER),
//                        success shows, and dirty-tracking resets (a second save is a no-op),
//   - save (no edits) => "No changes to save." and NO network call,
//   - save (400)      => the ProblemDetail field errors render inline on the right inputs,
//   - save (401)      => a graceful notice shows,
//   plus unit checks of the pure buildPatch / parseProblemErrors helpers.
//
// It is a plain assertion harness (no test framework, no deps): it prints each check and
// exits non-zero on the first failure, so it doubles as a CI-friendly gate. It is a `.cjs`
// file OUTSIDE web/e2e/tests/, so Playwright (testDir ./tests) never picks it up.
//
// Run:  node web/e2e/profile.simulation.cjs

"use strict";

const path = require("node:path");
// Require the REAL module under test. profile.js is a classic browser script that also
// exports its pure helpers + the controller factory when required from Node (its browser
// bootstrap is skipped because `window` is undefined here). So this asserts the SAME code
// the page runs.
const profile = require(path.resolve(__dirname, "..", "profile.js"));

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
function assertDeep(label, actual, expected) {
  assert(
    label + " (expected " + JSON.stringify(expected) + ", got " + JSON.stringify(actual) + ")",
    JSON.stringify(actual) === JSON.stringify(expected),
  );
}

// ---------------------------------------------------------------------------
// Fakes: a tiny DOM (auto-creating elements on getElementById), a fetch stub that records
// calls and returns canned responses, and a token source. None of jsdom/browser needed —
// the controller only touches .value / .textContent / .hidden / .disabled / setAttribute.
// ---------------------------------------------------------------------------
function makeFakeDoc() {
  const store = {};
  return {
    _store: store,
    getElementById(id) {
      if (!store[id]) {
        store[id] = {
          id,
          value: "",
          textContent: "",
          hidden: false,
          disabled: false,
          _attrs: {},
          setAttribute(k, v) { this._attrs[k] = v; },
          removeAttribute(k) { delete this._attrs[k]; },
        };
      }
      return store[id];
    },
  };
}

// handler(url, opts, callIndex) -> { ok, status, body } ; body is what json() resolves to.
function makeFetchStub(handler) {
  const calls = [];
  const fn = function (url, opts) {
    calls.push({ url, opts });
    const r = handler(url, opts, calls.length) || {};
    return Promise.resolve({
      ok: r.ok !== undefined ? r.ok : (r.status >= 200 && r.status < 300),
      status: r.status,
      json: function () {
        if (r.jsonThrows) { return Promise.reject(new Error("bad json")); }
        return Promise.resolve(r.body);
      },
    });
  };
  fn.calls = calls;
  return fn;
}

function tokenSource(token) {
  return function () { return Promise.resolve(token); };
}

// A representative "loaded" profile (mirrors the GET /api/v1/me CurrentUser shape).
function sampleProfile(overrides) {
  return Object.assign(
    {
      uid: "firebase-uid-123",
      email: "ada@example.com",
      role: "USER",
      firstName: "Ada",
      lastName: "Lovelace",
      displayName: "Ada L.",
      city: "London",
      age: 36,
      phone: "+44 20 7946 0000",
      notificationPreference: "PUSH",
      timezone: "Europe/London",
      locale: "en-GB",
    },
    overrides || {},
  );
}

// ===========================================================================
// 1) PURE: buildPatch — dirty-tracking / send-only-changed.
// ===========================================================================
console.log("\nbuildPatch() — dirty-tracking:");
{
  const orig = sampleProfile();

  // No edits at all => empty patch.
  assertDeep(
    "no changes => {}",
    profile.buildPatch(orig, {
      firstName: "Ada", lastName: "Lovelace", displayName: "Ada L.", city: "London",
      age: "36", phone: "+44 20 7946 0000", notificationPreference: "PUSH",
      timezone: "Europe/London", locale: "en-GB",
    }),
    {},
  );

  // Only city changed => only city in the patch.
  assertDeep(
    "one field changed => only that field",
    profile.buildPatch(orig, {
      firstName: "Ada", lastName: "Lovelace", displayName: "Ada L.", city: "Bristol",
      age: "36", phone: "+44 20 7946 0000", notificationPreference: "PUSH",
      timezone: "Europe/London", locale: "en-GB",
    }),
    { city: "Bristol" },
  );

  // Age changed => sent as a NUMBER, not a string.
  {
    const p = profile.buildPatch(orig, { age: "40" });
    assertEqual("age patched", p.age, 40);
    assertEqual("age is a number", typeof p.age, "number");
  }

  // Clearing age (was 36, now empty) is SKIPPED — an integer can't be cleared to null.
  assertDeep("cleared age is omitted", profile.buildPatch(orig, { age: "" }), {});

  // Enum change is sent as-is.
  assertDeep("enum changed", profile.buildPatch(orig, { notificationPreference: "NONE" }), { notificationPreference: "NONE" });

  // Clearing a text field sends "" (sets empty — the backend can't null it, a documented
  // asymmetry). This proves a change to "" is treated as a real change.
  assertDeep("cleared text field sends empty string", profile.buildPatch(orig, { firstName: "" }), { firstName: "" });

  // Untouched-and-already-empty field (orig null, input "") => NOT a change.
  assertDeep(
    "empty stays empty => no spurious send",
    profile.buildPatch(sampleProfile({ firstName: null }), { firstName: "" }),
    {},
  );

  // Whitespace around an otherwise-unchanged value is trimmed away => no change.
  assertDeep("whitespace-only diff is trimmed to no-change", profile.buildPatch(orig, { city: "  London  " }), {});
}

// ===========================================================================
// 2) PURE: parseProblemErrors — map a 400 ProblemDetail to per-field messages.
// ===========================================================================
console.log("\nparseProblemErrors() — 400 ProblemDetail mapping:");
{
  const parsed = profile.parseProblemErrors({
    title: "Bad Request",
    status: 400,
    detail: "Request validation failed.",
    errors: ["age: must be at least 13", "timezone: must be a valid IANA time zone"],
  });
  assertEqual("age message mapped to field", parsed.fieldErrors.age, "must be at least 13");
  assertEqual("timezone message mapped to field", parsed.fieldErrors.timezone, "must be a valid IANA time zone");
  assertEqual("no leftover general messages", parsed.generalMessages.length, 0);
}
{
  // An unknown field name falls through to generalMessages (never dropped silently).
  const parsed = profile.parseProblemErrors({ errors: ["mystery: something went wrong"] });
  assertEqual("unknown field => not a field error", parsed.fieldErrors.mystery, undefined);
  assertEqual("unknown field => general message kept", parsed.generalMessages.length, 1);
}
{
  // A malformed-body 400 (no `errors` array) surfaces the `detail`.
  const parsed = profile.parseProblemErrors({ detail: "Malformed or invalid request body." });
  assertEqual("detail captured", parsed.detail, "Malformed or invalid request body.");
  assertEqual("no field errors", Object.keys(parsed.fieldErrors).length, 0);
}
assertDeep("null body is safe", profile.parseProblemErrors(null), { fieldErrors: {}, generalMessages: [], detail: "" });

// ===========================================================================
// 3) CONTROLLER: load (200) — populate the form from GET /api/v1/me.
// ===========================================================================
async function run() {
  console.log("\ncontroller.loadProfile() — 200 populates the form:");
  {
    const doc = makeFakeDoc();
    const loaded = sampleProfile();
    const fetchStub = makeFetchStub(function () { return { status: 200, body: loaded }; });
    const c = profile.createProfileController({
      doc, fetchFn: fetchStub, getIdToken: tokenSource("fake-token"), apiBaseUrl: "http://127.0.0.1:8080",
    });

    const outcome = await c.loadProfile();
    assertEqual("load ok", outcome.ok, true);
    assertEqual("GET was called once", fetchStub.calls.length, 1);
    assert("GET hit /api/v1/me", /\/api\/v1\/me$/.test(fetchStub.calls[0].url));
    assert("GET carried a Bearer token", fetchStub.calls[0].opts.headers.Authorization === "Bearer fake-token");
    assertEqual("firstName input populated", doc.getElementById("pf-firstName").value, "Ada");
    assertEqual("age input populated (stringified)", doc.getElementById("pf-age").value, "36");
    assertEqual("notificationPreference select populated", doc.getElementById("pf-notificationPreference").value, "PUSH");
    assertEqual("read-only uid rendered", doc.getElementById("pf-uid").textContent, "firebase-uid-123");
    assertEqual("read-only role rendered", doc.getElementById("pf-role").textContent, "USER");
    assertEqual("form is shown", doc.getElementById("profile-form").hidden, false);
    assertEqual("loading status is cleared", doc.getElementById("profile-status").hidden, true);
  }

  // -------------------------------------------------------------------------
  // 4) CONTROLLER: load (401) — graceful notice, form stays hidden.
  // -------------------------------------------------------------------------
  console.log("\ncontroller.loadProfile() — 401 degrades gracefully:");
  {
    const doc = makeFakeDoc();
    const fetchStub = makeFetchStub(function () { return { status: 401, body: {} }; });
    const c = profile.createProfileController({
      doc, fetchFn: fetchStub, getIdToken: tokenSource("stale-token"), apiBaseUrl: "",
    });

    const outcome = await c.loadProfile();
    assertEqual("outcome status 401", outcome.status, 401);
    assertEqual("form stays hidden", doc.getElementById("profile-form").hidden, true);
    const err = doc.getElementById("profile-general-error");
    assertEqual("general error shown", err.hidden, false);
    assert("general error mentions 401", /401/.test(err.textContent));
  }

  // -------------------------------------------------------------------------
  // 5) CONTROLLER: save — sends ONLY changed fields, success, dirty-tracking resets.
  // -------------------------------------------------------------------------
  console.log("\ncontroller.save() — patch-only-changed + success:");
  {
    const doc = makeFakeDoc();
    const loaded = sampleProfile();
    // First request: GET load. Second: PATCH save (echo the updated profile back).
    const fetchStub = makeFetchStub(function (url, opts, n) {
      if (n === 1) { return { status: 200, body: loaded }; }
      const sent = JSON.parse(opts.body);
      return { status: 200, body: sampleProfile({ city: sent.city, age: sent.age }) };
    });
    const c = profile.createProfileController({
      doc, fetchFn: fetchStub, getIdToken: tokenSource("fake-token"), apiBaseUrl: "http://127.0.0.1:8080",
    });

    await c.loadProfile();
    // User edits: change city + age; leave everything else untouched.
    doc.getElementById("pf-city").value = "Bristol";
    doc.getElementById("pf-age").value = "40";

    const outcome = await c.save();
    assertEqual("save ok", outcome.ok, true);
    assertEqual("PATCH was called", fetchStub.calls.length, 2);
    const patchCall = fetchStub.calls[1];
    assertEqual("method is PATCH", patchCall.opts.method, "PATCH");
    assertEqual("Content-Type json", patchCall.opts.headers["Content-Type"], "application/json");
    const body = JSON.parse(patchCall.opts.body);
    assertDeep("body has ONLY the changed fields", body, { city: "Bristol", age: 40 });
    assertEqual("age sent as a number", typeof body.age, "number");
    assertEqual("success shown", doc.getElementById("profile-success").textContent, "Profile saved.");

    // Dirty-tracking reset: after a successful save that echoed the new values, a second
    // save with no further edits must be a NO-OP (no extra network call).
    const outcome2 = await c.save();
    assertEqual("second save is a no-op", outcome2.noChanges, true);
    assertEqual("no extra network call", fetchStub.calls.length, 2);
    assertEqual("no-changes message shown", doc.getElementById("profile-success").textContent, "No changes to save.");
  }

  // -------------------------------------------------------------------------
  // 6) CONTROLLER: save (no edits) — no network call at all.
  // -------------------------------------------------------------------------
  console.log("\ncontroller.save() — nothing changed => no request:");
  {
    const doc = makeFakeDoc();
    const fetchStub = makeFetchStub(function () { return { status: 200, body: sampleProfile() }; });
    const c = profile.createProfileController({
      doc, fetchFn: fetchStub, getIdToken: tokenSource("fake-token"), apiBaseUrl: "",
    });
    await c.loadProfile();
    const outcome = await c.save();
    assertEqual("no-changes outcome", outcome.noChanges, true);
    assertEqual("only the load call happened", fetchStub.calls.length, 1);
  }

  // -------------------------------------------------------------------------
  // 7) CONTROLLER: save (400) — render ProblemDetail field errors inline.
  // -------------------------------------------------------------------------
  console.log("\ncontroller.save() — 400 renders field errors inline:");
  {
    const doc = makeFakeDoc();
    const loaded = sampleProfile();
    const fetchStub = makeFetchStub(function (url, opts, n) {
      if (n === 1) { return { status: 200, body: loaded }; }
      return {
        status: 400,
        body: {
          title: "Bad Request",
          status: 400,
          detail: "Request validation failed.",
          errors: ["age: must be at least 13"],
        },
      };
    });
    const c = profile.createProfileController({
      doc, fetchFn: fetchStub, getIdToken: tokenSource("fake-token"), apiBaseUrl: "",
    });
    await c.loadProfile();
    doc.getElementById("pf-age").value = "5"; // below the min
    const outcome = await c.save();

    assertEqual("outcome status 400", outcome.status, 400);
    assertEqual("one field error rendered", outcome.fieldErrorCount, 1);
    const ageErr = doc.getElementById("err-age");
    assertEqual("age error text", ageErr.textContent, "must be at least 13");
    assertEqual("age error is visible", ageErr.hidden, false);
    assertEqual("age input marked invalid", doc.getElementById("pf-age")._attrs["aria-invalid"], "true");
    assertEqual("no success shown on a rejected save", doc.getElementById("profile-success").hidden, true);
    assertEqual("a general hint is shown too", doc.getElementById("profile-general-error").hidden, false);
  }

  // -------------------------------------------------------------------------
  // 8) CONTROLLER: save (401) — graceful notice.
  // -------------------------------------------------------------------------
  console.log("\ncontroller.save() — 401 degrades gracefully:");
  {
    const doc = makeFakeDoc();
    const fetchStub = makeFetchStub(function (url, opts, n) {
      if (n === 1) { return { status: 200, body: sampleProfile() }; }
      return { status: 401, body: {} };
    });
    const c = profile.createProfileController({
      doc, fetchFn: fetchStub, getIdToken: tokenSource("fake-token"), apiBaseUrl: "",
    });
    await c.loadProfile();
    doc.getElementById("pf-city").value = "Bristol";
    const outcome = await c.save();
    assertEqual("outcome status 401", outcome.status, 401);
    assert("general error mentions 401", /401/.test(doc.getElementById("profile-general-error").textContent));
    assertEqual("save button re-enabled after failure", doc.getElementById("save-btn").disabled, false);
  }

  // -------------------------------------------------------------------------
  // 9) Field spec sanity — the exported list covers exactly the OSK-67 editable fields.
  // -------------------------------------------------------------------------
  console.log("\nfield spec:");
  {
    const keys = profile.OSK_PROFILE_FIELDS.map(function (f) { return f.key; });
    ["firstName", "lastName", "displayName", "city", "age", "phone", "notificationPreference", "timezone", "locale"]
      .forEach(function (k) { assert("editable field present: " + k, keys.indexOf(k) !== -1); });
    assert("age is typed int", profile.OSK_PROFILE_FIELDS.some(function (f) { return f.key === "age" && f.type === "int"; }));
    assert("notificationPreference is typed enum", profile.OSK_PROFILE_FIELDS.some(function (f) { return f.key === "notificationPreference" && f.type === "enum"; }));
  }

  // -------------------------------------------------------------------------
  // Summary + exit code.
  // -------------------------------------------------------------------------
  console.log("\n----------------------------------------");
  console.log("profile simulation: " + passed + " passed, " + failed + " failed");
  if (failed > 0) { process.exitCode = 1; }
}

run().catch(function (err) {
  console.error("simulation crashed:", err);
  process.exitCode = 1;
});
