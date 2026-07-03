// badges.simulation.cjs — headless Node simulation of the OSK-70 account-state badges.
//
// WHY this exists: live sign-in + a live backend cannot be exercised in CI/locally without
// the human Firebase Web App apiKey (OSK-92) and firebase-scoped ADC (OSK-38). But the
// badge row's core behaviour — mapping the /me flags to verified/unverified/unknown states,
// rendering a compact accessible row, handling the MFA `null` tri-state WITHOUT falsely
// showing "off", and degrading to NOTHING on signed-out / 401 / error — is all pure/DOM-
// shaped logic that web/badges.js exports for exactly this reason. This script requires
// those exports and drives them with a FAKE DOM + a STUBBED fetch + a fake token source,
// asserting that:
//   - computeBadges maps each flag to the right state (email/age booleans; MFA tri-state),
//   - mfaEnabled === null renders as a neutral "unknown" badge (default), NEVER "off",
//   - mfaUnknownMode:"hide" omits the MFA badge while unknown,
//   - controller.load (200) renders one <li> per badge with the right icon/label/data-state
//     AND the accessible aria-label/title, using createElement/textContent only,
//   - signed-out (no token) renders NOTHING (mount cleared + hidden),
//   - load (401) renders nothing (graceful), load (network error) renders nothing,
//   - XSS-safe: innerHTML is NEVER assigned, and a hostile string in an unrelated /me field
//     never reaches the rendered output.
//
// It is a plain assertion harness (no test framework, no deps): it prints each check and
// exits non-zero on the first failure, so it doubles as a CI-friendly gate. It is a `.cjs`
// file OUTSIDE web/e2e/tests/, so Playwright (testDir ./tests) never picks it up.
//
// Run:  node web/e2e/badges.simulation.cjs

"use strict";

const path = require("node:path");
// Require the REAL module under test. badges.js is a classic browser script that also
// exports its pure helpers + the controller factory when required from Node (its browser
// bootstrap is skipped because `window` is undefined here). So this asserts the SAME code
// the page runs.
const badges = require(path.resolve(__dirname, "..", "badges.js"));

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
// Fakes: a tiny DOM whose elements track children + attributes + textContent, and — crucially
// — a POISONED `innerHTML` setter that flips a shared flag if anyone assigns it, so the XSS-
// safe (createElement/textContent-only) contract is machine-checked. No jsdom / browser.
// ---------------------------------------------------------------------------
const domState = { innerHTMLUsed: false };

class FakeElement {
  constructor(tag) {
    this.tagName = String(tag || "").toUpperCase();
    this.children = [];
    this.textContent = "";
    this.className = "";
    this.hidden = false;
    this._attrs = {};
  }
  setAttribute(k, v) { this._attrs[k] = String(v); }
  getAttribute(k) { return Object.prototype.hasOwnProperty.call(this._attrs, k) ? this._attrs[k] : null; }
  appendChild(node) { this.children.push(node); return node; }
  removeChild(node) {
    const i = this.children.indexOf(node);
    if (i !== -1) { this.children.splice(i, 1); }
    return node;
  }
  get firstChild() { return this.children.length ? this.children[0] : null; }
  // Any assignment to innerHTML is a house-style violation (bypasses XSS-safety). Record it
  // so the test can fail hard rather than silently allow an unsafe render path to creep in.
  set innerHTML(_v) { domState.innerHTMLUsed = true; }
  get innerHTML() { return ""; }
}

function makeFakeDoc() {
  return { createElement(tag) { return new FakeElement(tag); } };
}

// handler(url, opts, callIndex) -> { ok?, status, body?, jsonThrows? } ; body is what json() resolves to.
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

// A fetch stub that REJECTS — simulates the backend being unreachable (network/CORS).
function makeRejectingFetch() {
  const fn = function () { return Promise.reject(new Error("network down")); };
  return fn;
}

function tokenSource(token) {
  return function () { return Promise.resolve(token); };
}

// A representative "loaded" /me (mirrors the GET /api/v1/me CurrentUser shape, OSK-73/69).
function sampleMe(overrides) {
  return Object.assign(
    {
      uid: "firebase-uid-123",
      email: "ada@example.com",
      emailVerified: true,
      ageVerified: true,
      mfaEnabled: null, // firebase-admin 9.9.0 exposes no MFA accessor => always null today.
    },
    overrides || {},
  );
}

// Find the rendered <li> for a badge key in a mount, or null.
function findBadge(mount, key) {
  for (let i = 0; i < mount.children.length; i++) {
    if (mount.children[i].getAttribute("data-badge") === key) { return mount.children[i]; }
  }
  return null;
}
// The visible label text of a rendered badge <li> (its second child span).
function badgeLabel(li) { return li && li.children[1] ? li.children[1].textContent : null; }
// The icon glyph of a rendered badge <li> (its first child span).
function badgeIcon(li) { return li && li.children[0] ? li.children[0].textContent : null; }

// ===========================================================================
// 1) PURE: computeBadges — flag -> state mapping (the crux of the ticket).
// ===========================================================================
console.log("\ncomputeBadges() — flag -> state mapping:");
{
  // All verified, MFA on.
  const b = badges.computeBadges(sampleMe({ mfaEnabled: true }));
  assertEqual("three badges rendered", b.length, 3);
  assertEqual("email verified", b[0].state, "verified");
  assertEqual("age verified", b[1].state, "verified");
  assertEqual("mfa on => verified", b[2].state, "verified");
}
{
  // Unverified email + age; MFA off.
  const b = badges.computeBadges(sampleMe({ emailVerified: false, ageVerified: false, mfaEnabled: false }));
  assertEqual("email unverified", b[0].state, "unverified");
  assertEqual("age unverified", b[1].state, "unverified");
  assertEqual("mfa false => unverified (off)", b[2].state, "unverified");
}
{
  // THE KEY CASE: mfaEnabled === null => "unknown", NOT "off". Default mode shows it.
  const b = badges.computeBadges(sampleMe({ mfaEnabled: null }));
  const mfa = b.find(function (x) { return x.key === "mfa"; });
  assertEqual("null MFA => unknown state", mfa.state, "unknown");
  assert("null MFA is NOT unverified/off", mfa.state !== "unverified");
  assertEqual("unknown MFA label reads 'MFA unknown'", mfa.label, "MFA unknown");
}
{
  // undefined / absent MFA field is also treated as unknown (defensive), never off.
  const b = badges.computeBadges({ emailVerified: true, ageVerified: false });
  const mfa = b.find(function (x) { return x.key === "mfa"; });
  assertEqual("absent MFA => unknown", mfa.state, "unknown");
}
{
  // mfaUnknownMode:"hide" => the MFA badge is omitted entirely while unknown.
  const b = badges.computeBadges(sampleMe({ mfaEnabled: null }), { mfaUnknownMode: "hide" });
  assertEqual("hide mode: only two badges", b.length, 2);
  assert("hide mode: no MFA badge", !b.some(function (x) { return x.key === "mfa"; }));
}
{
  // hide mode still shows MFA when it IS known (true/false).
  const b = badges.computeBadges(sampleMe({ mfaEnabled: true }), { mfaUnknownMode: "hide" });
  assert("hide mode: MFA shown when known", b.some(function (x) { return x.key === "mfa"; }));
}

// ===========================================================================
// 2) PURE: resolveMfaUnknownMode — config parsing with a safe default.
// ===========================================================================
console.log("\nresolveMfaUnknownMode() — config default:");
assertEqual("default => show", badges.resolveMfaUnknownMode(undefined), "show");
assertEqual("explicit hide", badges.resolveMfaUnknownMode({ mfaUnknownMode: "hide" }), "hide");
assertEqual("garbage => show", badges.resolveMfaUnknownMode({ mfaUnknownMode: "banana" }), "show");

// ===========================================================================
// 3) CONTROLLER: load (200) — render one <li> per badge with icon/label/aria.
// ===========================================================================
async function run() {
  console.log("\ncontroller.load() — 200 renders the badge row:");
  {
    const doc = makeFakeDoc();
    const mount = new FakeElement("ul");
    const me = sampleMe({ emailVerified: true, ageVerified: false, mfaEnabled: null });
    const fetchStub = makeFetchStub(function () { return { status: 200, body: me }; });
    const c = badges.createBadgesController({
      doc, mount, fetchFn: fetchStub, getIdToken: tokenSource("fake-token"), apiBaseUrl: "http://127.0.0.1:8080",
    });

    const outcome = await c.load();
    assertEqual("load ok", outcome.ok, true);
    assertEqual("GET was called once", fetchStub.calls.length, 1);
    assert("GET hit /api/v1/me", /\/api\/v1\/me$/.test(fetchStub.calls[0].url));
    assert("GET carried a Bearer token", fetchStub.calls[0].opts.headers.Authorization === "Bearer fake-token");
    assertEqual("three <li> badges rendered", mount.children.length, 3);
    assertEqual("mount is visible", mount.hidden, false);

    const email = findBadge(mount, "email");
    assertEqual("email badge state (verified)", email.getAttribute("data-state"), "verified");
    assertEqual("email badge label", badgeLabel(email), "Email verified");
    assertEqual("email badge icon is a check", badgeIcon(email), "✓");
    assertEqual("email badge has accessible name", email.getAttribute("aria-label"), "Email verified");
    assert("email badge has a tooltip", (email.getAttribute("title") || "").length > 0);
    assertEqual("email icon is aria-hidden", email.children[0].getAttribute("aria-hidden"), "true");

    const age = findBadge(mount, "age");
    assertEqual("age badge state (unverified)", age.getAttribute("data-state"), "unverified");
    assertEqual("age badge label", badgeLabel(age), "Age unverified");
    assertEqual("age badge icon is a ring", badgeIcon(age), "○");

    const mfa = findBadge(mount, "mfa");
    assertEqual("mfa badge state (unknown, NOT off)", mfa.getAttribute("data-state"), "unknown");
    assertEqual("mfa badge label", badgeLabel(mfa), "MFA unknown");
    assertEqual("mfa badge icon is a question mark", badgeIcon(mfa), "?");
  }

  // -------------------------------------------------------------------------
  // 4) CONTROLLER: signed-out (no token) — render NOTHING.
  // -------------------------------------------------------------------------
  console.log("\ncontroller.load() — no token renders nothing:");
  {
    const doc = makeFakeDoc();
    const mount = new FakeElement("ul");
    const fetchStub = makeFetchStub(function () { return { status: 200, body: sampleMe() }; });
    const c = badges.createBadgesController({
      doc, mount, fetchFn: fetchStub, getIdToken: tokenSource(null), apiBaseUrl: "",
    });
    const outcome = await c.load();
    assertEqual("outcome not ok (no token)", outcome.ok, false);
    assertEqual("no fetch made without a token", fetchStub.calls.length, 0);
    assertEqual("nothing rendered", mount.children.length, 0);
    assertEqual("mount hidden", mount.hidden, true);
  }

  // -------------------------------------------------------------------------
  // 5) CONTROLLER: load (401) — graceful, render nothing.
  // -------------------------------------------------------------------------
  console.log("\ncontroller.load() — 401 renders nothing:");
  {
    const doc = makeFakeDoc();
    const mount = new FakeElement("ul");
    // Seed a pre-rendered badge so we can prove 401 CLEARS it (not just "never rendered").
    mount.appendChild(new FakeElement("li"));
    mount.hidden = false;
    const fetchStub = makeFetchStub(function () { return { status: 401, body: {} }; });
    const c = badges.createBadgesController({
      doc, mount, fetchFn: fetchStub, getIdToken: tokenSource("stale-token"), apiBaseUrl: "",
    });
    const outcome = await c.load();
    assertEqual("outcome status 401", outcome.status, 401);
    assertEqual("row cleared on 401", mount.children.length, 0);
    assertEqual("mount hidden on 401", mount.hidden, true);
  }

  // -------------------------------------------------------------------------
  // 6) CONTROLLER: load (network error) — graceful, render nothing.
  // -------------------------------------------------------------------------
  console.log("\ncontroller.load() — network error renders nothing:");
  {
    const doc = makeFakeDoc();
    const mount = new FakeElement("ul");
    const c = badges.createBadgesController({
      doc, mount, fetchFn: makeRejectingFetch(), getIdToken: tokenSource("fake-token"), apiBaseUrl: "",
    });
    const outcome = await c.load();
    assertEqual("outcome reason network", outcome.reason, "network");
    assertEqual("nothing rendered on network error", mount.children.length, 0);
    assertEqual("mount hidden on network error", mount.hidden, true);
  }

  // -------------------------------------------------------------------------
  // 7) CONTROLLER: 5xx / other non-ok — graceful, render nothing.
  // -------------------------------------------------------------------------
  console.log("\ncontroller.load() — 500 renders nothing:");
  {
    const doc = makeFakeDoc();
    const mount = new FakeElement("ul");
    const fetchStub = makeFetchStub(function () { return { status: 500, body: {} }; });
    const c = badges.createBadgesController({
      doc, mount, fetchFn: fetchStub, getIdToken: tokenSource("fake-token"), apiBaseUrl: "",
    });
    const outcome = await c.load();
    assertEqual("outcome status 500", outcome.status, 500);
    assertEqual("nothing rendered on 500", mount.children.length, 0);
  }

  // -------------------------------------------------------------------------
  // 8) XSS-safety — no innerHTML anywhere, and a hostile /me string never surfaces.
  // -------------------------------------------------------------------------
  console.log("\nXSS-safety:");
  {
    domState.innerHTMLUsed = false;
    const doc = makeFakeDoc();
    const mount = new FakeElement("ul");
    // A malicious payload smuggled into UNRELATED /me fields. The badges only read the three
    // boolean/null flags, so this must NEVER appear in any rendered text/attribute.
    const hostile = '<img src=x onerror=alert(1)>';
    const me = sampleMe({ emailVerified: true, email: hostile, displayName: hostile, mfaEnabled: true });
    const fetchStub = makeFetchStub(function () { return { status: 200, body: me }; });
    const c = badges.createBadgesController({
      doc, mount, fetchFn: fetchStub, getIdToken: tokenSource("fake-token"), apiBaseUrl: "",
    });
    await c.load();

    assertEqual("innerHTML never assigned during render", domState.innerHTMLUsed, false);
    // Serialise every rendered label + attribute and confirm the hostile string is absent.
    let serialised = "";
    for (let i = 0; i < mount.children.length; i++) {
      const li = mount.children[i];
      serialised += JSON.stringify(li._attrs) + "|" + badgeLabel(li) + "|" + badgeIcon(li) + "||";
    }
    assert("hostile /me field never reaches the DOM", serialised.indexOf("onerror") === -1);
    assert("hostile /me field text absent from render", serialised.indexOf(hostile) === -1);
  }

  // -------------------------------------------------------------------------
  // 9) Spec sanity — the exported spec covers exactly the three required badges.
  // -------------------------------------------------------------------------
  console.log("\nbadge spec:");
  {
    const keys = badges.OSK_BADGE_SPECS.map(function (s) { return s.key; });
    ["email", "age", "mfa"].forEach(function (k) { assert("badge present: " + k, keys.indexOf(k) !== -1); });
    assert("mfa is the tri-state badge", badges.OSK_BADGE_SPECS.some(function (s) { return s.key === "mfa" && s.tri === true; }));
    assert("email is a plain boolean badge", badges.OSK_BADGE_SPECS.some(function (s) { return s.key === "email" && !s.tri; }));
  }

  // -------------------------------------------------------------------------
  // Summary + exit code.
  // -------------------------------------------------------------------------
  console.log("\n----------------------------------------");
  console.log("badges simulation: " + passed + " passed, " + failed + " failed");
  if (failed > 0) { process.exitCode = 1; }
}

run().catch(function (err) {
  console.error("simulation crashed:", err);
  process.exitCode = 1;
});
