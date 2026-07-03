// admin-console.simulation.cjs — headless Node simulation of the OSK-72 admin console.
//
// WHY this exists: a LIVE admin run cannot be exercised in CI/locally without the human
// Firebase Web App apiKey (OSK-92) and an ADMIN test user. But the console's DECISION +
// REQUEST logic is PURE (no DOM, no browser, no Firebase) — web/admin.js exports it for
// exactly this reason. This script requires those pure helpers and drives them with FAKE
// data + a STUBBED fetch, asserting that:
//   - the admin-access state machine gates correctly: not-configured / loading / error /
//     signed-out / checking / signed-in-non-admin / signed-in-ADMIN, plus 401/403 on /me,
//   - applyAdminView toggles a set of fake DOM regions' `.hidden` flags correctly,
//   - the client-side search/filter matches across email/displayName/role/status,
//   - the list-render row model formats fields (dashes, status, created-at),
//   - the API client builds the RIGHT request for each action — method, URL (incl.
//     ?page=&size=), `Authorization: Bearer <token>`, JSON body {role} / {enabled} — and
//   - it handles 401 / 403 / signed-out (no token) / network errors gracefully.
//
// It is a plain assertion harness (no framework, no deps): prints each check and exits
// non-zero on the first failure, so it doubles as a CI-friendly gate. It is a `.cjs` file
// OUTSIDE web/e2e/tests/, so Playwright (testDir ./tests) never picks it up.
//
// Run:  node web/e2e/admin-console.simulation.cjs

"use strict";

const path = require("node:path");
// Require the REAL module under test. admin.js is a classic browser script that also
// exports its pure helpers when required from Node (its browser bootstrap is skipped
// because `window` is undefined here). So this asserts the SAME code the page runs.
const admin = require(path.resolve(__dirname, "..", "admin.js"));

let passed = 0;
let failed = 0;

function assert(label, cond) {
  if (cond) { passed++; console.log("  ok  - " + label); }
  else { failed++; console.log("  FAIL- " + label); }
}
function assertEqual(label, actual, expected) {
  assert(
    label + " (expected " + JSON.stringify(expected) + ", got " + JSON.stringify(actual) + ")",
    actual === expected,
  );
}

// ---------------------------------------------------------------------------
// 1) computeAdminView — one assertion group per admin-access state.
// ---------------------------------------------------------------------------
console.log("\ncomputeAdminView() — NOT CONFIGURED (empty apiKey, the OSK-92 gate):");
{
  const v = admin.computeAdminView({ configured: false, ready: true, user: null });
  assertEqual("mode", v.mode, "not-configured");
  assertEqual("sign-in hidden", v.showSignIn, false);
  assertEqual("console hidden", v.showConsole, false);
  assertEqual("notice shown", v.showNotice, true);
  assert("notice mentions the human gate", /OSK-92/.test(v.noticeText));
}

console.log("\ncomputeAdminView() — LOADING (configured, auth not settled):");
{
  const v = admin.computeAdminView({ configured: true, ready: false, user: null });
  assertEqual("mode", v.mode, "loading");
  assertEqual("console hidden", v.showConsole, false);
  assertEqual("notice shown", v.showNotice, true);
  assertEqual("tone pending", v.noticeTone, "pending");
}

console.log("\ncomputeAdminView() — ERROR (configured, Firebase SDK failed):");
{
  const v = admin.computeAdminView({ configured: true, ready: true, user: null, authError: "network blocked" });
  assertEqual("mode", v.mode, "error");
  assertEqual("console hidden", v.showConsole, false);
  assert("notice surfaces the error text", /network blocked/.test(v.noticeText));
}

console.log("\ncomputeAdminView() — SIGNED OUT (configured, ready, no user):");
{
  const v = admin.computeAdminView({ configured: true, ready: true, user: null });
  assertEqual("mode", v.mode, "signed-out");
  assertEqual("sign-in SHOWN", v.showSignIn, true);
  assertEqual("console hidden", v.showConsole, false);
  assertEqual("notice hidden", v.showNotice, false);
}

console.log("\ncomputeAdminView() — CHECKING (signed in, /me in flight):");
{
  const v = admin.computeAdminView({ configured: true, ready: true, user: { uid: "u1" }, meStatus: "loading" });
  assertEqual("mode", v.mode, "checking");
  assertEqual("console hidden", v.showConsole, false);
  assertEqual("notice shown", v.showNotice, true);
}

console.log("\ncomputeAdminView() — /me 401 (session not authorized):");
{
  const v = admin.computeAdminView({ configured: true, ready: true, user: { uid: "u1" }, meStatus: "unauthorized" });
  assertEqual("mode", v.mode, "unauthorized");
  assertEqual("console hidden", v.showConsole, false);
  assert("notice mentions 401", /401/.test(v.noticeText));
  assertEqual("tone error", v.noticeTone, "error");
}

console.log("\ncomputeAdminView() — signed in but NOT an admin (role USER):");
{
  const v = admin.computeAdminView({ configured: true, ready: true, user: { uid: "u1" }, meStatus: "ok", role: "USER" });
  assertEqual("mode", v.mode, "not-admin");
  assertEqual("sign-in hidden", v.showSignIn, false);
  assertEqual("console hidden", v.showConsole, false);
  assertEqual("notice shown", v.showNotice, true);
  assert("notice explains admin-only", /administrator/i.test(v.noticeText));
}

console.log("\ncomputeAdminView() — /me 403 (refused) also lands on not-admin:");
{
  const v = admin.computeAdminView({ configured: true, ready: true, user: { uid: "u1" }, meStatus: "forbidden" });
  assertEqual("mode", v.mode, "not-admin");
  assertEqual("console hidden", v.showConsole, false);
}

console.log("\ncomputeAdminView() — signed in ADMIN (the console unlocks):");
{
  const v = admin.computeAdminView({ configured: true, ready: true, user: { uid: "u1" }, meStatus: "ok", role: "ADMIN" });
  assertEqual("mode", v.mode, "admin");
  assertEqual("sign-in hidden", v.showSignIn, false);
  assertEqual("console SHOWN", v.showConsole, true);
  assertEqual("notice hidden", v.showNotice, false);
}

// ---------------------------------------------------------------------------
// 2) applyAdminView — drive fake DOM regions and assert `.hidden` + notice text.
// ---------------------------------------------------------------------------
function fakeEls() {
  return {
    signIn: { hidden: true },
    console: { hidden: true },
    notice: { hidden: true },
    noticeText: { textContent: "" },
  };
}

console.log("\napplyAdminView() — ADMIN reveals the console only:");
{
  const els = fakeEls();
  admin.applyAdminView(
    admin.computeAdminView({ configured: true, ready: true, user: { uid: "u1" }, meStatus: "ok", role: "ADMIN" }),
    els,
  );
  assertEqual("sign-in hidden", els.signIn.hidden, true);
  assertEqual("console visible", els.console.hidden, false);
  assertEqual("notice hidden", els.notice.hidden, true);
}

console.log("\napplyAdminView() — NOT-ADMIN reveals the notice, hides the console:");
{
  const els = fakeEls();
  admin.applyAdminView(
    admin.computeAdminView({ configured: true, ready: true, user: { uid: "u1" }, meStatus: "ok", role: "USER" }),
    els,
  );
  assertEqual("console hidden", els.console.hidden, true);
  assertEqual("notice visible", els.notice.hidden, false);
  assert("notice text populated", typeof els.noticeText.textContent === "string" && els.noticeText.textContent.length > 0);
}

console.log("\napplyAdminView() — signed OUT reveals the sign-in form only:");
{
  const els = fakeEls();
  admin.applyAdminView(
    admin.computeAdminView({ configured: true, ready: true, user: null }),
    els,
  );
  assertEqual("sign-in visible", els.signIn.hidden, false);
  assertEqual("console hidden", els.console.hidden, true);
}

console.log("\napplyAdminView() — tolerates a partial DOM (missing regions skipped):");
{
  let threw = false;
  try {
    admin.applyAdminView({ showConsole: true, showSignIn: true, showNotice: true, noticeText: "x" }, { console: { hidden: true } });
  } catch (e) { threw = true; }
  assertEqual("no throw on partial DOM", threw, false);
}

// ---------------------------------------------------------------------------
// 3) applyUserFilters — client-side search/filter over the current page.
// ---------------------------------------------------------------------------
const USERS = [
  { id: "1", email: "alice@example.com", displayName: "Alice Admin", role: "ADMIN", enabled: true, accountType: "REAL", createdAt: "2026-01-01T10:00:00Z" },
  { id: "2", email: "bob@example.com", displayName: "Bob User", role: "USER", enabled: true, accountType: "REAL", createdAt: "2026-02-02T11:30:00Z" },
  { id: "3", email: "carol@test.dev", displayName: null, role: "USER", enabled: false, accountType: "TEST", createdAt: "2026-03-03T09:15:00Z" },
];

console.log("\napplyUserFilters() — free-text query:");
{
  assertEqual("email substring", admin.applyUserFilters(USERS, { query: "bob@" }).length, 1);
  assertEqual("displayName substring (case-insensitive)", admin.applyUserFilters(USERS, { query: "alice" }).length, 1);
  assertEqual("role word matches", admin.applyUserFilters(USERS, { query: "admin" }).length, 1);
  assertEqual("status word 'disabled' matches the disabled user", admin.applyUserFilters(USERS, { query: "disabled" }).length, 1);
  assertEqual("no match => empty", admin.applyUserFilters(USERS, { query: "zzz" }).length, 0);
  assertEqual("empty query => all", admin.applyUserFilters(USERS, { query: "" }).length, 3);
}

console.log("\napplyUserFilters() — role + status facets (and combined):");
{
  assertEqual("role=USER", admin.applyUserFilters(USERS, { role: "USER" }).length, 2);
  assertEqual("role=ADMIN", admin.applyUserFilters(USERS, { role: "ADMIN" }).length, 1);
  assertEqual("status=enabled", admin.applyUserFilters(USERS, { status: "enabled" }).length, 2);
  assertEqual("status=disabled", admin.applyUserFilters(USERS, { status: "disabled" }).length, 1);
  assertEqual("role=USER + status=enabled", admin.applyUserFilters(USERS, { role: "USER", status: "enabled" }).length, 1);
  assertEqual("role=USER + query=carol", admin.applyUserFilters(USERS, { role: "USER", query: "carol" }).length, 1);
  assert("does not mutate input", USERS.length === 3);
  assertEqual("non-array input => []", admin.applyUserFilters(null, {}).length, 0);
}

// ---------------------------------------------------------------------------
// 4) userRowModel — list-render formatting (dashes / status / created-at).
// ---------------------------------------------------------------------------
console.log("\nuserRowModel() — formats a full user:");
{
  const m = admin.userRowModel(USERS[0]);
  assertEqual("email", m.email, "alice@example.com");
  assertEqual("displayName", m.displayName, "Alice Admin");
  assertEqual("role value (for the select)", m.role, "ADMIN");
  assertEqual("enabled", m.enabled, true);
  assertEqual("statusLabel", m.statusLabel, "Enabled");
  assertEqual("toggleLabel for an enabled user is Disable", m.toggleLabel, "Disable");
  assertEqual("nextEnabled for an enabled user is false", m.nextEnabled, false);
  assertEqual("createdAt formatted (UTC, minute precision)", m.createdAt, "2026-01-01 10:00 UTC");
}

console.log("\nuserRowModel() — null fields degrade to dashes, disabled user toggles to Enable:");
{
  const m = admin.userRowModel(USERS[2]);
  assertEqual("null displayName => em dash", m.displayName, "—");
  assertEqual("role defaults to raw", m.role, "USER");
  assertEqual("statusLabel", m.statusLabel, "Disabled");
  assertEqual("toggleLabel for a disabled user is Enable", m.toggleLabel, "Enable");
  assertEqual("nextEnabled for a disabled user is true", m.nextEnabled, true);
}

// ---------------------------------------------------------------------------
// 5) pagerModel — button state + label from server page metadata.
// ---------------------------------------------------------------------------
console.log("\npagerModel():");
{
  const first = admin.pagerModel(0, 20, 45, 3);
  assertEqual("first page: no prev", first.hasPrev, false);
  assertEqual("first page: has next", first.hasNext, true);
  assert("label counts total", /Page 1 of 3/.test(first.label) && /45 users/.test(first.label));

  const last = admin.pagerModel(2, 20, 45, 3);
  assertEqual("last page: has prev", last.hasPrev, true);
  assertEqual("last page: no next", last.hasNext, false);

  const empty = admin.pagerModel(0, 20, 0, 0);
  assertEqual("empty: no prev", empty.hasPrev, false);
  assertEqual("empty: no next", empty.hasNext, false);
  assertEqual("empty label", empty.label, "No users");
}

// ---------------------------------------------------------------------------
// 6) isSelf — self-lockout guard (email match).
// ---------------------------------------------------------------------------
console.log("\nisSelf():");
{
  assertEqual("matches own email (case-insensitive)", admin.isSelf(USERS[0], "ALICE@example.com"), true);
  assertEqual("different email => not self", admin.isSelf(USERS[1], "alice@example.com"), false);
  assertEqual("no meEmail => not self", admin.isSelf(USERS[0], null), false);
}

// ---------------------------------------------------------------------------
// 7) createAdminApi — assert every request's SHAPE + 401/403/no-token handling.
//    A stubbed fetch records each call; a fake token stands in for OSKAuth.getIdToken().
// ---------------------------------------------------------------------------
// Build a fake fetch that returns a scripted response and records the call.
function makeFetch(response) {
  const calls = [];
  const fn = function (url, init) {
    calls.push({ url: url, init: init });
    return Promise.resolve(response);
  };
  fn.calls = calls;
  return fn;
}
// A minimal Response-like object.
function res(status, body) {
  return {
    ok: status >= 200 && status < 300,
    status: status,
    json: function () { return Promise.resolve(body); },
  };
}

console.log("\ncreateAdminApi() — listUsers builds GET /api/v1/admin/users?page=&size= with a Bearer token:");
{
  const page = { items: USERS, page: 1, size: 20, totalElements: 45, totalPages: 3 };
  const fetchImpl = makeFetch(res(200, page));
  const api = admin.createAdminApi({
    baseUrl: "https://api.example.com/",
    getToken: function () { return Promise.resolve("TESTTOKEN"); },
    fetchImpl: fetchImpl,
  });
  api.listUsers({ page: 1, size: 20 }).then(function (r) {
    const c = fetchImpl.calls[0];
    assertEqual("method GET", c.init.method, "GET");
    assertEqual("URL has trimmed base + path + query", c.url, "https://api.example.com/api/v1/admin/users?page=1&size=20");
    assertEqual("Authorization header is a Bearer token", c.init.headers.Authorization, "Bearer TESTTOKEN");
    assertEqual("normalised ok", r.ok, true);
    assertEqual("status", r.status, 200);
    assertEqual("parsed items count", r.data.items.length, 3);
    assertEqual("no body sent on GET", c.init.body, undefined);
  });
}

console.log("\ncreateAdminApi() — setRole builds a PATCH .../{id}/role with a JSON {role} body:");
{
  const updated = { id: "2", email: "bob@example.com", role: "ADMIN", enabled: true, accountType: "REAL", createdAt: "2026-02-02T11:30:00Z" };
  const fetchImpl = makeFetch(res(200, updated));
  const api = admin.createAdminApi({
    baseUrl: "https://api.example.com",
    getToken: function () { return Promise.resolve("TESTTOKEN"); },
    fetchImpl: fetchImpl,
  });
  api.setRole("2", "ADMIN").then(function (r) {
    const c = fetchImpl.calls[0];
    assertEqual("method PATCH", c.init.method, "PATCH");
    assertEqual("URL targets the role sub-resource", c.url, "https://api.example.com/api/v1/admin/users/2/role");
    assertEqual("Content-Type json", c.init.headers["Content-Type"], "application/json");
    assertEqual("Authorization Bearer", c.init.headers.Authorization, "Bearer TESTTOKEN");
    assertEqual("body is exactly {role}", c.init.body, JSON.stringify({ role: "ADMIN" }));
    assertEqual("returns the updated summary", r.data.role, "ADMIN");
  });
}

console.log("\ncreateAdminApi() — setEnabled builds a PATCH .../{id}/enabled with a JSON {enabled} body:");
{
  const updated = { id: "3", email: "carol@test.dev", role: "USER", enabled: false, accountType: "TEST", createdAt: "2026-03-03T09:15:00Z" };
  const fetchImpl = makeFetch(res(200, updated));
  const api = admin.createAdminApi({
    baseUrl: "https://api.example.com",
    getToken: function () { return Promise.resolve("TESTTOKEN"); },
    fetchImpl: fetchImpl,
  });
  api.setEnabled("3", false).then(function (r) {
    const c = fetchImpl.calls[0];
    assertEqual("method PATCH", c.init.method, "PATCH");
    assertEqual("URL targets the enabled sub-resource", c.url, "https://api.example.com/api/v1/admin/users/3/enabled");
    assertEqual("body is exactly {enabled}", c.init.body, JSON.stringify({ enabled: false }));
    assertEqual("returns the updated summary", r.data.enabled, false);
  });
}

console.log("\ncreateAdminApi() — 401 is normalised to error 'unauthorized':");
{
  const fetchImpl = makeFetch(res(401, { title: "Unauthorized" }));
  const api = admin.createAdminApi({ baseUrl: "", getToken: function () { return Promise.resolve("T"); }, fetchImpl: fetchImpl });
  api.listUsers({ page: 0, size: 20 }).then(function (r) {
    assertEqual("ok false", r.ok, false);
    assertEqual("status 401", r.status, 401);
    assertEqual("error token", r.error, "unauthorized");
  });
}

console.log("\ncreateAdminApi() — 403 is normalised to error 'forbidden':");
{
  const fetchImpl = makeFetch(res(403, { title: "Forbidden" }));
  const api = admin.createAdminApi({ baseUrl: "", getToken: function () { return Promise.resolve("T"); }, fetchImpl: fetchImpl });
  api.setRole("9", "ADMIN").then(function (r) {
    assertEqual("ok false", r.ok, false);
    assertEqual("status 403", r.status, 403);
    assertEqual("error token", r.error, "forbidden");
  });
}

console.log("\ncreateAdminApi() — signed out (no token) short-circuits WITHOUT hitting the network:");
{
  const fetchImpl = makeFetch(res(200, {}));
  const api = admin.createAdminApi({ baseUrl: "", getToken: function () { return Promise.resolve(null); }, fetchImpl: fetchImpl });
  api.listUsers({ page: 0, size: 20 }).then(function (r) {
    assertEqual("ok false", r.ok, false);
    assertEqual("error no-token", r.error, "no-token");
    assertEqual("fetch NOT called", fetchImpl.calls.length, 0);
  });
}

console.log("\ncreateAdminApi() — a network failure is caught, never thrown:");
{
  const api = admin.createAdminApi({
    baseUrl: "",
    getToken: function () { return Promise.resolve("T"); },
    fetchImpl: function () { return Promise.reject(new Error("boom")); },
  });
  api.getMe().then(function (r) {
    assertEqual("ok false", r.ok, false);
    assertEqual("status 0", r.status, 0);
    assert("error carries the message", /boom/.test(r.error));
  });
}

// ---------------------------------------------------------------------------
// 8) OSK-85 — view + edit another user's profile.
//    a) getUser + setProfile build the right requests (method, URL, Bearer, JSON body),
//    b) buildProfileUpdate turns raw form input into the SPARSE wire body,
//    c) profileFormModel projects a UserDetail onto the form's string values,
//    d) profileErrors extracts the backend 400 validation messages for inline render.
// ---------------------------------------------------------------------------
const USER_DETAIL = {
  id: "42", email: "dana@example.com", displayName: "Dana Dev", role: "USER",
  enabled: true, accountType: "REAL", createdAt: "2026-04-04T08:00:00Z",
  firstName: "Dana", lastName: "Dev", city: "Berlin", age: 31, phone: "+49123",
  notificationPreference: "PUSH", timezone: "Europe/Berlin", locale: "de-DE",
};

console.log("\ncreateAdminApi() — getUser builds GET /api/v1/admin/users/{id} with a Bearer token:");
{
  const fetchImpl = makeFetch(res(200, USER_DETAIL));
  const api = admin.createAdminApi({
    baseUrl: "https://api.example.com",
    getToken: function () { return Promise.resolve("TESTTOKEN"); },
    fetchImpl: fetchImpl,
  });
  api.getUser("42").then(function (r) {
    const c = fetchImpl.calls[0];
    assertEqual("method GET", c.init.method, "GET");
    assertEqual("URL targets the single-user detail", c.url, "https://api.example.com/api/v1/admin/users/42");
    assertEqual("Authorization Bearer", c.init.headers.Authorization, "Bearer TESTTOKEN");
    assertEqual("no body on GET", c.init.body, undefined);
    assertEqual("detail carries the profile fields", r.data.city, "Berlin");
    assertEqual("detail carries the notification pref", r.data.notificationPreference, "PUSH");
  });
}

console.log("\ncreateAdminApi() — setProfile builds a PATCH .../{id}/profile with the sparse JSON body:");
{
  const updated = Object.assign({}, USER_DETAIL, { city: "Munich" });
  const fetchImpl = makeFetch(res(200, updated));
  const api = admin.createAdminApi({
    baseUrl: "https://api.example.com",
    getToken: function () { return Promise.resolve("TESTTOKEN"); },
    fetchImpl: fetchImpl,
  });
  const bodyObj = { city: "Munich", age: 32 };
  api.setProfile("42", bodyObj).then(function (r) {
    const c = fetchImpl.calls[0];
    assertEqual("method PATCH", c.init.method, "PATCH");
    assertEqual("URL targets the profile sub-resource", c.url, "https://api.example.com/api/v1/admin/users/42/profile");
    assertEqual("Content-Type json", c.init.headers["Content-Type"], "application/json");
    assertEqual("Authorization Bearer", c.init.headers.Authorization, "Bearer TESTTOKEN");
    assertEqual("body is exactly the profile object", c.init.body, JSON.stringify(bodyObj));
    assertEqual("returns the updated detail", r.data.city, "Munich");
  });
}

console.log("\ncreateAdminApi() — setProfile 404 (unknown user) is normalised to 'not-found':");
{
  const fetchImpl = makeFetch(res(404, { title: "Not Found", status: 404 }));
  const api = admin.createAdminApi({ baseUrl: "", getToken: function () { return Promise.resolve("T"); }, fetchImpl: fetchImpl });
  api.setProfile("nope", { city: "X" }).then(function (r) {
    assertEqual("ok false", r.ok, false);
    assertEqual("status 404", r.status, 404);
    assertEqual("error token", r.error, "not-found");
  });
}

console.log("\nbuildProfileUpdate() — omits blanks, trims text, and coerces a valid age to a number:");
{
  const body = admin.buildProfileUpdate({
    displayName: "  Dana D.  ", firstName: "", lastName: "Dev",
    city: "  Munich ", age: "32", phone: "  ", notificationPreference: "PUSH",
    timezone: "Europe/Berlin", locale: "",
  });
  assertEqual("displayName trimmed", body.displayName, "Dana D.");
  assertEqual("blank firstName omitted", "firstName" in body, false);
  assertEqual("lastName kept", body.lastName, "Dev");
  assertEqual("city trimmed", body.city, "Munich");
  assertEqual("age coerced to a number", body.age, 32);
  assert("age is a number type", typeof body.age === "number");
  assertEqual("whitespace-only phone omitted", "phone" in body, false);
  assertEqual("notificationPreference kept", body.notificationPreference, "PUSH");
  assertEqual("timezone kept", body.timezone, "Europe/Berlin");
  assertEqual("empty locale omitted", "locale" in body, false);
}

console.log("\nbuildProfileUpdate() — a non-integer age is sent raw so the backend can 400 it:");
{
  const body = admin.buildProfileUpdate({ age: "not-a-number" });
  assertEqual("age passed through as the raw string", body.age, "not-a-number");
}

console.log("\nprofileFormModel() — projects a UserDetail onto form string values (nulls => \"\"):");
{
  const m = admin.profileFormModel(Object.assign({}, USER_DETAIL, { city: null, age: null, timezone: null }));
  assertEqual("displayName", m.displayName, "Dana Dev");
  assertEqual("null city => empty string", m.city, "");
  assertEqual("null age => empty string", m.age, "");
  assertEqual("age otherwise stringified", admin.profileFormModel(USER_DETAIL).age, "31");
  assertEqual("null notificationPreference falls back to EMAIL", admin.profileFormModel({}).notificationPreference, "EMAIL");
  assertEqual("null timezone => empty string", m.timezone, "");
}

console.log("\nprofileErrors() — extracts the backend 400 validation messages for inline render:");
{
  // The exact ProblemDetail shape GlobalExceptionHandler emits: errors are "field: message".
  const problem = { title: "Bad Request", status: 400, errors: ["age: must be at most 120", "timezone: must be a valid IANA time zone (e.g. Europe/London)"] };
  const msgs = admin.profileErrors({ ok: false, status: 400, data: problem, error: "http-400" });
  assertEqual("two messages extracted", msgs.length, 2);
  assert("first mentions age", /age/.test(msgs[0]));
  assert("second mentions timezone", /timezone/.test(msgs[1]));
}

console.log("\nprofileErrors() — falls back to detail/title/status when there is no errors array:");
{
  assertEqual("uses detail", admin.profileErrors({ ok: false, status: 400, data: { detail: "Malformed request." } })[0], "Malformed request.");
  assertEqual("uses title", admin.profileErrors({ ok: false, status: 400, data: { title: "Bad Request" } })[0], "Bad Request");
  assert("uses status", /HTTP 500/.test(admin.profileErrors({ ok: false, status: 500, data: null })[0]));
  assert("generic when nothing", admin.profileErrors({}).length === 1);
}

// ---------------------------------------------------------------------------
// Summary + exit code. The async assertions above resolve on the microtask queue;
// process.on("exit") runs after they settle, so the tally is complete here.
// ---------------------------------------------------------------------------
process.on("exit", function () {
  console.log("\n----------------------------------------");
  console.log("admin-console simulation: " + passed + " passed, " + failed + " failed");
  if (failed > 0) { process.exitCode = 1; }
});
