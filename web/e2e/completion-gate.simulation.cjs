// completion-gate.simulation.cjs — headless Node simulation of the OSK-136 first-login
// profile-completion gate.
//
// WHY this exists: live sign-in + a live GET/PATCH /api/v1/me cannot be exercised in
// CI/locally without the human Firebase Web App apiKey (OSK-92) and firebase-scoped ADC
// (OSK-38). But every piece of completion-gate.js that MATTERS is either a PURE
// data->data transform, a fetch against an injectable `fetchImpl`/`getToken`, or DOM built
// against an injectable `document` — the module exports them all for exactly this reason.
// This script requires those helpers + the controller and drives them with a FAKE DOM, a
// STUBBED fetch and FAKE auth, asserting the ACs:
//   - the gate SHOWS (blocking form mounts) when a required field is missing,
//   - the gate stays HIDDEN when all required fields are present,
//   - submit PATCHes ONLY the entered fields, with the Bearer token, to PATCH /api/v1/me,
//   - a 400 ProblemDetail (`errors: ["field: message"]`) renders INLINE per-field errors,
//   - a 401 on submit is handled (general error shown; the gate does NOT dismiss),
//   - fetch/patch classify token/401/non-ok/success/throw correctly, and
//   - a brief blocking loader is shown while /me is in flight (no flash-of-app).
//
// It is a plain assertion harness (no test framework, no deps): it prints each check and
// exits non-zero on any failure, so it doubles as a CI-friendly gate. It is a `.cjs` file
// OUTSIDE web/e2e/tests/, so Playwright (testDir ./tests) never picks it up.
//
// Run:  node web/e2e/completion-gate.simulation.cjs

"use strict";

const path = require("node:path");
// Require the REAL module under test. completion-gate.js is a classic browser script that
// also exports its helpers when required from Node (its browser bootstrap is skipped
// because `window` is undefined here). So this asserts the SAME code the page runs.
const CG = require(path.resolve(__dirname, "..", "completion-gate.js"));

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
function assertMatch(label, actual, regex) {
  assert(label + " (got " + JSON.stringify(actual) + ")", typeof actual === "string" && regex.test(actual));
}

// ---------------------------------------------------------------------------
// A tiny FAKE DOM: just enough of the surface completion-gate.js touches — createElement,
// a node's className / textContent / hidden / disabled / value / attributes / style /
// childNodes / parentNode / appendChild / removeChild / addEventListener / focus. No jsdom.
// ---------------------------------------------------------------------------
function makeFakeDoc() {
  function makeEl(tag) {
    return {
      tagName: tag,
      className: "",
      textContent: "",
      hidden: false,
      disabled: false,
      value: "",
      type: "",
      attributes: {},
      style: {},
      childNodes: [],
      parentNode: null,
      _listeners: {},
      _focused: false,
      get firstChild() {
        return this.childNodes[0] || null;
      },
      appendChild(child) {
        this.childNodes.push(child);
        child.parentNode = this;
        return child;
      },
      removeChild(child) {
        const i = this.childNodes.indexOf(child);
        if (i >= 0) {
          this.childNodes.splice(i, 1);
          child.parentNode = null;
        }
        return child;
      },
      setAttribute(name, val) {
        this.attributes[name] = val;
      },
      getAttribute(name) {
        return Object.prototype.hasOwnProperty.call(this.attributes, name) ? this.attributes[name] : null;
      },
      addEventListener(type, fn) {
        (this._listeners[type] = this._listeners[type] || []).push(fn);
      },
      dispatch(type, ev) {
        (this._listeners[type] || []).forEach((fn) => fn(ev || {}));
      },
      focus() {
        this._focused = true;
      },
    };
  }
  const doc = { createElement: makeEl };
  doc.body = makeEl("body");
  doc.head = makeEl("head");
  return doc;
}

// Depth-first find first descendant (incl. self) whose className contains `cls`.
function findByClass(node, cls) {
  if (!node) {
    return null;
  }
  if ((node.className || "").split(/\s+/).indexOf(cls) >= 0) {
    return node;
  }
  for (const c of node.childNodes || []) {
    const hit = findByClass(c, cls);
    if (hit) {
      return hit;
    }
  }
  return null;
}
function hasChildWithClass(node, cls) {
  return !!findByClass(node, cls);
}

// A stubbed fetch Response.
function fakeResponse(status, body) {
  return {
    status: status,
    ok: status >= 200 && status < 300,
    json: function () {
      return Promise.resolve(body);
    },
  };
}

// A recording fetch stub: returns the queued responses in order and records every call.
function makeFetchStub(responder) {
  const calls = [];
  const impl = function (url, opts) {
    calls.push({ url: url, opts: opts || {} });
    return Promise.resolve(responder(url, opts, calls.length - 1));
  };
  impl.calls = calls;
  return impl;
}

(async function main() {
  const required = ["firstName", "city", "age"];

  // =========================================================================
  // 1) resolveRequiredFields — config-driven, defensive.
  // =========================================================================
  console.log("\nresolveRequiredFields():");
  assertEqual("default when absent", CG.resolveRequiredFields(undefined).join(","), "firstName,city,age");
  assertEqual("honours a subset", CG.resolveRequiredFields({ requiredFields: ["city", "age"] }).join(","), "city,age");
  assertEqual("drops unknown fields", CG.resolveRequiredFields({ requiredFields: ["city", "bogus"] }).join(","), "city");
  assertEqual("garbage -> default", CG.resolveRequiredFields({ requiredFields: ["nope"] }).join(","), "firstName,city,age");
  assertEqual("dedupes", CG.resolveRequiredFields({ requiredFields: ["age", "age"] }).join(","), "age");

  // =========================================================================
  // 2) isFieldMissing / getMissingRequiredFields / isProfileComplete.
  // =========================================================================
  console.log("\nmissing-field detection:");
  assertEqual("null profile -> firstName missing", CG.isFieldMissing(null, "firstName"), true);
  assertEqual("empty string missing", CG.isFieldMissing({ firstName: "" }, "firstName"), true);
  assertEqual("whitespace missing", CG.isFieldMissing({ firstName: "   " }, "firstName"), true);
  assertEqual("present name not missing", CG.isFieldMissing({ firstName: "Ada" }, "firstName"), false);
  assertEqual("age as number present", CG.isFieldMissing({ age: 30 }, "age"), false);
  assertEqual("age zero-ish 0 present (finite)", CG.isFieldMissing({ age: 0 }, "age"), false);
  assertEqual("age null missing", CG.isFieldMissing({ age: null }, "age"), true);
  assertEqual("age NaN-string missing", CG.isFieldMissing({ age: "abc" }, "age"), true);
  assertEqual("age numeric string present", CG.isFieldMissing({ age: "30" }, "age"), false);

  {
    const partial = { firstName: "Ada", city: "", age: null };
    const miss = CG.getMissingRequiredFields(partial, required);
    assertEqual("missing = city + age", miss.join(","), "city,age");
    assertEqual("partial not complete", CG.isProfileComplete(partial, required), false);
  }
  {
    const full = { firstName: "Ada", lastName: "Lovelace", city: "London", age: 36, phone: null };
    assertEqual("full profile complete", CG.isProfileComplete(full, required), true);
    assertEqual("full profile no missing", CG.getMissingRequiredFields(full, required).length, 0);
  }

  // =========================================================================
  // 3) buildPatch — only entered fields, trimmed, age coerced to a NUMBER.
  // =========================================================================
  console.log("\nbuildPatch():");
  {
    const patch = CG.buildPatch({ firstName: "  Ada  ", city: "London", age: "36" }, required);
    assertEqual("firstName trimmed", patch.firstName, "Ada");
    assertEqual("city kept", patch.city, "London");
    assertEqual("age coerced to number", patch.age, 36);
    assertEqual("age is a number type", typeof patch.age, "number");
  }
  {
    // Only city entered (name+age blank) -> patch carries ONLY city.
    const patch = CG.buildPatch({ firstName: "", city: "Paris", age: "" }, required);
    assertEqual("only city in patch", Object.keys(patch).join(","), "city");
  }
  {
    // Non-numeric age is sent AS THE RAW STRING so the backend can 400 on it.
    const patch = CG.buildPatch({ age: "notanumber" }, required);
    assertEqual("bad age sent as raw string", patch.age, "notanumber");
    assertEqual("bad age is a string type", typeof patch.age, "string");
  }

  // =========================================================================
  // 4) parseProblemErrors — RFC-7807 errors[] "field: message" shape.
  // =========================================================================
  console.log("\nparseProblemErrors():");
  {
    const parsed = CG.parseProblemErrors({
      type: "about:blank",
      title: "Bad Request",
      status: 400,
      errors: ["age: must be between 13 and 120", "city: must not be blank", "some global rule failed"],
    });
    assertEqual("age field error", parsed.fields.age, "must be between 13 and 120");
    assertEqual("city field error", parsed.fields.city, "must not be blank");
    assertEqual("field-less message -> general", parsed.general.length, 1);
    assertMatch("general text", parsed.general[0], /global rule/);
  }
  {
    // Tolerates object-shaped errors and a bare detail fallback.
    const objForm = CG.parseProblemErrors({ errors: [{ field: "age", message: "too young" }] });
    assertEqual("object-form field error", objForm.fields.age, "too young");
    const detailOnly = CG.parseProblemErrors({ detail: "Validation failed" });
    assertMatch("detail fallback -> general", detailOnly.general[0], /Validation failed/);
  }

  // =========================================================================
  // 5) computeGateView — the pure decision, one group per state.
  // =========================================================================
  console.log("\ncomputeGateView():");
  {
    const inactive = CG.computeGateView({ signedIn: false });
    assertEqual("signed-out -> inactive", inactive.mode, "inactive");
    assertEqual("signed-out hides gate", inactive.showGate, false);

    const loading = CG.computeGateView({ signedIn: true, profileStatus: "loading" });
    assertEqual("loading mode", loading.mode, "loading");
    assertEqual("loading blocks with loader", loading.showLoading, true);
    assertEqual("loading hides gate form", loading.showGate, false);

    const gate = CG.computeGateView({
      signedIn: true,
      profileStatus: "ok",
      profile: { firstName: "Ada", city: "", age: null },
      required: required,
    });
    assertEqual("incomplete -> gate", gate.mode, "gate");
    assertEqual("gate shows form", gate.showGate, true);
    assertEqual("gate missing = city,age", gate.missing.join(","), "city,age");

    const complete = CG.computeGateView({
      signedIn: true,
      profileStatus: "ok",
      profile: { firstName: "Ada", city: "London", age: 36 },
      required: required,
    });
    assertEqual("complete -> no gate", complete.mode, "complete");
    assertEqual("complete hides gate", complete.showGate, false);

    // FAIL-OPEN on any error status.
    ["unauthorized", "http-error", "network-error", "no-session"].forEach((st) => {
      const v = CG.computeGateView({ signedIn: true, profileStatus: st, required: required });
      assertEqual(st + " -> fail-open (no gate)", v.showGate, false);
      assertEqual(st + " -> mode error", v.mode, "error");
    });
  }

  // =========================================================================
  // 6) fetchProfile — classification + request shape (STUBBED fetch/getToken).
  // =========================================================================
  console.log("\nfetchProfile() (stubbed):");
  {
    // No token -> no-session, fetch never called.
    let called = false;
    const r = await CG.fetchProfile({
      getToken: () => Promise.resolve(null),
      apiBaseUrl: "http://127.0.0.1:8080",
      fetchImpl: () => {
        called = true;
        return Promise.resolve(fakeResponse(200, {}));
      },
    });
    assertEqual("no token -> no-session", r.status, "no-session");
    assertEqual("no token -> no fetch", called, false);
  }
  {
    // Success -> ok + Bearer + right URL (trailing slash trimmed).
    let seenUrl = null;
    let seenAuth = null;
    const r = await CG.fetchProfile({
      getToken: () => Promise.resolve("TOKEN123"),
      apiBaseUrl: "http://127.0.0.1:8080/",
      fetchImpl: (url, opts) => {
        seenUrl = url;
        seenAuth = opts.headers.Authorization;
        return Promise.resolve(fakeResponse(200, { firstName: "Ada", city: "London", age: 36 }));
      },
    });
    assertEqual("ok status", r.status, "ok");
    assertEqual("hits /api/v1/me (no //)", seenUrl, "http://127.0.0.1:8080/api/v1/me");
    assertEqual("sends Bearer", seenAuth, "Bearer TOKEN123");
    assertEqual("profile echoed", r.profile.city, "London");
  }
  {
    const r = await CG.fetchProfile({ getToken: () => Promise.resolve("T"), apiBaseUrl: "", fetchImpl: () => Promise.resolve(fakeResponse(401, null)) });
    assertEqual("401 -> unauthorized", r.status, "unauthorized");
  }
  {
    const r = await CG.fetchProfile({ getToken: () => Promise.resolve("T"), apiBaseUrl: "", fetchImpl: () => Promise.resolve(fakeResponse(500, null)) });
    assertEqual("500 -> http-error", r.status, "http-error");
    assertEqual("carries httpStatus", r.httpStatus, 500);
  }
  {
    const r = await CG.fetchProfile({ getToken: () => Promise.resolve("T"), apiBaseUrl: "", fetchImpl: () => Promise.reject(new Error("offline")) });
    assertEqual("throw -> network-error", r.status, "network-error");
  }

  // =========================================================================
  // 7) submitProfile — PATCH classification + request shape.
  // =========================================================================
  console.log("\nsubmitProfile() (stubbed):");
  {
    let seen = null;
    const r = await CG.submitProfile({
      getToken: () => Promise.resolve("TOK"),
      apiBaseUrl: "http://api",
      patch: { city: "Paris", age: 40 },
      fetchImpl: (url, opts) => {
        seen = { url, opts };
        return Promise.resolve(fakeResponse(200, { firstName: "Ada", city: "Paris", age: 40 }));
      },
    });
    assertEqual("ok status", r.status, "ok");
    assertEqual("method PATCH", seen.opts.method, "PATCH");
    assertEqual("PATCH url", seen.url, "http://api/api/v1/me");
    assertEqual("PATCH Bearer", seen.opts.headers.Authorization, "Bearer TOK");
    assertEqual("PATCH content-type json", seen.opts.headers["Content-Type"], "application/json");
    assertEqual("PATCH body is the patch", seen.opts.body, JSON.stringify({ city: "Paris", age: 40 }));
  }
  {
    // 400 -> validation-error with parsed field errors.
    const r = await CG.submitProfile({
      getToken: () => Promise.resolve("TOK"),
      apiBaseUrl: "",
      patch: { age: "abc" },
      fetchImpl: () => Promise.resolve(fakeResponse(400, { status: 400, errors: ["age: must be a number between 13 and 120"] })),
    });
    assertEqual("400 -> validation-error", r.status, "validation-error");
    assertMatch("parsed age error", r.errors.fields.age, /must be a number/);
  }
  {
    const r = await CG.submitProfile({ getToken: () => Promise.resolve("TOK"), apiBaseUrl: "", patch: {}, fetchImpl: () => Promise.resolve(fakeResponse(401, null)) });
    assertEqual("401 -> unauthorized", r.status, "unauthorized");
  }

  // =========================================================================
  // 8) CONTROLLER end-to-end (fake DOM + stubbed fetch) — the ACs.
  // =========================================================================
  console.log("\ncontroller — GATE SHOWS when a required field is missing:");
  {
    const doc = makeFakeDoc();
    const fetchStub = makeFetchStub(() => fakeResponse(200, { firstName: "Ada", city: "", age: null }));
    const gate = CG.createGate({
      document: doc,
      mountNode: doc.body,
      getToken: () => Promise.resolve("TOKEN"),
      apiBaseUrl: "http://api",
      fetchImpl: fetchStub,
    });

    // The loader blocks synchronously (before /me resolves) so the app never flashes.
    const p = gate.check();
    assertEqual("loader blocks while /me in flight", gate.isLoading(), true);
    assert("loading overlay mounted on body", hasChildWithClass(doc.body, "osk-cg-overlay--loading"));

    const view = await p;
    assertEqual("view = gate", view.mode, "gate");
    assertEqual("gate is mounted", gate.isMounted(), true);
    assert("blocking overlay on body", hasChildWithClass(doc.body, "osk-cg-overlay"));
    assert("dialog is role=dialog aria-modal", (() => {
      const dlg = findByClass(doc.body, "osk-cg-dialog");
      return dlg && dlg.getAttribute("role") === "dialog" && dlg.getAttribute("aria-modal") === "true";
    })());
    // Only the MISSING fields get inputs (name is present, so no firstName input).
    const refs = gate._refs();
    assert("no firstName input (already present)", !refs.inputs.firstName);
    assert("city input present", !!refs.inputs.city);
    assert("age input present", !!refs.inputs.age);
    assertEqual("age input is type=number", refs.inputs.age.getAttribute("type"), "number");
    assertEqual("first missing field focused", refs.inputs.city._focused, true);
  }

  console.log("\ncontroller — GATE HIDDEN when all required fields present:");
  {
    const doc = makeFakeDoc();
    const fetchStub = makeFetchStub(() => fakeResponse(200, { firstName: "Ada", city: "London", age: 36 }));
    const gate = CG.createGate({
      document: doc,
      mountNode: doc.body,
      getToken: () => Promise.resolve("TOKEN"),
      apiBaseUrl: "http://api",
      fetchImpl: fetchStub,
    });
    const view = await gate.check();
    assertEqual("view = complete", view.mode, "complete");
    assertEqual("gate NOT mounted", gate.isMounted(), false);
    assert("no overlay left on body", !hasChildWithClass(doc.body, "osk-cg-overlay"));
  }

  console.log("\ncontroller — SUBMIT patches only entered fields, then dismisses on 2xx:");
  {
    const doc = makeFakeDoc();
    // First response = GET /me (incomplete); second = PATCH (success echoing merged profile).
    const responder = (url, opts) => {
      if (opts && opts.method === "PATCH") {
        return fakeResponse(200, { firstName: "Ada", city: "London", age: 36 });
      }
      return fakeResponse(200, { firstName: "Ada", city: "", age: null });
    };
    const fetchStub = makeFetchStub(responder);
    const gate = CG.createGate({
      document: doc,
      mountNode: doc.body,
      getToken: () => Promise.resolve("TOKEN"),
      apiBaseUrl: "http://api",
      fetchImpl: fetchStub,
    });
    await gate.check();
    assertEqual("gate mounted before submit", gate.isMounted(), true);

    // The user fills the two missing fields.
    const refs = gate._refs();
    refs.inputs.city.value = "London";
    refs.inputs.age.value = "36";

    const outcome = await gate.submit();
    assertEqual("submit ok", outcome.status, "ok");

    // The PATCH call carried ONLY the entered fields + the Bearer token, to PATCH /me.
    const patchCall = fetchStub.calls.find((c) => c.opts && c.opts.method === "PATCH");
    assert("a PATCH call was made", !!patchCall);
    assertEqual("PATCH url", patchCall.url, "http://api/api/v1/me");
    assertEqual("PATCH Bearer", patchCall.opts.headers.Authorization, "Bearer TOKEN");
    const sentBody = JSON.parse(patchCall.opts.body);
    assertEqual("body has exactly city+age", Object.keys(sentBody).sort().join(","), "age,city");
    assertEqual("age serialized as number", sentBody.age, 36);
    assert("no firstName in body (never entered)", !("firstName" in sentBody));

    // On success the gate dismisses -> app is revealed.
    assertEqual("gate dismissed after success", gate.isMounted(), false);
    assert("overlay removed from body", !hasChildWithClass(doc.body, "osk-cg-overlay"));
  }

  console.log("\ncontroller — 2xx with EMPTY echo body still dismisses (no re-gate of a present field):");
  {
    // Profile already HAS firstName; only city+age are missing. The PATCH succeeds but the
    // backend echoes an empty body — the gate must still dismiss (merge base = fetched
    // profile, so the pre-existing firstName is not lost).
    const doc = makeFakeDoc();
    const responder = (url, opts) =>
      opts && opts.method === "PATCH"
        ? fakeResponse(200, {}) // empty echo
        : fakeResponse(200, { firstName: "Ada", city: "", age: null });
    const fetchStub = makeFetchStub(responder);
    const gate = CG.createGate({
      document: doc,
      mountNode: doc.body,
      getToken: () => Promise.resolve("TOKEN"),
      apiBaseUrl: "http://api",
      fetchImpl: fetchStub,
    });
    await gate.check();
    const refs = gate._refs();
    assert("form has no firstName field (already present)", !refs.inputs.firstName);
    refs.inputs.city.value = "Berlin";
    refs.inputs.age.value = "44";
    const outcome = await gate.submit();
    assertEqual("submit ok", outcome.status, "ok");
    assertEqual("gate dismissed despite empty echo", gate.isMounted(), false);
  }

  console.log("\ncontroller — INLINE 400 field errors render; gate stays open:");
  {
    const doc = makeFakeDoc();
    const responder = (url, opts) => {
      if (opts && opts.method === "PATCH") {
        return fakeResponse(400, { status: 400, errors: ["age: must be between 13 and 120"] });
      }
      return fakeResponse(200, { firstName: "Ada", city: "", age: null });
    };
    const fetchStub = makeFetchStub(responder);
    const gate = CG.createGate({
      document: doc,
      mountNode: doc.body,
      getToken: () => Promise.resolve("TOKEN"),
      apiBaseUrl: "http://api",
      fetchImpl: fetchStub,
    });
    await gate.check();
    const refs = gate._refs();
    refs.inputs.city.value = "London";
    refs.inputs.age.value = "5"; // too young -> server 400

    const outcome = await gate.submit();
    assertEqual("submit -> validation-error", outcome.status, "validation-error");
    assertEqual("age error shown inline", refs.errorEls.age.hidden, false);
    assertMatch("age error text", refs.errorEls.age.textContent, /between 13 and 120/);
    assertEqual("age input flagged aria-invalid", refs.inputs.age.getAttribute("aria-invalid"), "true");
    assertEqual("city has no error", refs.errorEls.city.hidden, true);
    assertEqual("gate STAYS open on 400", gate.isMounted(), true);
  }

  console.log("\ncontroller — 401 on submit handled (general error; gate stays open):");
  {
    const doc = makeFakeDoc();
    const responder = (url, opts) => {
      if (opts && opts.method === "PATCH") {
        return fakeResponse(401, null);
      }
      return fakeResponse(200, { firstName: "Ada", city: "", age: null });
    };
    const fetchStub = makeFetchStub(responder);
    const gate = CG.createGate({
      document: doc,
      mountNode: doc.body,
      getToken: () => Promise.resolve("TOKEN"),
      apiBaseUrl: "http://api",
      fetchImpl: fetchStub,
    });
    await gate.check();
    const refs = gate._refs();
    refs.inputs.city.value = "London";
    refs.inputs.age.value = "36";

    const outcome = await gate.submit();
    assertEqual("submit -> unauthorized", outcome.status, "unauthorized");
    assertEqual("general error shown", refs.generalError.hidden, false);
    assertMatch("general error mentions session/sign in", refs.generalError.textContent, /session|sign in/i);
    assertEqual("gate STAYS open on 401", gate.isMounted(), true);
  }

  console.log("\ncontroller — sign-out escape hatch tears the gate down:");
  {
    const doc = makeFakeDoc();
    let signedOut = false;
    const fetchStub = makeFetchStub(() => fakeResponse(200, { firstName: "", city: "", age: null }));
    const gate = CG.createGate({
      document: doc,
      mountNode: doc.body,
      getToken: () => Promise.resolve("TOKEN"),
      apiBaseUrl: "http://api",
      fetchImpl: fetchStub,
      onSignOut: () => {
        signedOut = true;
        gate.reset();
      },
    });
    await gate.check();
    assertEqual("gate mounted", gate.isMounted(), true);
    // Click the sign-out button (dispatch the real click listener the controller wired).
    gate._refs().signOutBtn.dispatch("click");
    assertEqual("onSignOut fired", signedOut, true);
    assertEqual("gate torn down", gate.isMounted(), false);
  }

  console.log("\ncontroller — form SUBMIT event path (preventDefault + submit) works:");
  {
    const doc = makeFakeDoc();
    const responder = (url, opts) =>
      opts && opts.method === "PATCH"
        ? fakeResponse(200, { firstName: "Zoe", city: "Rome", age: 22 })
        : fakeResponse(200, { firstName: "", city: "", age: null });
    const fetchStub = makeFetchStub(responder);
    const gate = CG.createGate({
      document: doc,
      mountNode: doc.body,
      getToken: () => Promise.resolve("TOKEN"),
      apiBaseUrl: "http://api",
      fetchImpl: fetchStub,
    });
    await gate.check();
    const refs = gate._refs();
    refs.inputs.firstName.value = "Zoe";
    refs.inputs.city.value = "Rome";
    refs.inputs.age.value = "22";
    let prevented = false;
    refs.form.dispatch("submit", { preventDefault: () => { prevented = true; } });
    // Let the async submit settle.
    await new Promise((res) => setTimeout(res, 0));
    assertEqual("submit event prevented default", prevented, true);
    assert("PATCH fired via form submit", !!fetchStub.calls.find((c) => c.opts && c.opts.method === "PATCH"));
    assertEqual("gate dismissed after form submit success", gate.isMounted(), false);
  }

  // =========================================================================
  // Summary + exit code.
  // =========================================================================
  console.log("\n----------------------------------------");
  console.log("completion-gate simulation: " + passed + " passed, " + failed + " failed");
  if (failed > 0) {
    process.exitCode = 1;
  }
})().catch((err) => {
  console.error("simulation crashed:", err);
  process.exitCode = 1;
});
