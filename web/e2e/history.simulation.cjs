// history.simulation.cjs — headless Node simulation of the OSK-101 profile-history view.
//
// WHY this exists: live sign-in + a live `GET /api/v1/me/history` cannot be exercised in
// CI/locally without the human Firebase Web App apiKey (OSK-92) and firebase-scoped ADC
// (OSK-38). But every piece of history.js that MATTERS for rendering is a PURE data->data
// transform (or builds DOM against an injectable `document` / fetches via an injectable
// `fetchImpl`) — web/history.js exports them for exactly this reason. This script requires
// those helpers and drives them with FAKE data, a STUBBED fetch and a FAKE DOM, asserting:
//   - field-label / value / timestamp formatting (incl. the null / cleared / empty cases),
//   - the PagedResponse envelope is normalised defensively (garbage -> empty history),
//   - entries render NEWEST FIRST regardless of input order,
//   - the panel view is right for loading / list / EMPTY-STATE / 401 / HTTP-error / offline,
//   - fetchHistory classifies token/401/non-ok/success/throw correctly and sends the right
//     Authorization header + URL, and
//   - the list renders XSS-safely (values go through textContent, never innerHTML).
//
// It is a plain assertion harness (no test framework, no deps): it prints each check and
// exits non-zero on the first failure, so it doubles as a CI-friendly gate. It is a `.cjs`
// file OUTSIDE web/e2e/tests/, so Playwright (testDir ./tests) never picks it up.
//
// Run:  node web/e2e/history.simulation.cjs

"use strict";

const path = require("node:path");
// Require the REAL module under test. history.js is a classic browser script that also
// exports its pure helpers when required from Node (its browser bootstrap is skipped
// because `window` is undefined here). So this asserts the SAME code the page runs.
const H = require(path.resolve(__dirname, "..", "history.js"));

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
// A tiny FAKE DOM: just enough of the `document` surface history.js touches
// (createElement + a node's className / textContent / setAttribute / appendChild /
// removeChild / firstChild). No jsdom, no browser. Mirrors how auth-guard.simulation.cjs
// passes duck-typed elements to applyGuardView.
// ---------------------------------------------------------------------------
function makeFakeDoc() {
  function makeEl(tag) {
    return {
      tagName: tag,
      className: "",
      textContent: "",
      attributes: {},
      childNodes: [],
      get firstChild() {
        return this.childNodes[0] || null;
      },
      appendChild(child) {
        this.childNodes.push(child);
        return child;
      },
      removeChild(child) {
        const i = this.childNodes.indexOf(child);
        if (i >= 0) {
          this.childNodes.splice(i, 1);
        }
        return child;
      },
      setAttribute(name, value) {
        this.attributes[name] = value;
      },
    };
  }
  return { createElement: makeEl };
}

// Depth-first collect every node's textContent — lets us assert rendered text without
// caring about the exact wrapper structure.
function allText(node) {
  let out = node.textContent || "";
  for (const c of node.childNodes || []) {
    out += " " + allText(c);
  }
  return out;
}
// Find the first descendant node whose className contains `cls`.
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
  // -------------------------------------------------------------------------
  // 1) formatFieldLabel — camel / snake / kebab -> sentence case.
  // -------------------------------------------------------------------------
  console.log("\nformatFieldLabel():");
  assertEqual("displayName -> Display name", H.formatFieldLabel("displayName"), "Display name");
  assertEqual("email_address -> Email address", H.formatFieldLabel("email_address"), "Email address");
  assertEqual("phone-number -> Phone number", H.formatFieldLabel("phone-number"), "Phone number");
  assertEqual("null -> Field", H.formatFieldLabel(null), "Field");
  assertEqual("empty -> Field", H.formatFieldLabel("  "), "Field");

  // -------------------------------------------------------------------------
  // 2) formatValue — null / empty / normal.
  // -------------------------------------------------------------------------
  console.log("\nformatValue():");
  assertEqual("null -> (not set)", H.formatValue(null), "(not set)");
  assertEqual("undefined -> (not set)", H.formatValue(undefined), "(not set)");
  assertEqual("'' -> (empty)", H.formatValue(""), "(empty)");
  assertEqual("'Ada' -> Ada", H.formatValue("Ada"), "Ada");

  // -------------------------------------------------------------------------
  // 3) formatTimestamp — deterministic in UTC; graceful on junk.
  // -------------------------------------------------------------------------
  console.log("\nformatTimestamp():");
  const ts = H.formatTimestamp("2026-07-02T14:30:00Z", { timeZone: "UTC" });
  assertMatch("has year", ts, /2026/);
  assertMatch("has month", ts, /Jul/);
  assertMatch("has time", ts, /14:30/);
  assertMatch("marks UTC", ts, /UTC/);
  assertEqual("empty -> (unknown time)", H.formatTimestamp(""), "(unknown time)");
  assertEqual("junk -> raw string", H.formatTimestamp("not-a-date"), "not-a-date");

  // -------------------------------------------------------------------------
  // 4) normalizeHistory — defensive envelope unpacking.
  // -------------------------------------------------------------------------
  console.log("\nnormalizeHistory():");
  {
    const n = H.normalizeHistory({ items: [{ field: "displayName" }], page: 0, size: 20, totalElements: 1, totalPages: 1 });
    assertEqual("items length", n.items.length, 1);
    assertEqual("totalElements", n.totalElements, 1);
  }
  assertEqual("null payload -> empty items", H.normalizeHistory(null).items.length, 0);
  assertEqual("missing items -> empty items", H.normalizeHistory({}).items.length, 0);
  assertEqual("non-array items -> empty items", H.normalizeHistory({ items: "oops" }).items.length, 0);

  // -------------------------------------------------------------------------
  // 5) sortNewestFirst — newest first, undated last, non-mutating.
  // -------------------------------------------------------------------------
  console.log("\nsortNewestFirst():");
  {
    const input = [
      { field: "a", changedAt: "2026-01-01T00:00:00Z" },
      { field: "b", changedAt: "2026-06-01T00:00:00Z" },
      { field: "c", changedAt: null },
      { field: "d", changedAt: "2026-03-01T00:00:00Z" },
    ];
    const out = H.sortNewestFirst(input);
    assertEqual("newest first", out[0].field, "b");
    assertEqual("then next", out[1].field, "d");
    assertEqual("then oldest dated", out[2].field, "a");
    assertEqual("undated last", out[3].field, "c");
    assertEqual("input not mutated", input[0].field, "a");
  }

  // -------------------------------------------------------------------------
  // 6) formatChange — isInitial / isCleared + texts.
  // -------------------------------------------------------------------------
  console.log("\nformatChange():");
  {
    const first = H.formatChange({ field: "displayName", oldValue: null, newValue: "Ada", changedAt: "2026-07-02T14:30:00Z" }, { timeZone: "UTC" });
    assertEqual("label", first.fieldLabel, "Display name");
    assertEqual("isInitial (old null)", first.isInitial, true);
    assertEqual("isCleared false", first.isCleared, false);
    assertEqual("oldText not set", first.oldText, "(not set)");
    assertEqual("newText", first.newText, "Ada");

    const cleared = H.formatChange({ field: "displayName", oldValue: "Ada", newValue: null });
    assertEqual("isCleared (new null)", cleared.isCleared, true);
    assertEqual("newText not set", cleared.newText, "(not set)");
  }

  // -------------------------------------------------------------------------
  // 7) computeHistoryPanel — every sub-state.
  // -------------------------------------------------------------------------
  console.log("\ncomputeHistoryPanel():");
  {
    const loading = H.computeHistoryPanel({ status: "loading" });
    assertEqual("loading mode", loading.mode, "loading");
    assertEqual("loading shows status", loading.showStatus, true);
    assertEqual("loading hides list", loading.showList, false);

    const empty = H.computeHistoryPanel({ status: "ok", items: [] });
    assertEqual("empty mode", empty.mode, "empty");
    assertEqual("empty shows empty state", empty.showEmpty, true);
    assertEqual("empty hides list", empty.showList, false);

    const list = H.computeHistoryPanel({ status: "ok", items: [{ field: "displayName" }] });
    assertEqual("list mode", list.mode, "list");
    assertEqual("list shows list", list.showList, true);
    assertEqual("list hides empty", list.showEmpty, false);

    const unauth = H.computeHistoryPanel({ status: "unauthorized" });
    assertEqual("401 mode", unauth.mode, "unauthorized");
    assertMatch("401 text mentions 401", unauth.statusText, /401/);
    assertEqual("401 hides list", unauth.showList, false);

    const httpErr = H.computeHistoryPanel({ status: "http-error", httpStatus: 500 });
    assertMatch("http-error text mentions 500", httpErr.statusText, /500/);

    const netErr = H.computeHistoryPanel({ status: "network-error" });
    assertMatch("network-error text", netErr.statusText, /reach the backend/);

    const noSession = H.computeHistoryPanel({ status: "no-session" });
    assertMatch("no-session prompts sign in", noSession.statusText, /Sign in/);
  }

  // -------------------------------------------------------------------------
  // 8) renderHistoryList — newest first, XSS-safe (textContent), classes.
  // -------------------------------------------------------------------------
  console.log("\nrenderHistoryList() (fake DOM):");
  {
    const doc = makeFakeDoc();
    const list = doc.createElement("ol");
    // Deliberately out of order + a would-be XSS payload in a value.
    const items = [
      { field: "displayName", oldValue: null, newValue: "Ada", actor: "u1", changedAt: "2026-01-01T00:00:00Z" },
      { field: "displayName", oldValue: "Ada", newValue: "<img src=x onerror=alert(1)>", actor: "u1", changedAt: "2026-06-01T00:00:00Z" },
    ];
    const count = H.renderHistoryList(doc, list, items, { timeZone: "UTC" });
    assertEqual("rendered both rows", count, 2);
    assertEqual("list has 2 children", list.childNodes.length, 2);
    assertEqual("each child is an li.entry", list.childNodes[0].className, "entry");
    // Newest first: the June "<img…>" edit renders BEFORE the January one.
    const firstText = allText(list.childNodes[0]);
    assertMatch("newest row first", firstText, /onerror=alert\(1\)/);
    // XSS-safe: the payload is stored as raw textContent on the new-value span, never parsed.
    const newSpan = findByClass(list.childNodes[0], "entry__value--new");
    assertEqual("payload set via textContent verbatim", newSpan.textContent, "<img src=x onerror=alert(1)>");
    // The first-ever set (old null) row marks its old value as "none".
    const janRow = list.childNodes[1];
    const oldSpan = findByClass(janRow, "entry__value--old");
    assertMatch("initial old value flagged --none", oldSpan.className, /entry__value--none/);
    assertEqual("initial old value text", oldSpan.textContent, "(not set)");

    // Re-rendering CLEARS prior rows (no stale accumulation).
    const recount = H.renderHistoryList(doc, list, [], { timeZone: "UTC" });
    assertEqual("re-render empty clears list", recount, 0);
    assertEqual("list emptied", list.childNodes.length, 0);
  }

  // -------------------------------------------------------------------------
  // 9) fetchHistory — classification + request shape (STUBBED fetch/getToken).
  // -------------------------------------------------------------------------
  console.log("\nfetchHistory() (stubbed fetch + getToken):");

  // (a) No token -> no-session (and fetch is never called).
  {
    let called = false;
    const r = await H.fetchHistory({
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

  // (b) Success -> ok, newest-first, and the request carries the Bearer token + right URL.
  {
    let seenUrl = null;
    let seenAuth = null;
    const r = await H.fetchHistory({
      getToken: () => Promise.resolve("TOKEN123"),
      apiBaseUrl: "http://127.0.0.1:8080/", // trailing slash must be trimmed
      fetchImpl: (url, opts) => {
        seenUrl = url;
        seenAuth = opts.headers.Authorization;
        return Promise.resolve(
          fakeResponse(200, {
            items: [
              { field: "displayName", oldValue: null, newValue: "Ada", changedAt: "2026-01-01T00:00:00Z" },
              { field: "displayName", oldValue: "Ada", newValue: "Ada L.", changedAt: "2026-06-01T00:00:00Z" },
            ],
            page: 0,
            size: 20,
            totalElements: 2,
            totalPages: 1,
          }),
        );
      },
    });
    assertEqual("ok status", r.status, "ok");
    assertEqual("url has no double slash + hits /me/history", seenUrl, "http://127.0.0.1:8080/api/v1/me/history");
    assertEqual("sends Bearer token", seenAuth, "Bearer TOKEN123");
    assertEqual("two items", r.items.length, 2);
    assertEqual("items newest first", r.items[0].changedAt, "2026-06-01T00:00:00Z");
    assertEqual("total surfaced", r.total, 2);
  }

  // (c) 401 -> unauthorized.
  {
    const r = await H.fetchHistory({
      getToken: () => Promise.resolve("TOKEN"),
      apiBaseUrl: "",
      fetchImpl: () => Promise.resolve(fakeResponse(401, null)),
    });
    assertEqual("401 -> unauthorized", r.status, "unauthorized");
  }

  // (d) 500 -> http-error with the status code.
  {
    const r = await H.fetchHistory({
      getToken: () => Promise.resolve("TOKEN"),
      apiBaseUrl: "",
      fetchImpl: () => Promise.resolve(fakeResponse(500, null)),
    });
    assertEqual("500 -> http-error", r.status, "http-error");
    assertEqual("carries httpStatus", r.httpStatus, 500);
  }

  // (e) fetch throws (offline/CORS) -> network-error (never throws).
  {
    const r = await H.fetchHistory({
      getToken: () => Promise.resolve("TOKEN"),
      apiBaseUrl: "",
      fetchImpl: () => Promise.reject(new Error("Failed to fetch")),
    });
    assertEqual("fetch throw -> network-error", r.status, "network-error");
  }

  // (f) getToken rejects -> network-error (bucketed, never throws).
  {
    const r = await H.fetchHistory({
      getToken: () => Promise.reject(new Error("token blew up")),
      apiBaseUrl: "",
      fetchImpl: () => Promise.resolve(fakeResponse(200, {})),
    });
    assertEqual("getToken throw -> network-error", r.status, "network-error");
  }

  // -------------------------------------------------------------------------
  // Summary + exit code.
  // -------------------------------------------------------------------------
  console.log("\n----------------------------------------");
  console.log("history simulation: " + passed + " passed, " + failed + " failed");
  if (failed > 0) {
    process.exitCode = 1;
  }
})().catch((err) => {
  console.error("simulation crashed:", err);
  process.exitCode = 1;
});
