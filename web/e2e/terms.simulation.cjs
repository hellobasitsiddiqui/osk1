// terms.simulation.cjs — headless Node simulation of the OSK-78 terms/privacy gate.
//
// WHY this exists: live sign-in + a live `GET /api/v1/me` / `PATCH /api/v1/me/lifecycle`
// cannot be exercised in CI/locally without the human Firebase Web App apiKey (OSK-92) and
// firebase-scoped ADC (OSK-38). But every piece of terms.js that MATTERS is a PURE
// data->data transform (or builds DOM against an injectable `document` / fetches via an
// injectable `fetchImpl`) — web/terms.js exports them for exactly this reason. This script
// requires those helpers and drives them with FAKE data, a STUBBED fetch and a FAKE DOM,
// asserting the ACs:
//   - the gate SHOWS when the accepted version differs (incl. null = never accepted),
//   - the gate is HIDDEN when the versions match,
//   - a SIGNED-OUT user is never blocked (gate hidden — the OSK-74 guard shows sign-in),
//   - Accept PATCHes /api/v1/me/lifecycle with the RIGHT body { termsAcceptedVersion } +
//     method + Bearer token + Content-Type, and NEVER sends a timestamp (server-stamped),
//   - 401 on accept / lookup is handled gracefully (classified + surfaced, never throws),
//   - HTTP-error / offline are handled, and
//   - buildGate renders the version + Terms/Privacy links XSS-safely (textContent only).
//
// It is a plain assertion harness (no test framework, no deps): it prints each check and
// exits non-zero on the first failure, so it doubles as a CI-friendly gate. It is a `.cjs`
// file OUTSIDE web/e2e/tests/, so Playwright (testDir ./tests) never picks it up.
//
// Run:  node web/e2e/terms.simulation.cjs

"use strict";

const path = require("node:path");
// Require the REAL module under test. terms.js is a classic browser script that also
// exports its pure helpers when required from Node (its browser bootstrap is skipped
// because `window` is undefined here). So this asserts the SAME code the page runs.
const T = require(path.resolve(__dirname, "..", "terms.js"));

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
// A tiny FAKE DOM: just enough of the `document` surface terms.js touches
// (createElement + a node's className / textContent / hidden / setAttribute /
// getAttribute / appendChild / firstChild). No jsdom, no browser. Mirrors how
// history.simulation.cjs passes duck-typed elements around.
// ---------------------------------------------------------------------------
function makeFakeDoc() {
  function makeEl(tag) {
    return {
      tagName: tag,
      className: "",
      textContent: "",
      hidden: false,
      attributes: {},
      childNodes: [],
      get firstChild() {
        return this.childNodes[0] || null;
      },
      appendChild(child) {
        this.childNodes.push(child);
        return child;
      },
      setAttribute(name, value) {
        this.attributes[name] = value;
      },
      getAttribute(name) {
        return Object.prototype.hasOwnProperty.call(this.attributes, name) ? this.attributes[name] : null;
      },
    };
  }
  return { createElement: makeEl };
}

// Depth-first collect every node's textContent — assert rendered text without caring about
// the exact wrapper structure.
function allText(node) {
  let out = node.textContent || "";
  for (const c of node.childNodes || []) {
    out += " " + allText(c);
  }
  return out;
}
// Find the first descendant whose className contains `cls`.
function findByClass(node, cls) {
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

(async function main() {
  const CURRENT = "2026-07-01";

  // -------------------------------------------------------------------------
  // 1) needsTermsAcceptance — the gate DECISION (differs/null => show; match => hide).
  // -------------------------------------------------------------------------
  console.log("\nneedsTermsAcceptance():");
  assertEqual("never accepted (null) => needs", T.needsTermsAcceptance({ termsAcceptedVersion: null }, CURRENT), true);
  assertEqual("field absent => needs", T.needsTermsAcceptance({}, CURRENT), true);
  assertEqual("no user object => needs", T.needsTermsAcceptance(null, CURRENT), true);
  assertEqual("older version => needs", T.needsTermsAcceptance({ termsAcceptedVersion: "2026-01-01" }, CURRENT), true);
  assertEqual("same version => does NOT need", T.needsTermsAcceptance({ termsAcceptedVersion: CURRENT }, CURRENT), false);
  assertEqual("empty current version => inert (never gate)", T.needsTermsAcceptance({ termsAcceptedVersion: null }, ""), false);
  assertEqual("null current version => inert (never gate)", T.needsTermsAcceptance({ termsAcceptedVersion: null }, null), false);

  // -------------------------------------------------------------------------
  // 2) computeTermsView — every state.
  // -------------------------------------------------------------------------
  console.log("\ncomputeTermsView():");
  {
    // Signed OUT => hidden, never blocks (the OSK-74 guard shows the sign-in affordance).
    const out = T.computeTermsView({ signedIn: false, needsAcceptance: true });
    assertEqual("signed-out mode hidden", out.mode, "hidden");
    assertEqual("signed-out hides gate", out.showGate, false);

    // Signed IN + needs acceptance => the gate blocks.
    const gate = T.computeTermsView({ signedIn: true, needsAcceptance: true });
    assertEqual("gate mode", gate.mode, "gate");
    assertEqual("gate shown", gate.showGate, true);
    assertEqual("gate not busy", gate.busy, false);

    // Signed IN + submitting => gate stays up, accept button busy/disabled.
    const submitting = T.computeTermsView({ signedIn: true, needsAcceptance: true, submitting: true });
    assertEqual("submitting mode", submitting.mode, "submitting");
    assertEqual("submitting shows gate", submitting.showGate, true);
    assertEqual("submitting is busy", submitting.busy, true);

    // Signed IN + error => surfaced as errorText on the gate.
    const errored = T.computeTermsView({ signedIn: true, needsAcceptance: true, error: "boom" });
    assertEqual("error surfaced", errored.errorText, "boom");

    // Signed IN + versions match => hidden, app is free.
    const ok = T.computeTermsView({ signedIn: true, needsAcceptance: false });
    assertEqual("accepted mode hidden", ok.mode, "hidden");
    assertEqual("accepted hides gate", ok.showGate, false);
  }

  // -------------------------------------------------------------------------
  // 3) applyTermsView — drive FAKE els; assert .hidden / .disabled / error text.
  // -------------------------------------------------------------------------
  console.log("\napplyTermsView() (fake els):");
  {
    const els = { gate: { hidden: false }, acceptBtn: { disabled: false }, error: { hidden: false, textContent: "x" } };

    T.applyTermsView(T.computeTermsView({ signedIn: true, needsAcceptance: false }), els);
    assertEqual("match => gate hidden", els.gate.hidden, true);
    assertEqual("match => error cleared+hidden", els.error.hidden, true);
    assertEqual("match => error text empty", els.error.textContent, "");

    T.applyTermsView(T.computeTermsView({ signedIn: true, needsAcceptance: true }), els);
    assertEqual("needs => gate shown", els.gate.hidden, false);
    assertEqual("needs => accept enabled", els.acceptBtn.disabled, false);

    T.applyTermsView(T.computeTermsView({ signedIn: true, needsAcceptance: true, submitting: true }), els);
    assertEqual("submitting => accept disabled", els.acceptBtn.disabled, true);

    T.applyTermsView(T.computeTermsView({ signedIn: true, needsAcceptance: true, error: "oops" }), els);
    assertEqual("error => shown", els.error.hidden, false);
    assertEqual("error => text set", els.error.textContent, "oops");

    // Partial DOM (missing elements) must not throw.
    let threw = false;
    try {
      T.applyTermsView({ showGate: true, busy: true, errorText: "x" }, { gate: { hidden: true } });
    } catch (e) {
      threw = true;
    }
    assertEqual("tolerates partial DOM", threw, false);
  }

  // -------------------------------------------------------------------------
  // 4) buildGate — renders the version + links, XSS-safe (textContent), starts hidden.
  // -------------------------------------------------------------------------
  console.log("\nbuildGate() (fake DOM):");
  {
    const doc = makeFakeDoc();
    const g = T.buildGate(doc, { version: CURRENT, termsUrl: "/terms", privacyUrl: "/privacy" });
    assertEqual("overlay starts hidden", g.root.hidden, true);
    assertEqual("role=dialog", g.root.getAttribute("role"), "dialog");
    assertEqual("aria-modal", g.root.getAttribute("aria-modal"), "true");
    // Version shown verbatim.
    assertEqual("version value rendered", g.versionValue.textContent, CURRENT);
    assertMatch("gate text mentions the version", allText(g.root), new RegExp(CURRENT));
    // Links present with the configured hrefs.
    assertEqual("terms link href", g.termsLink.getAttribute("href"), "/terms");
    assertEqual("privacy link href", g.privacyLink.getAttribute("href"), "/privacy");
    assertEqual("terms link opens new tab", g.termsLink.getAttribute("target"), "_blank");
    assertEqual("terms link rel noopener", g.termsLink.getAttribute("rel"), "noopener");
    // Accept button + hidden error present.
    assertMatch("accept button label", g.acceptBtn.textContent, /Accept/);
    assertEqual("error starts hidden", g.error.hidden, true);

    // XSS-safe: a version containing an HTML payload is stored verbatim as textContent,
    // never parsed into markup.
    const g2 = T.buildGate(doc, { version: "<img src=x onerror=alert(1)>" });
    assertEqual("version payload set via textContent verbatim", g2.versionValue.textContent, "<img src=x onerror=alert(1)>");
    // Defaults kick in when no URLs supplied.
    assertEqual("default terms url", g2.termsLink.getAttribute("href"), T.DEFAULT_TERMS_URL);
    assertEqual("default privacy url", g2.privacyLink.getAttribute("href"), T.DEFAULT_PRIVACY_URL);
  }

  // -------------------------------------------------------------------------
  // 5) fetchMe — classification + request shape (STUBBED fetch/getToken).
  // -------------------------------------------------------------------------
  console.log("\nfetchMe() (stubbed fetch + getToken):");

  // (a) No token -> no-session (fetch never called).
  {
    let called = false;
    const r = await T.fetchMe({
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

  // (b) Success -> ok with the user, right URL + Bearer.
  {
    let seenUrl = null;
    let seenAuth = null;
    const r = await T.fetchMe({
      getToken: () => Promise.resolve("TOKEN123"),
      apiBaseUrl: "http://127.0.0.1:8080/", // trailing slash must be trimmed
      fetchImpl: (url, opts) => {
        seenUrl = url;
        seenAuth = opts.headers.Authorization;
        return Promise.resolve(fakeResponse(200, { id: "u1", termsAcceptedVersion: "2026-01-01" }));
      },
    });
    assertEqual("ok status", r.status, "ok");
    assertEqual("url has no double slash + hits /me", seenUrl, "http://127.0.0.1:8080/api/v1/me");
    assertEqual("sends Bearer token", seenAuth, "Bearer TOKEN123");
    assertEqual("returns the user", r.user.termsAcceptedVersion, "2026-01-01");
    // End-to-end: this user needs acceptance against the current version.
    assertEqual("older accepted version => needs gate", T.needsTermsAcceptance(r.user, CURRENT), true);
  }

  // (c) 401 -> unauthorized. (d) 500 -> http-error. (e) throw -> network-error.
  {
    const r401 = await T.fetchMe({ getToken: () => Promise.resolve("T"), apiBaseUrl: "", fetchImpl: () => Promise.resolve(fakeResponse(401, null)) });
    assertEqual("401 -> unauthorized", r401.status, "unauthorized");
    const r500 = await T.fetchMe({ getToken: () => Promise.resolve("T"), apiBaseUrl: "", fetchImpl: () => Promise.resolve(fakeResponse(500, null)) });
    assertEqual("500 -> http-error", r500.status, "http-error");
    assertEqual("500 carries httpStatus", r500.httpStatus, 500);
    const rThrow = await T.fetchMe({ getToken: () => Promise.resolve("T"), apiBaseUrl: "", fetchImpl: () => Promise.reject(new Error("Failed to fetch")) });
    assertEqual("throw -> network-error", rThrow.status, "network-error");
  }

  // -------------------------------------------------------------------------
  // 6) acceptTerms — the CORE AC: right method + URL + body + Bearer, server-stamped.
  // -------------------------------------------------------------------------
  console.log("\nacceptTerms() (stubbed fetch + getToken):");

  // (a) Success -> ok; assert the PATCH request shape precisely.
  {
    let seen = null;
    const r = await T.acceptTerms({
      getToken: () => Promise.resolve("TOKENABC"),
      apiBaseUrl: "http://127.0.0.1:8080/", // trailing slash trimmed
      version: CURRENT,
      fetchImpl: (url, opts) => {
        seen = { url, opts };
        // Simulate the server echoing the updated user with a server-stamped timestamp.
        return Promise.resolve(fakeResponse(200, { id: "u1", termsAcceptedVersion: CURRENT, termsAcceptedAt: "2026-07-03T10:00:00Z" }));
      },
    });
    assertEqual("accept ok", r.status, "ok");
    assertEqual("PATCH method", seen.opts.method, "PATCH");
    assertEqual("hits /api/v1/me/lifecycle (no double slash)", seen.url, "http://127.0.0.1:8080/api/v1/me/lifecycle");
    assertEqual("sends Bearer token", seen.opts.headers.Authorization, "Bearer TOKENABC");
    assertEqual("sends JSON content-type", seen.opts.headers["Content-Type"], "application/json");
    // Body carries ONLY the version — the client never sends a timestamp (server-stamped).
    const bodyObj = JSON.parse(seen.opts.body);
    assertEqual("body termsAcceptedVersion is the current version", bodyObj.termsAcceptedVersion, CURRENT);
    assertEqual("body has exactly one key", Object.keys(bodyObj).length, 1);
    assertEqual("body does NOT include a timestamp", bodyObj.termsAcceptedAt === undefined, true);
    // After acceptance the echoed user no longer needs the gate (idempotent).
    assertEqual("echoed user no longer needs gate", T.needsTermsAcceptance(r.user, CURRENT), false);
  }

  // (b) Success with NO body (endpoint may not echo the user) -> still ok.
  {
    const r = await T.acceptTerms({
      getToken: () => Promise.resolve("T"),
      apiBaseUrl: "",
      version: CURRENT,
      fetchImpl: () => Promise.resolve({ status: 204, ok: true /* no json() */ }),
    });
    assertEqual("204/no-body -> ok", r.status, "ok");
    assertEqual("no-body -> null user", r.user, null);
  }

  // (c) No token -> no-session (PATCH never fires).
  {
    let called = false;
    const r = await T.acceptTerms({
      getToken: () => Promise.resolve(null),
      apiBaseUrl: "",
      version: CURRENT,
      fetchImpl: () => {
        called = true;
        return Promise.resolve(fakeResponse(200, {}));
      },
    });
    assertEqual("no token -> no-session", r.status, "no-session");
    assertEqual("no token -> no PATCH", called, false);
  }

  // (d) 401 -> unauthorized (AC). (e) 500 -> http-error. (f) throw -> network-error.
  {
    const r401 = await T.acceptTerms({ getToken: () => Promise.resolve("T"), apiBaseUrl: "", version: CURRENT, fetchImpl: () => Promise.resolve(fakeResponse(401, null)) });
    assertEqual("401 -> unauthorized", r401.status, "unauthorized");
    const r500 = await T.acceptTerms({ getToken: () => Promise.resolve("T"), apiBaseUrl: "", version: CURRENT, fetchImpl: () => Promise.resolve(fakeResponse(500, null)) });
    assertEqual("500 -> http-error", r500.status, "http-error");
    assertEqual("500 carries httpStatus", r500.httpStatus, 500);
    const rThrow = await T.acceptTerms({ getToken: () => Promise.resolve("T"), apiBaseUrl: "", version: CURRENT, fetchImpl: () => Promise.reject(new Error("offline")) });
    assertEqual("throw -> network-error", rThrow.status, "network-error");
  }

  // -------------------------------------------------------------------------
  // 7) termsErrorText — user-facing wording per status (401 mentions 401, etc.).
  // -------------------------------------------------------------------------
  console.log("\ntermsErrorText():");
  assertMatch("401 wording mentions 401", T.termsErrorText({ status: "unauthorized" }), /401/);
  assertMatch("http-error wording mentions the code", T.termsErrorText({ status: "http-error", httpStatus: 503 }), /503/);
  assertMatch("network wording mentions the backend", T.termsErrorText({ status: "network-error" }), /backend/);
  assertMatch("no-session prompts sign in", T.termsErrorText({ status: "no-session" }), /sign in/i);
  assertEqual("ok status has no error text", T.termsErrorText({ status: "ok" }), "");

  // -------------------------------------------------------------------------
  // 8) termsStyles / z-index — the overlay is above the alert banner (1000).
  // -------------------------------------------------------------------------
  console.log("\ntermsStyles():");
  assert("z-index is above the alert banner (1000)", T.TERMS_Z > 1000);
  assertMatch("styles reference the z-index", T.termsStyles(), new RegExp("z-index:" + T.TERMS_Z));
  assertMatch("styles use shared palette tokens", T.termsStyles(), /var\(--panel/);

  // -------------------------------------------------------------------------
  // Summary + exit code.
  // -------------------------------------------------------------------------
  console.log("\n----------------------------------------");
  console.log("terms simulation: " + passed + " passed, " + failed + " failed");
  if (failed > 0) {
    process.exitCode = 1;
  }
})().catch((err) => {
  console.error("simulation crashed:", err);
  process.exitCode = 1;
});
