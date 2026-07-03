// admin.js — OpenSkeleton admin users console logic (OSK-72).
//
// WHAT: the client-side brains of the admin users console (web/admin.html). It
//   - gates the whole console to signed-in ADMINS: it reuses the OSK-74 auth module
//     (window.OSKAuth from web/auth.js) for Firebase sign-in state, then calls the
//     backend GET /api/v1/me and only reveals the console when the caller's `role`
//     is "ADMIN" (a non-admin / signed-out / not-configured caller sees a clear notice
//     or the sign-in affordance instead — never a broken page),
//   - lists users from GET /api/v1/admin/users with SERVER paging (?page=&size=),
//   - offers CLIENT-side search/filter over the current page (email / displayName /
//     role / status), and
//   - lets an admin change a user's role (PATCH .../{id}/role) or enable/disable them
//     (PATCH .../{id}/enabled), attaching `Authorization: Bearer <idToken>` (from
//     OSKAuth.getIdToken()) to every call and reconciling each row from the updated
//     UserSummary the backend returns.
//
// WHY it is a plain (non-module) script AND Node-requirable (UMD-ish) — identical to
//   auth.js's rationale:
//   - The other web pages load their scripts as plain, non-hashed <script src> tags so
//     CD can inject config at deploy time without a bundler. admin.html loads this the
//     same way: <script src="/admin.js"> after <script src="/auth.js">.
//   - The PURE helpers below (the admin-access state machine, the filter, the row/pager
//     models and the API-client factory) touch no DOM, no globals and no Firebase, so
//     `node --check admin.js` passes and they can be unit-simulated in Node with a fake
//     fetch + fake token — see web/e2e/admin-console.simulation.cjs. Only the BROWSER
//     BOOTSTRAP at the foot (guarded by `typeof window`) wires them to the real DOM.
//
// GRACEFUL DEGRADATION (a hard requirement, mirroring OSK-74): if Firebase auth is not
//   configured (empty apiKey — the committed default until OSK-92), or the caller is not
//   an admin, or the backend returns 401/403, the console NEVER crashes: it shows an
//   explanatory notice and keeps the user list hidden.
//
// XSS-SAFE: every dynamic string (emails, names, notices, errors) is written with
//   textContent / createElement only — never innerHTML.
//
// NO SECRETS: this file embeds nothing sensitive. Access is enforced by Firebase Auth +
//   the backend verifying the ID token and the ADMIN role — never by hiding this file.

// ===========================================================================
// PURE, ENVIRONMENT-AGNOSTIC HELPERS
// These take plain data and return plain data. They touch no globals, no network and
// no DOM, so they run identically in the browser and under `node`/`require` — which is
// what makes the console's decision + request logic unit-testable without a browser.
// ===========================================================================

// The role that unlocks the console. Single source of truth so there is one string to
// change if the backend ever renames it.
var OSK_ADMIN_ROLE = "ADMIN";

// Map a full ADMIN-ACCESS state snapshot to a VIEW descriptor for the console shell.
// Pure: same state in => same plain object out, so it is trivial to assert in a Node
// simulation. It layers an ADMIN check on top of the OSK-74 sign-in states.
//
// state = {
//   configured: boolean,     // is a Firebase apiKey present? (auth not configured until OSK-92)
//   ready:      boolean,     // has auth settled? (SDK loaded + first auth callback, OR not-configured)
//   user:       object|null, // the signed-in Firebase user, or null when signed out
//   authError:  string|null, // Firebase SDK load/init error, if any
//   meStatus:   "idle"|"loading"|"ok"|"unauthorized"|"forbidden"|"error", // GET /api/v1/me outcome
//   role:       string|null, // the caller's role from /api/v1/me when meStatus === "ok"
// }
//
// returns {
//   mode:        "not-configured" | "error" | "loading" | "signed-out"
//              | "checking" | "unauthorized" | "not-admin" | "admin",
//   showSignIn:  boolean,  // render the email/password + Google sign-in affordance
//   showConsole: boolean,  // render the users console (only for a signed-in ADMIN)
//   showNotice:  boolean,  // render the status/notice line
//   noticeText:  string,   // human-readable explanation for the current mode
//   noticeTone:  "pending" | "error" | "info", // drives the notice's accent colour
// }
function oskComputeAdminView(state) {
  var s = state || {};

  // 1) Auth not configured — the committed default until OSK-92. Explain, hide
  //    everything interactive. A first-class state, NOT an error.
  if (!s.configured) {
    return view("not-configured", false, false, true, "info",
      "Sign-in is not configured yet. A Firebase Web App must be registered to enable " +
      "authentication (see OSK-92). The admin console is unavailable until then.");
  }

  // 2) Configured but the Firebase SDK failed to load/init — clear notice, not a blank page.
  if (s.authError) {
    return view("error", false, false, true, "error",
      "Could not initialise sign-in: " + s.authError);
  }

  // 3) Configured, SDK still loading / first auth state unknown.
  if (!s.ready) {
    return view("loading", false, false, true, "pending",
      "Checking your sign-in status…");
  }

  // 4) Ready + signed OUT — show the sign-in affordance, hide the console.
  if (!s.user) {
    return view("signed-out", true, false, false, "info", "");
  }

  // 5) Signed IN — the admin check now hinges on GET /api/v1/me.
  var meStatus = s.meStatus || "idle";

  if (meStatus === "idle" || meStatus === "loading") {
    return view("checking", false, false, true, "pending",
      "Checking your admin access…");
  }
  if (meStatus === "unauthorized") { // 401 — token rejected/expired
    return view("unauthorized", false, false, true, "error",
      "Your session isn't authorized (401). Try signing out and back in.");
  }
  if (meStatus === "forbidden") { // 403 — refused (rare on /me; handled for completeness)
    return view("not-admin", false, false, true, "info",
      "You're signed in, but this console is for administrators only.");
  }
  if (meStatus === "error") { // network/backend down
    return view("error", false, false, true, "error",
      "Couldn't verify your access. Check your connection and try again.");
  }

  // meStatus === "ok": the profile loaded — gate on the role.
  if (s.role === OSK_ADMIN_ROLE) {
    return view("admin", false, true, false, "info", "");
  }
  return view("not-admin", false, false, true, "info",
    "You're signed in, but this console is for administrators only.");

  // Tiny local constructor keeps the branches above readable.
  function view(mode, showSignIn, showConsole, showNotice, noticeTone, noticeText) {
    return {
      mode: mode,
      showSignIn: showSignIn,
      showConsole: showConsole,
      showNotice: showNotice,
      noticeTone: noticeTone,
      noticeText: noticeText,
    };
  }
}

// Apply an admin-access VIEW to a set of DOM-ish regions by toggling their `.hidden`
// flag and (for the notice) its text. Deliberately duck-typed — it only sets `.hidden`
// / `.textContent`, exactly like OSK-74's applyGuardView — so the Node simulation can
// pass `{ hidden: true }` / `{ textContent: "" }` stand-ins and assert the result with
// no jsdom. Any element may be omitted (guarded), so partial DOMs are safe. The notice
// TONE (accent colour) is applied separately in the browser render loop, which has the
// real classList — this pure helper stays minimal, matching the proven pattern.
//
// els = { signIn, console, notice, noticeText } (each optional)
function oskApplyAdminView(vw, els) {
  var v = vw || {};
  var e = els || {};
  if (e.signIn) { e.signIn.hidden = !v.showSignIn; }
  if (e.console) { e.console.hidden = !v.showConsole; }
  if (e.notice) { e.notice.hidden = !v.showNotice; }
  if (e.noticeText) { e.noticeText.textContent = v.noticeText || ""; }
}

// CLIENT-side search/filter over the users on the CURRENT page (server does the paging).
// Pure. Returns a NEW filtered array; never mutates the input.
//
// filters = {
//   query:  string,                 // free-text; case-insensitive substring across
//                                   //   id / email / displayName / role / accountType
//                                   //   and the status word ("enabled"/"disabled")
//   role:   "" | "USER" | "ADMIN",  // exact role match, or "" for any
//   status: "" | "enabled" | "disabled", // enabled-flag match, or "" for any
// }
function oskApplyUserFilters(users, filters) {
  if (!Array.isArray(users)) { return []; }
  var f = filters || {};
  var q = typeof f.query === "string" ? f.query.trim().toLowerCase() : "";
  var wantRole = f.role || "";
  var wantStatus = f.status || "";

  return users.filter(function (u) {
    if (!u) { return false; }

    // Role facet.
    if (wantRole && u.role !== wantRole) { return false; }

    // Status facet.
    if (wantStatus === "enabled" && !u.enabled) { return false; }
    if (wantStatus === "disabled" && u.enabled) { return false; }

    // Free-text query across the human-meaningful fields + the status word.
    if (q) {
      var hay = [
        u.id, u.email, u.displayName, u.role, u.accountType,
        u.enabled ? "enabled" : "disabled",
      ]
        .filter(function (x) { return x != null; })
        .join(" ")
        .toLowerCase();
      if (hay.indexOf(q) === -1) { return false; }
    }
    return true;
  });
}

// Null/empty → an em dash, everything else → its string. Keeps the table free of blank
// cells and literal "null"/"undefined".
function oskDash(v) {
  return (v == null || v === "") ? "—" : String(v);
}

// Format an ISO-8601 instant as a compact, DETERMINISTIC "YYYY-MM-DD HH:mm" (UTC) so the
// Node simulation asserts a stable string (toLocaleString would vary by machine locale).
// Invalid/absent input degrades to a dash / the raw value rather than "Invalid Date".
function oskFormatInstant(iso) {
  if (iso == null || iso === "") { return "—"; }
  var t = Date.parse(iso);
  if (isNaN(t)) { return String(iso); }
  return new Date(t).toISOString().slice(0, 16).replace("T", " ") + " UTC";
}

// Project one UserSummary onto the display MODEL a table row renders from. Pure, so the
// list-render formatting (dashes, status label, created-at) is asserted without a DOM.
function oskUserRowModel(user) {
  var u = user || {};
  return {
    id: u.id,
    email: oskDash(u.email),
    displayName: oskDash(u.displayName),
    role: u.role || "USER",              // raw value for the role <select>
    roleLabel: oskDash(u.role),
    enabled: !!u.enabled,
    statusLabel: u.enabled ? "Enabled" : "Disabled",
    accountType: oskDash(u.accountType),
    createdAt: oskFormatInstant(u.createdAt),
    // The action a disable/enable button performs and its label, precomputed here.
    toggleLabel: u.enabled ? "Disable" : "Enable",
    nextEnabled: !u.enabled,
  };
}

// Is `user` the CURRENTLY SIGNED-IN admin (matched by email)? Used to disable an admin's
// controls on their OWN row so they can't accidentally lock themselves out (demote or
// disable self). Email match is deliberate: /api/v1/me returns the Firebase uid, not the
// admin-list UUID, so email is the only field common to both.
function oskIsSelf(user, meEmail) {
  return !!(
    meEmail &&
    user && typeof user.email === "string" && user.email &&
    user.email.toLowerCase() === String(meEmail).toLowerCase()
  );
}

// Derive the pager's display + button state from the server's page metadata. Pure.
function oskPagerModel(page, size, totalElements, totalPages) {
  var p = page || 0;
  var tp = totalPages || 0;
  var te = totalElements || 0;
  return {
    page: p,
    size: size || 0,
    totalElements: te,
    totalPages: tp,
    hasPrev: p > 0,
    hasNext: p < tp - 1,
    label: te === 0
      ? "No users"
      : "Page " + (p + 1) + " of " + Math.max(tp, 1) +
        " · " + te + " user" + (te === 1 ? "" : "s"),
  };
}

// Build a small ADMIN API client bound to injected dependencies. Pure factory (no
// window/DOM), so a Node simulation can drive it with a FAKE fetch + FAKE token to
// assert every request's shape (method, URL, `Authorization: Bearer`, JSON body) and
// its 401/403 handling — exactly what a live run would send.
//
// deps = {
//   baseUrl:   string,                 // backend base, e.g. config.js apiBaseUrl (trailing "/" trimmed)
//   getToken:  () => Promise<string|null>, // OSKAuth.getIdToken — the fresh Firebase ID token
//   fetchImpl: typeof fetch,           // window.fetch in the browser; a stub in tests
// }
//
// Every method resolves to a NORMALISED result — never throws — so callers branch on a
// plain object instead of try/catch:
//   { ok: boolean, status: number, data: any, error: string|null }
// where error is "no-token" (signed out), "unauthorized" (401), "forbidden" (403),
// "not-found" (404), "http-<n>" (other non-2xx), or "network-error" (fetch threw).
function oskCreateAdminApi(deps) {
  var d = deps || {};
  var base = String(d.baseUrl || "").replace(/\/+$/, "");
  var getToken = typeof d.getToken === "function" ? d.getToken : function () { return Promise.resolve(null); };
  var fetchImpl = typeof d.fetchImpl === "function" ? d.fetchImpl : function () { return Promise.reject(new Error("no fetch")); };

  // Map an HTTP status to our stable error token.
  function httpError(status) {
    if (status === 401) { return "unauthorized"; }
    if (status === 403) { return "forbidden"; }
    if (status === 404) { return "not-found"; }
    return "http-" + status;
  }

  // Read a JSON body defensively (204/no-body/parse-failure → null) so a normalised
  // result is always produced, even for an error response with an empty body.
  function readBody(res) {
    if (!res || res.status === 204) { return Promise.resolve(null); }
    if (typeof res.json !== "function") { return Promise.resolve(null); }
    return res.json().then(function (b) { return b; }, function () { return null; });
  }

  // The single request seam every method funnels through. Attaches the bearer token and
  // normalises the outcome. `body === undefined` means "no request body" (GET).
  function request(method, path, body) {
    return Promise.resolve(getToken()).then(function (token) {
      // Signed out / no token: don't hit the network — report it so the UI can re-gate.
      if (!token) {
        return { ok: false, status: 0, data: null, error: "no-token" };
      }
      var headers = { Accept: "application/json", Authorization: "Bearer " + token };
      var init = { method: method, headers: headers, cache: "no-store" };
      if (body !== undefined) {
        headers["Content-Type"] = "application/json";
        init.body = JSON.stringify(body);
      }
      return fetchImpl(base + path, init).then(function (res) {
        return readBody(res).then(function (data) {
          return {
            ok: !!res.ok,
            status: res.status,
            data: data,
            error: res.ok ? null : httpError(res.status),
          };
        });
      });
    }).catch(function (err) {
      // Network/CORS/backend-down: degrade to a normalised error, never throw.
      return { ok: false, status: 0, data: null, error: (err && err.message) ? err.message : "network-error" };
    });
  }

  // Assemble a ?page=&size= query string from optional numbers.
  function pageQuery(opts) {
    var o = opts || {};
    var parts = [];
    if (o.page != null) { parts.push("page=" + encodeURIComponent(o.page)); }
    if (o.size != null) { parts.push("size=" + encodeURIComponent(o.size)); }
    return parts.length ? "?" + parts.join("&") : "";
  }

  return {
    // The signed-in caller's own profile (incl. `role`) — used to gate the console.
    getMe: function () {
      return request("GET", "/api/v1/me");
    },
    // A page of users (PagedResponse envelope in `data`).
    listUsers: function (opts) {
      return request("GET", "/api/v1/admin/users" + pageQuery(opts));
    },
    // A single user's full detail incl. the OSK-67 profile fields (UserDetail in `data`).
    // Used by the profile editor (OSK-85) to load fresh values before editing.
    getUser: function (id) {
      return request("GET", "/api/v1/admin/users/" + encodeURIComponent(id));
    },
    // Change a user's role. Body shape is exactly the backend's UpdateRoleRequest.
    setRole: function (id, role) {
      return request("PATCH", "/api/v1/admin/users/" + encodeURIComponent(id) + "/role", { role: role });
    },
    // Enable/disable a user. Body shape is exactly the backend's UpdateEnabledRequest.
    setEnabled: function (id, enabled) {
      return request("PATCH", "/api/v1/admin/users/" + encodeURIComponent(id) + "/enabled", { enabled: enabled });
    },
    // Sparse-edit a user's OSK-67 profile fields (OSK-85). Body is the backend's
    // UpdateProfileRequest shape — only the fields present in `profile` are sent; a
    // present field with an unchanged value is a server-side no-op. On a 400 the
    // normalised result carries the RFC-7807 ProblemDetail (incl. `errors`) in `data`.
    setProfile: function (id, profile) {
      return request("PATCH", "/api/v1/admin/users/" + encodeURIComponent(id) + "/profile", profile || {});
    },
  };
}

// ===========================================================================
// PROFILE-EDIT HELPERS (OSK-85) — pure, so the edit form's payload-building and the
// validation-error rendering are unit-simulated in Node with no DOM/browser.
// ===========================================================================

// The editable profile fields, in display order. Single source of truth shared by the
// form-model projection and the payload builder so the two never drift.
var OSK_PROFILE_FIELDS = [
  "displayName", "firstName", "lastName", "city",
  "age", "phone", "notificationPreference", "timezone", "locale",
];

// Project a UserDetail (from GET .../{id}) onto the STRING values the edit form's inputs
// hold. Nulls degrade to "" (an empty input, not the text "null"); age is stringified;
// notificationPreference falls back to the EMAIL default every user has. Pure.
function oskProfileFormModel(user) {
  var u = user || {};
  function s(v) { return v == null ? "" : String(v); }
  return {
    id: u.id,
    email: s(u.email),
    displayName: s(u.displayName),
    firstName: s(u.firstName),
    lastName: s(u.lastName),
    city: s(u.city),
    age: u.age == null ? "" : String(u.age),
    phone: s(u.phone),
    notificationPreference: u.notificationPreference || "EMAIL",
    timezone: s(u.timezone),
    locale: s(u.locale),
  };
}

// Build the SPARSE PATCH body from the form's raw input values. A field is INCLUDED only
// when the admin left meaningful content in it: blank text fields are omitted (the
// backend's sparse convention — omitted = "leave unchanged"; a value cannot be cleared to
// null through this path, matching PATCH /me). `age` is coerced to a number when it parses
// as an integer, else sent as the raw trimmed string so the backend returns a 400 the UI
// can surface (rather than the client silently swallowing it). Pure: same input → same
// object out, so the simulation asserts the exact wire body.
function oskBuildProfileUpdate(input) {
  var i = input || {};
  var out = {};
  OSK_PROFILE_FIELDS.forEach(function (field) {
    var raw = i[field];
    if (raw == null) { return; }
    if (field === "age") {
      var ageStr = String(raw).trim();
      if (ageStr === "") { return; }
      var n = Number(ageStr);
      out.age = Number.isInteger(n) ? n : ageStr;
      return;
    }
    var val = String(raw).trim();
    if (val === "") { return; } // blank → omit (sparse: leave unchanged)
    out[field] = val;
  });
  return out;
}

// Extract the human-readable validation messages to render inline after a failed save.
// The backend's 400 body is an RFC-7807 ProblemDetail whose `errors` array holds
// "field: message" strings (see GlobalExceptionHandler); fall back to `detail`/`title`,
// then a status/generic line, so the UI ALWAYS has something to show. Pure; never throws.
function oskProfileErrors(res) {
  var r = res || {};
  var data = r.data;
  if (data && Array.isArray(data.errors) && data.errors.length) {
    return data.errors.map(String);
  }
  if (data && typeof data.detail === "string" && data.detail) { return [data.detail]; }
  if (data && typeof data.title === "string" && data.title) { return [data.title]; }
  if (r.status) { return ["Request failed (HTTP " + r.status + ")."]; }
  return ["Couldn't save the profile. Please try again."];
}

// ---------------------------------------------------------------------------
// Node export. When this file is `require()`d (CommonJS), expose the pure helpers so the
// console logic can be simulated without a browser. `typeof module` is "undefined" in a
// classic browser <script>, so this block is a no-op there.
// ---------------------------------------------------------------------------
if (typeof module !== "undefined" && module.exports) {
  module.exports = {
    ADMIN_ROLE: OSK_ADMIN_ROLE,
    computeAdminView: oskComputeAdminView,
    applyAdminView: oskApplyAdminView,
    applyUserFilters: oskApplyUserFilters,
    userRowModel: oskUserRowModel,
    pagerModel: oskPagerModel,
    isSelf: oskIsSelf,
    dash: oskDash,
    formatInstant: oskFormatInstant,
    createAdminApi: oskCreateAdminApi,
    // OSK-85 profile-edit helpers.
    profileFields: OSK_PROFILE_FIELDS,
    profileFormModel: oskProfileFormModel,
    buildProfileUpdate: oskBuildProfileUpdate,
    profileErrors: oskProfileErrors,
  };
}

// ===========================================================================
// BROWSER BOOTSTRAP
// Everything below runs ONLY in a real browser (guarded by `typeof window`). It wires
// the pure helpers above to the DOM + window.OSKAuth (web/auth.js): drive the access
// shell, load/paginate the user list, filter it client-side, and mutate role/enabled.
// ===========================================================================
if (typeof window !== "undefined") {
  window.addEventListener("DOMContentLoaded", function () {
    "use strict";

    var cfg = window.__APP_CONFIG__ || {};
    var A = window.OSKAuth;

    // The three access regions the pure view toggles (mirrors app.html's guard).
    var els = {
      signIn: document.getElementById("admin-signin"),
      console: document.getElementById("admin-console"),
      notice: document.getElementById("admin-notice"),
      noticeText: document.getElementById("admin-notice-text"),
    };

    // Hard fallback: if auth.js failed to load, OSKAuth is absent. Show a plain notice
    // instead of a silently broken page.
    if (!A) {
      if (els.notice) { els.notice.hidden = false; }
      if (els.noticeText) {
        els.noticeText.textContent = "The sign-in module failed to load. Please refresh the page.";
      }
      return;
    }

    // Backend base URL from config.js (trailing slash trimmed; "" = same-origin until the
    // backend is wired). The API client attaches OSKAuth.getIdToken() to every call.
    var base = String(cfg.apiBaseUrl || "").replace(/\/+$/, "");
    var api = oskCreateAdminApi({
      baseUrl: base,
      getToken: function () { return A.getIdToken(); },
      fetchImpl: window.fetch.bind(window),
    });

    // ---- Live UI state ----------------------------------------------------
    // `access` mirrors the shape oskComputeAdminView consumes; `list` holds the current
    // server page + client filter state.
    var access = {
      configured: A.isConfigured(),
      ready: false,
      user: null,
      authError: null,
      meStatus: "idle",   // GET /api/v1/me lifecycle
      role: null,
      meEmail: null,      // the signed-in admin's email (for self-lockout guards)
    };
    var list = {
      items: [],
      page: 0,
      size: 20,
      totalElements: 0,
      totalPages: 0,
      loading: false,
      error: null,        // list-level fetch error message, or null
    };
    var filters = { query: "", role: "", status: "" };

    // ---- Element handles (console region) --------------------------------
    var noticeEl = els.notice;
    var listStatusEl = document.getElementById("list-status");
    var listErrorEl = document.getElementById("list-error");
    var tbody = document.getElementById("users-tbody");
    var emptyEl = document.getElementById("empty-state");
    var pagePrevBtn = document.getElementById("page-prev");
    var pageNextBtn = document.getElementById("page-next");
    var pageLabelEl = document.getElementById("page-label");
    var pageSizeSel = document.getElementById("page-size");
    var refreshBtn = document.getElementById("refresh-btn");
    var searchInput = document.getElementById("filter-search");
    var roleFilterSel = document.getElementById("filter-role");
    var statusFilterSel = document.getElementById("filter-status");

    // Sign-in region handles (reused from the OSK-74 pattern).
    var signinForm = document.getElementById("signin-form");
    var signinEmail = document.getElementById("signin-email");
    var signinPassword = document.getElementById("signin-password");
    var signinSubmit = document.getElementById("signin-submit");
    var googleBtn = document.getElementById("google-btn");
    var signinError = document.getElementById("signin-error");

    // Profile editor handles (OSK-85). The panel + its inputs are declared in admin.html
    // and start hidden; they are only ever populated/shown for an admin editing a user.
    var editorEl = document.getElementById("profile-editor");
    var editorForm = document.getElementById("pe-form");
    var editorSubject = document.getElementById("pe-subject");
    var editorErrors = document.getElementById("pe-errors");
    var editorStatus = document.getElementById("pe-status");
    var editorSave = document.getElementById("pe-save");
    var editorCancel = document.getElementById("pe-cancel");
    // The nine editable inputs, keyed by field name (mirrors OSK_PROFILE_FIELDS).
    var editorInputs = {
      displayName: document.getElementById("pe-displayName"),
      firstName: document.getElementById("pe-firstName"),
      lastName: document.getElementById("pe-lastName"),
      city: document.getElementById("pe-city"),
      age: document.getElementById("pe-age"),
      phone: document.getElementById("pe-phone"),
      notificationPreference: document.getElementById("pe-notificationPreference"),
      timezone: document.getElementById("pe-timezone"),
      locale: document.getElementById("pe-locale"),
    };
    // Which user the editor is currently editing (id), and whether a save is in flight.
    var editor = { id: null, saving: false };

    // ---- Small DOM helpers (all XSS-safe: textContent / createElement only) ----

    function clearChildren(node) {
      while (node && node.firstChild) { node.removeChild(node.firstChild); }
    }

    function setListError(message) {
      if (!listErrorEl) { return; }
      listErrorEl.textContent = message || "";
      listErrorEl.hidden = !message;
    }

    function setSigninError(message) {
      if (!signinError) { return; }
      signinError.textContent = message || "";
      signinError.hidden = !message;
    }

    // ---- Access verification (GET /api/v1/me → role) ----------------------

    // Called whenever we (re)need to know if the signed-in caller is an admin. Sets
    // access.meStatus/role/meEmail from the normalised /me result, then repaints. When
    // the caller turns out to be an admin, kicks off the first page load.
    function verifyAdmin() {
      if (!access.user) { return; }
      access.meStatus = "loading";
      render();
      api.getMe().then(function (res) {
        // A later auth change may have signed the user out mid-flight; ignore a stale result.
        if (!access.user) { return; }
        if (res.ok && res.data && typeof res.data === "object") {
          access.meStatus = "ok";
          access.role = res.data.role || null;
          access.meEmail = res.data.email || null;
          render();
          if (access.role === OSK_ADMIN_ROLE) {
            loadPage(0);
          }
          return;
        }
        // Non-2xx / no body: map to a gating state.
        if (res.error === "unauthorized" || res.error === "no-token") {
          access.meStatus = "unauthorized";
        } else if (res.error === "forbidden") {
          access.meStatus = "forbidden";
        } else {
          access.meStatus = "error";
        }
        render();
      });
    }

    // ---- User list loading (server paging) --------------------------------

    function loadPage(page) {
      if (!access.user || access.role !== OSK_ADMIN_ROLE) { return; }
      list.loading = true;
      list.error = null;
      setListError("");
      renderConsole(); // reflect the loading line immediately
      api.listUsers({ page: page, size: list.size }).then(function (res) {
        list.loading = false;
        if (res.ok && res.data && Array.isArray(res.data.items)) {
          list.items = res.data.items;
          list.page = typeof res.data.page === "number" ? res.data.page : page;
          list.size = typeof res.data.size === "number" ? res.data.size : list.size;
          list.totalElements = res.data.totalElements || 0;
          list.totalPages = res.data.totalPages || 0;
          renderConsole();
          return;
        }
        // Lost admin access mid-session (401/403) — re-gate rather than showing a broken list.
        if (res.error === "unauthorized" || res.error === "no-token") {
          access.meStatus = "unauthorized";
          render();
          return;
        }
        if (res.error === "forbidden") {
          verifyAdmin(); // role may have changed under us; re-check
          return;
        }
        list.error = "Couldn't load users" + (res.status ? " (HTTP " + res.status + ")" : "") + ".";
        renderConsole();
      });
    }

    // ---- Mutations (role / enabled) ---------------------------------------

    // Shared post-mutation handler: on success reconcile the row from the returned
    // UserSummary; on 401/403 re-gate; otherwise show a dismissible list error.
    function afterMutation(res, failVerb) {
      if (res.ok && res.data && res.data.id != null) {
        // Reconcile the single row in place (optimistic-with-server-truth).
        for (var i = 0; i < list.items.length; i++) {
          if (list.items[i] && list.items[i].id === res.data.id) {
            list.items[i] = res.data;
            break;
          }
        }
        setListError("");
        renderConsole();
        return;
      }
      if (res.error === "unauthorized" || res.error === "no-token") {
        access.meStatus = "unauthorized";
        render();
        return;
      }
      if (res.error === "forbidden") {
        setListError("That action was refused (403) — re-checking your access…");
        verifyAdmin();
        return;
      }
      setListError("Couldn't " + failVerb + (res.status ? " (HTTP " + res.status + ")" : "") + ".");
      renderConsole(); // re-render restores the control to the true (unchanged) state
    }

    function changeRole(id, role) {
      api.setRole(id, role).then(function (res) { afterMutation(res, "change the role"); });
    }
    function changeEnabled(id, enabled) {
      api.setEnabled(id, enabled).then(function (res) {
        afterMutation(res, enabled ? "enable the user" : "disable the user");
      });
    }

    // ---- Profile editor (OSK-85: view + edit another user's profile) ------

    // Show a list of validation/error strings in the editor's error region (or hide it
    // when there are none). XSS-safe: each message is a text node in its own <li>.
    function renderEditorErrors(messages) {
      if (!editorErrors) { return; }
      clearChildren(editorErrors);
      if (!messages || !messages.length) {
        editorErrors.hidden = true;
        return;
      }
      for (var i = 0; i < messages.length; i++) {
        var li = document.createElement("li");
        li.textContent = messages[i];
        editorErrors.appendChild(li);
      }
      editorErrors.hidden = false;
    }

    function setEditorStatus(message) {
      if (!editorStatus) { return; }
      editorStatus.textContent = message || "";
      editorStatus.hidden = !message;
    }

    // Populate the editor form from a UserDetail-ish object and reveal the panel.
    function fillEditorForm(detail) {
      var model = oskProfileFormModel(detail);
      Object.keys(editorInputs).forEach(function (field) {
        var el = editorInputs[field];
        if (el) { el.value = model[field] != null ? model[field] : ""; }
      });
      if (editorSubject) {
        editorSubject.textContent = "Editing " + oskDash(detail && detail.email);
      }
    }

    // Open the editor for a user: fetch their FRESH detail (so the form reflects the
    // persisted truth, incl. fields not shown in the list) then populate + show the panel.
    function openProfileEditor(user) {
      if (!user || user.id == null) { return; }
      editor.id = user.id;
      editor.saving = false;
      renderEditorErrors(null);
      setEditorStatus("Loading…");
      if (editorEl) { editorEl.hidden = false; }
      // Pre-fill from the row we already have so the form isn't blank while loading.
      fillEditorForm(user);
      if (editorEl && typeof editorEl.scrollIntoView === "function") {
        editorEl.scrollIntoView({ behavior: "smooth", block: "start" });
      }
      api.getUser(user.id).then(function (res) {
        // Ignore a stale load if the admin opened a different row meanwhile / closed it.
        if (editor.id !== user.id) { return; }
        if (res.ok && res.data && res.data.id != null) {
          fillEditorForm(res.data);
          setEditorStatus("");
          return;
        }
        if (res.error === "unauthorized" || res.error === "no-token") {
          closeProfileEditor();
          access.meStatus = "unauthorized";
          render();
          return;
        }
        // Couldn't refresh — keep the row's values (already filled) and note it.
        setEditorStatus("Couldn't load the latest details; editing the values shown.");
      });
    }

    function closeProfileEditor() {
      editor.id = null;
      editor.saving = false;
      renderEditorErrors(null);
      setEditorStatus("");
      if (editorEl) { editorEl.hidden = true; }
    }

    // Save the edited profile: build the sparse body from the inputs and PATCH it. On
    // success reconcile the list row (the returned UserDetail is a superset of the summary)
    // and close; on a 400 render the field errors inline; on 401/403 re-gate.
    function saveProfile() {
      if (editor.id == null || editor.saving) { return; }
      var raw = {};
      Object.keys(editorInputs).forEach(function (field) {
        var el = editorInputs[field];
        raw[field] = el ? el.value : "";
      });
      var payload = oskBuildProfileUpdate(raw);
      var targetId = editor.id;

      editor.saving = true;
      if (editorSave) { editorSave.disabled = true; }
      renderEditorErrors(null);
      setEditorStatus("Saving…");

      api.setProfile(targetId, payload).then(function (res) {
        editor.saving = false;
        if (editorSave) { editorSave.disabled = false; }
        // A later open/close may have moved on; ignore a stale save result.
        if (editor.id !== targetId) { return; }

        if (res.ok && res.data && res.data.id != null) {
          // Reflect the update in the list (display name etc. may have changed).
          for (var i = 0; i < list.items.length; i++) {
            if (list.items[i] && list.items[i].id === res.data.id) {
              list.items[i] = res.data;
              break;
            }
          }
          renderConsole();
          setEditorStatus("Saved.");
          closeProfileEditor();
          return;
        }
        if (res.error === "unauthorized" || res.error === "no-token") {
          closeProfileEditor();
          access.meStatus = "unauthorized";
          render();
          return;
        }
        if (res.error === "forbidden") {
          renderEditorErrors(["That action was refused (403) — re-checking your access…"]);
          setEditorStatus("");
          verifyAdmin();
          return;
        }
        if (res.error === "not-found") {
          renderEditorErrors(["That user no longer exists (404)."]);
          setEditorStatus("");
          return;
        }
        // 400 (validation) or any other non-2xx: show the extracted messages inline.
        renderEditorErrors(oskProfileErrors(res));
        setEditorStatus("");
      });
    }

    // ---- Rendering --------------------------------------------------------

    // Build one <tr> for a user, wiring its role <select> and enable/disable button.
    function buildRow(user) {
      var m = oskUserRowModel(user);
      var self = oskIsSelf(user, access.meEmail);
      var tr = document.createElement("tr");

      // Text cells. `data-label` powers the stacked mobile layout (CSS ::before).
      tr.appendChild(cell("Email", m.email, "email"));
      var nameCell = cell("Name", m.displayName, null);
      if (self) {
        var youTag = document.createElement("span");
        youTag.className = "you-tag";
        youTag.textContent = " (you)";
        nameCell.appendChild(youTag);
      }
      tr.appendChild(nameCell);

      // Role: a <select> so the current role is visible and changeable in one control.
      var roleTd = document.createElement("td");
      roleTd.setAttribute("data-label", "Role");
      var roleSel = document.createElement("select");
      roleSel.className = "row-role";
      roleSel.setAttribute("aria-label", "Role for " + m.email);
      ["USER", "ADMIN"].forEach(function (r) {
        var opt = document.createElement("option");
        opt.value = r;
        opt.textContent = r;
        if (r === m.role) { opt.selected = true; }
        roleSel.appendChild(opt);
      });
      if (self) {
        roleSel.disabled = true;
        roleSel.title = "You can't change your own role here.";
      } else {
        roleSel.addEventListener("change", function () {
          changeRole(m.id, roleSel.value);
        });
      }
      roleTd.appendChild(roleSel);
      tr.appendChild(roleTd);

      // Status pill.
      var statusTd = document.createElement("td");
      statusTd.setAttribute("data-label", "Status");
      var pill = document.createElement("span");
      pill.className = "pill " + (m.enabled ? "pill--on" : "pill--off");
      pill.textContent = m.statusLabel;
      statusTd.appendChild(pill);
      tr.appendChild(statusTd);

      // Account type + created-at.
      tr.appendChild(cell("Type", m.accountType, null));
      tr.appendChild(cell("Created", m.createdAt, null));

      // Actions: the enable/disable toggle.
      var actionsTd = document.createElement("td");
      actionsTd.setAttribute("data-label", "Actions");
      var toggleBtn = document.createElement("button");
      toggleBtn.type = "button";
      toggleBtn.className = "btn btn--ghost row-toggle";
      toggleBtn.textContent = m.toggleLabel;
      if (self) {
        toggleBtn.disabled = true;
        toggleBtn.title = "You can't disable your own account here.";
      } else {
        toggleBtn.addEventListener("click", function () {
          changeEnabled(m.id, m.nextEnabled);
        });
      }
      actionsTd.appendChild(toggleBtn);

      // Edit-profile affordance (OSK-85). Unlike role/disable this is safe on your OWN row
      // (editing profile fields can't lock you out), so it is never disabled for self.
      var editBtn = document.createElement("button");
      editBtn.type = "button";
      editBtn.className = "btn btn--ghost row-edit";
      editBtn.textContent = "Edit profile";
      editBtn.setAttribute("aria-label", "Edit profile for " + m.email);
      editBtn.addEventListener("click", function () {
        openProfileEditor(user);
      });
      actionsTd.appendChild(editBtn);

      tr.appendChild(actionsTd);

      return tr;

      function cell(label, text, extraClass) {
        var td = document.createElement("td");
        td.setAttribute("data-label", label);
        if (extraClass) { td.className = extraClass; }
        td.textContent = text;
        return td;
      }
    }

    // Render the console body: status line, filtered rows, empty states, pager. Only
    // meaningful when the console region is visible (admin), but safe to call anytime.
    function renderConsole() {
      if (!tbody) { return; }

      // Status line.
      if (listStatusEl) {
        if (list.loading) {
          listStatusEl.textContent = "Loading users…";
        } else {
          var visible = oskApplyUserFilters(list.items, filters).length;
          var total = list.totalElements;
          listStatusEl.textContent =
            total === 0 ? "" : ("Showing " + visible + " of " + list.items.length + " on this page · " + total + " total.");
        }
      }

      // Keep the filter controls in sync with state (e.g. after a programmatic reset).
      if (searchInput && searchInput.value !== filters.query) { searchInput.value = filters.query; }
      if (roleFilterSel && roleFilterSel.value !== filters.role) { roleFilterSel.value = filters.role; }
      if (statusFilterSel && statusFilterSel.value !== filters.status) { statusFilterSel.value = filters.status; }

      // Rows (filtered).
      var rows = oskApplyUserFilters(list.items, filters);
      clearChildren(tbody);
      for (var i = 0; i < rows.length; i++) {
        tbody.appendChild(buildRow(rows[i]));
      }

      // Empty states — distinguish "no users at all" from "none match your filters".
      if (emptyEl) {
        if (list.loading || list.error) {
          emptyEl.hidden = true;
        } else if (list.items.length === 0) {
          emptyEl.textContent = "No users yet.";
          emptyEl.hidden = false;
        } else if (rows.length === 0) {
          emptyEl.textContent = "No users match your filters.";
          emptyEl.hidden = false;
        } else {
          emptyEl.hidden = true;
        }
      }

      // Pager.
      var pager = oskPagerModel(list.page, list.size, list.totalElements, list.totalPages);
      if (pageLabelEl) { pageLabelEl.textContent = pager.label; }
      if (pagePrevBtn) { pagePrevBtn.disabled = !pager.hasPrev || list.loading; }
      if (pageNextBtn) { pageNextBtn.disabled = !pager.hasNext || list.loading; }
    }

    // Master render: compute the access view, toggle the regions + notice tone, and
    // (when admin) render the console body.
    function render() {
      var view = oskComputeAdminView(access);
      oskApplyAdminView(view, els);
      // Notice accent colour by tone (the pure helper stays minimal; classList lives here).
      if (noticeEl) {
        noticeEl.classList.remove("notice--pending", "notice--error", "notice--info");
        if (view.showNotice) { noticeEl.classList.add("notice--" + (view.noticeTone || "info")); }
      }
      if (view.showConsole) { renderConsole(); }
    }

    // ---- Wire auth + events ----------------------------------------------

    // React to Firebase auth-state changes: refresh our snapshot, and when a NEW user
    // appears verify their admin access; when they disappear reset the console.
    var lastUid = null;
    function onAuth() {
      var st = A.getState();
      access.configured = st.configured;
      access.ready = st.ready;
      access.user = st.user;
      access.authError = st.error;

      var uid = st.user ? (st.user.uid || "signed-in") : null;
      if (uid !== lastUid) {
        lastUid = uid;
        if (st.user) {
          // New sign-in: (re)verify admin access, which will load the list if admin.
          access.meStatus = "idle";
          access.role = null;
          access.meEmail = null;
          verifyAdmin();
        } else {
          // Signed out: clear everything sensitive and reset the list.
          access.meStatus = "idle";
          access.role = null;
          access.meEmail = null;
          list.items = [];
          list.page = 0;
          list.totalElements = 0;
          list.totalPages = 0;
          setListError("");
          closeProfileEditor(); // tear down any open edit form on sign-out
          render();
        }
      } else {
        render();
      }
    }

    render(); // paint the initial (loading/not-configured) state at once
    A.onAuthStateChanged(onAuth);
    A.ready.then(onAuth).catch(onAuth);

    // Filters (client-side, live).
    if (searchInput) {
      searchInput.addEventListener("input", function () {
        filters.query = searchInput.value;
        renderConsole();
      });
    }
    if (roleFilterSel) {
      roleFilterSel.addEventListener("change", function () {
        filters.role = roleFilterSel.value;
        renderConsole();
      });
    }
    if (statusFilterSel) {
      statusFilterSel.addEventListener("change", function () {
        filters.status = statusFilterSel.value;
        renderConsole();
      });
    }

    // Pager + page size + refresh.
    if (pagePrevBtn) {
      pagePrevBtn.addEventListener("click", function () {
        if (list.page > 0) { loadPage(list.page - 1); }
      });
    }
    if (pageNextBtn) {
      pageNextBtn.addEventListener("click", function () {
        if (list.page < list.totalPages - 1) { loadPage(list.page + 1); }
      });
    }
    if (pageSizeSel) {
      pageSizeSel.addEventListener("change", function () {
        var n = parseInt(pageSizeSel.value, 10);
        if (!isNaN(n) && n > 0) { list.size = n; loadPage(0); }
      });
    }
    if (refreshBtn) {
      refreshBtn.addEventListener("click", function () { loadPage(list.page); });
    }

    // Profile editor save/cancel (OSK-85). Submitting the form saves; cancel just closes.
    if (editorForm) {
      editorForm.addEventListener("submit", function (ev) {
        ev.preventDefault();
        saveProfile();
      });
    }
    if (editorSave) {
      editorSave.addEventListener("click", function (ev) {
        // The button is type=submit inside the form, but guard in case markup changes.
        if (!editorForm) { ev.preventDefault(); saveProfile(); }
      });
    }
    if (editorCancel) {
      editorCancel.addEventListener("click", function () { closeProfileEditor(); });
    }

    // Sign-in affordance (signed-out state) — same shape as app.html's OSK-74 form.
    function setSigninBusy(busy) {
      if (signinSubmit) { signinSubmit.disabled = busy; }
      if (googleBtn) { googleBtn.disabled = busy; }
    }
    if (signinForm) {
      signinForm.addEventListener("submit", function (ev) {
        ev.preventDefault();
        setSigninError("");
        var email = signinEmail ? signinEmail.value.trim() : "";
        var password = signinPassword ? signinPassword.value : "";
        if (!email || !password) { setSigninError("Enter your email and password."); return; }
        setSigninBusy(true);
        A.signInWithEmailPassword(email, password)
          .then(function () { setSigninError(""); })
          .catch(function (err) { setSigninError((err && err.message) ? err.message : "Sign-in failed."); })
          .then(function () { setSigninBusy(false); });
      });
    }
    if (googleBtn) {
      googleBtn.addEventListener("click", function () {
        setSigninError("");
        setSigninBusy(true);
        A.signInWithGoogle()
          .then(function () { setSigninError(""); })
          .catch(function (err) { setSigninError((err && err.message) ? err.message : "Google sign-in failed."); })
          .then(function () { setSigninBusy(false); });
      });
    }
  });
}
