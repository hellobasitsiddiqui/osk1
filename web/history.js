// history.js — OpenSkeleton profile change-history view logic (OSK-101).
//
// WHAT: the JS behind the protected `/history` page (web/history.html). For the
//   signed-in user it calls the backend `GET /api/v1/me/history` (OSK-99) with the
//   caller's Firebase ID token (`Authorization: Bearer <token>`) and renders the
//   returned profile-change history — each entry showing the field that changed, its
//   old -> new value, and when it changed — newest first, with a clear empty state and
//   graceful 401 / error / offline handling.
//
// WHY it is a plain (non-module) script AND Node-requirable (UMD-ish), exactly like
//   web/auth.js:
//   - The web pages load their scripts as plain, non-hashed <script src> tags so CD can
//     inject config at deploy time without a bundler. history.html loads this as
//     <script src="/history.js"> at the foot of the body.
//   - Everything that is a PURE data->data transform (normalising the API envelope,
//     formatting a field name / value / timestamp, deciding which panel state to show,
//     building the list DOM against an injectable `document`) is written so it runs
//     identically under `node`/`require` with ZERO browser. auth.js does the same with
//     its guard helpers precisely so the show/hide logic can be unit-simulated headlessly
//     — see web/e2e/history.simulation.cjs, which drives THESE helpers with a fake DOM +
//     a stubbed fetch (live sign-in is human-gated behind the Firebase apiKey, OSK-92).
//   - The pure helpers are exported via `module.exports` when required from Node; the
//     browser bootstrap at the bottom is skipped when `window` is absent.
//
// AUTH REUSE (no duplicated decision logic): the signed-out / not-configured / loading /
//   error GATING is delegated to window.OSKAuth (web/auth.js) via its pure
//   computeGuardView / applyGuardView helpers — the SAME functions app.html uses. This
//   file only adds the history-specific FETCH + RENDER on top of the signed-in state,
//   plus the same sign-in wiring app.html has (so /history is a self-contained protected
//   page: a signed-out visitor can sign in right here — "prompt sign-in via the OSK-74
//   guard pattern").
//
// XSS-safe: every dynamic string (field label, old/new value, timestamp, status text) is
//   written with textContent / createElement only — never innerHTML.
//
// NO SECRETS: the only network call is to the config-driven apiBaseUrl with a short-lived
//   Firebase ID token minted client-side; nothing sensitive is embedded here.

// ===========================================================================
// PURE, ENVIRONMENT-AGNOSTIC HELPERS
// These take plain data and return plain data (or build nodes against an injected
// `document`). They touch no globals, no network and no Firebase, so they run
// identically in the browser and under `node`/`require` — which is what makes the
// render/format/empty-state/401 behaviour unit-simulatable without a browser.
// ===========================================================================

// Human-readable label for a raw field name. Turns the API's camelCase / snake_case /
// kebab-case field key into a sentence-case label: "displayName" -> "Display name",
// "email_address" -> "Email address". Defensive against null/empty.
function oskFormatFieldLabel(field) {
  if (field == null || String(field).trim() === "") {
    return "Field";
  }
  var s = String(field)
    .replace(/[_-]+/g, " ") // snake_case / kebab-case -> spaces
    .replace(/([a-z0-9])([A-Z])/g, "$1 $2"); // camelCase boundary -> space
  s = s.replace(/\s+/g, " ").trim().toLowerCase();
  return s.charAt(0).toUpperCase() + s.slice(1);
}

// Render a change value for display. A `null`/`undefined` value means the field had no
// value (first-ever set, on the OLD side) or was cleared (on the NEW side); an empty
// string is shown distinctly. Everything else is stringified (never the text "null").
function oskFormatValue(value) {
  if (value == null) {
    return "(not set)";
  }
  var s = String(value);
  return s.trim() === "" ? "(empty)" : s;
}

// Format an ISO-8601 instant (the audit event's `changedAt`) as a readable timestamp.
//   - `options.timeZone` — IANA zone (e.g. "UTC"); omitted => the viewer's local zone
//     (nicer for users). The Node simulation passes "UTC" for a deterministic assertion.
//   - `options.locale`   — BCP-47 locale; defaults to "en-GB".
// Falls back to the raw string on an unparseable input, and to ISO on any Intl error, so
// it never throws and never renders "Invalid Date".
function oskFormatTimestamp(iso, options) {
  if (iso == null || String(iso).trim() === "") {
    return "(unknown time)";
  }
  var d = new Date(iso);
  if (isNaN(d.getTime())) {
    return String(iso); // unparseable — show what we were given rather than "Invalid Date"
  }
  var opts = options || {};
  try {
    var fmt = new Intl.DateTimeFormat(opts.locale || "en-GB", {
      year: "numeric",
      month: "short",
      day: "numeric",
      hour: "2-digit",
      minute: "2-digit",
      timeZone: opts.timeZone, // undefined => local zone
    });
    var text = fmt.format(d);
    // Make the zone explicit when we were asked to render in UTC, so a UTC timestamp is
    // never mistaken for local time.
    return opts.timeZone === "UTC" ? text + " UTC" : text;
  } catch (e) {
    return d.toISOString();
  }
}

// Defensively unpack the canonical PagedResponse envelope returned by
// `GET /api/v1/me/history` (OSK-87 shape: { items, page, size, totalElements,
// totalPages }). Tolerates a null/garbage body or a missing / non-array items field so a
// malformed response degrades to an empty history rather than throwing.
function oskNormalizeHistory(payload) {
  var p = payload && typeof payload === "object" ? payload : {};
  var items = Array.isArray(p.items) ? p.items : [];
  return {
    items: items,
    page: typeof p.page === "number" ? p.page : 0,
    size: typeof p.size === "number" ? p.size : items.length,
    totalElements: typeof p.totalElements === "number" ? p.totalElements : items.length,
    totalPages: typeof p.totalPages === "number" ? p.totalPages : (items.length ? 1 : 0),
  };
}

// Sort history entries newest-first by `changedAt`. The backend already returns
// `createdAt DESC` (see MeController#history), but we re-assert it client-side so the
// order is guaranteed regardless of how the page was fetched/merged. Entries with an
// unparseable/absent timestamp sort last, and the sort works on a copy (never mutates
// the caller's array).
function oskSortNewestFirst(items) {
  var arr = Array.isArray(items) ? items.slice() : [];
  arr.sort(function (a, b) {
    var ta = Date.parse((a && a.changedAt) || "");
    var tb = Date.parse((b && b.changedAt) || "");
    if (isNaN(ta) && isNaN(tb)) {
      return 0;
    }
    if (isNaN(ta)) {
      return 1; // a undated -> after b
    }
    if (isNaN(tb)) {
      return -1; // b undated -> after a
    }
    return tb - ta; // newest first
  });
  return arr;
}

// Project one raw ProfileChange entry onto a view-model ready for rendering. Keeps the
// null-vs-value logic (isInitial / isCleared) in one place so the DOM builder and the
// tests agree on it.
function oskFormatChange(entry, options) {
  var e = entry || {};
  return {
    field: e.field == null ? null : String(e.field),
    fieldLabel: oskFormatFieldLabel(e.field),
    oldValue: e.oldValue == null ? null : String(e.oldValue),
    newValue: e.newValue == null ? null : String(e.newValue),
    oldText: oskFormatValue(e.oldValue),
    newText: oskFormatValue(e.newValue),
    isInitial: e.oldValue == null, // field set for the first time (no prior value)
    isCleared: e.newValue == null, // field cleared (no new value)
    actor: e.actor == null ? null : String(e.actor),
    changedAt: e.changedAt || null,
    timestampText: oskFormatTimestamp(e.changedAt, options),
  };
}

// Map a fetch RESULT descriptor (from oskFetchHistory) to a PANEL view descriptor for the
// history region's sub-states. Pure: same input -> same output, so the panel behaviour
// (loading / empty / list / 401 / error / offline) is trivially assertable.
//
// result = { status: "loading"|"ok"|"no-session"|"unauthorized"|"http-error"|"network-error",
//            items?: [...], httpStatus?: number }
//
// returns { mode, showStatus, statusText, showList, showEmpty }
function oskComputeHistoryPanel(result) {
  var r = result || {};
  switch (r.status) {
    case "ok":
      // Signed in and the call succeeded — either show the list or the empty state.
      if (!r.items || r.items.length === 0) {
        return { mode: "empty", showStatus: false, statusText: "", showList: false, showEmpty: true };
      }
      return { mode: "list", showStatus: false, statusText: "", showList: true, showEmpty: false };
    case "no-session":
      // getIdToken() returned null (signed out / not configured). The guard normally shows
      // the sign-in form in this case; this status is a defensive fallback.
      return {
        mode: "no-session",
        showStatus: true,
        statusText: "No active session. Sign in to view your history.",
        showList: false,
        showEmpty: false,
      };
    case "unauthorized":
      // 401 (AC): the token was rejected/expired even though Firebase still holds a
      // session. Prompt a re-auth rather than showing a broken/empty list.
      return {
        mode: "unauthorized",
        showStatus: true,
        statusText: "Your session isn't authorized (401). Try signing out and back in.",
        showList: false,
        showEmpty: false,
      };
    case "http-error":
      return {
        mode: "http-error",
        showStatus: true,
        statusText: "Couldn't load your history (HTTP " + (r.httpStatus || "?") + ").",
        showList: false,
        showEmpty: false,
      };
    case "network-error":
      return {
        mode: "network-error",
        showStatus: true,
        statusText: "Couldn't reach the backend to load your history.",
        showList: false,
        showEmpty: false,
      };
    default:
      // "loading" and anything unexpected: show the loading line, hide list + empty.
      return {
        mode: "loading",
        showStatus: true,
        statusText: "Loading your history…",
        showList: false,
        showEmpty: false,
      };
  }
}

// Fetch the caller's history and classify the outcome into a RESULT descriptor (above).
// Injectable dependencies keep it browser-free and testable:
//   opts.getToken   — () => Promise<string|null> (browser: OSKAuth.getIdToken)
//   opts.apiBaseUrl — backend base URL (browser: config.js apiBaseUrl)
//   opts.fetchImpl  — fetch-compatible fn (browser: window.fetch; test: a stub)
// Never throws: any thrown/rejected error (offline, CORS, backend down, token error)
// resolves to { status: "network-error" }.
function oskFetchHistory(opts) {
  var o = opts || {};
  var getToken = o.getToken;
  var fetchImpl = o.fetchImpl;
  var base = (o.apiBaseUrl || "").replace(/\/+$/, ""); // trim trailing slashes -> no "//api"
  return Promise.resolve()
    .then(function () {
      return typeof getToken === "function" ? getToken() : null;
    })
    .then(function (token) {
      if (!token) {
        return { status: "no-session" };
      }
      return fetchImpl(base + "/api/v1/me/history", {
        headers: {
          Authorization: "Bearer " + token,
          Accept: "application/json",
        },
        cache: "no-store",
      }).then(function (r) {
        if (r.status === 401) {
          return { status: "unauthorized" };
        }
        if (!r.ok) {
          return { status: "http-error", httpStatus: r.status };
        }
        return r.json().then(function (body) {
          var norm = oskNormalizeHistory(body);
          return { status: "ok", items: oskSortNewestFirst(norm.items), total: norm.totalElements };
        });
      });
    })
    .catch(function () {
      return { status: "network-error" };
    });
}

// Build the DOM node for ONE history entry against an injected `document`. Uses only
// createElement / textContent / setAttribute / appendChild, so (a) it is strictly
// XSS-safe and (b) the Node simulation can pass a tiny fake `document` and assert the
// produced structure/text without jsdom or a browser.
function oskBuildEntryNode(doc, entry, options) {
  var vm = oskFormatChange(entry, options);

  var li = doc.createElement("li");
  li.className = "entry";

  // Head row: which field changed + when.
  var head = doc.createElement("div");
  head.className = "entry__head";

  var field = doc.createElement("span");
  field.className = "entry__field";
  field.textContent = vm.fieldLabel;
  head.appendChild(field);

  var time = doc.createElement("time");
  time.className = "entry__time";
  if (vm.changedAt) {
    time.setAttribute("datetime", String(vm.changedAt)); // machine-readable original instant
  }
  time.textContent = vm.timestampText;
  head.appendChild(time);

  li.appendChild(head);

  // Change row: old value -> new value.
  var change = doc.createElement("div");
  change.className = "entry__change";

  var oldEl = doc.createElement("span");
  oldEl.className = "entry__value entry__value--old" + (vm.isInitial ? " entry__value--none" : "");
  oldEl.textContent = vm.oldText;
  change.appendChild(oldEl);

  var arrow = doc.createElement("span");
  arrow.className = "entry__arrow";
  arrow.setAttribute("aria-hidden", "true");
  arrow.textContent = "→"; // → ; the semantic "changed to" is carried by sr-only text below
  change.appendChild(arrow);

  // Screen-reader-only connective so the change reads as "<old> changed to <new>".
  var sr = doc.createElement("span");
  sr.className = "sr-only";
  sr.textContent = " changed to ";
  change.appendChild(sr);

  var newEl = doc.createElement("span");
  newEl.className = "entry__value entry__value--new" + (vm.isCleared ? " entry__value--none" : "");
  newEl.textContent = vm.newText;
  change.appendChild(newEl);

  li.appendChild(change);
  return li;
}

// Render a whole list of entries into `listEl` (an <ol>), newest-first, replacing any
// prior content. Returns the number of rows rendered. `doc` is injectable for testing.
function oskRenderHistoryList(doc, listEl, items, options) {
  if (!doc || !listEl) {
    return 0;
  }
  while (listEl.firstChild) {
    listEl.removeChild(listEl.firstChild);
  }
  var rows = oskSortNewestFirst(items);
  for (var i = 0; i < rows.length; i++) {
    listEl.appendChild(oskBuildEntryNode(doc, rows[i], options));
  }
  return rows.length;
}

// ---------------------------------------------------------------------------
// Node export. Exposed only under CommonJS (`typeof module` is "undefined" in a
// classic browser <script>, so this is a no-op there). Lets web/e2e/history.simulation.cjs
// assert the SAME code the page runs.
// ---------------------------------------------------------------------------
if (typeof module !== "undefined" && module.exports) {
  module.exports = {
    formatFieldLabel: oskFormatFieldLabel,
    formatValue: oskFormatValue,
    formatTimestamp: oskFormatTimestamp,
    normalizeHistory: oskNormalizeHistory,
    sortNewestFirst: oskSortNewestFirst,
    formatChange: oskFormatChange,
    computeHistoryPanel: oskComputeHistoryPanel,
    fetchHistory: oskFetchHistory,
    buildEntryNode: oskBuildEntryNode,
    renderHistoryList: oskRenderHistoryList,
  };
}

// ===========================================================================
// BROWSER BOOTSTRAP
// Runs ONLY in a real browser (guarded by `typeof window`). Wires the OSK-74 auth
// guard (via window.OSKAuth) to gate the page, fetches + renders the history when
// signed in, and wires the sign-in / sign-out affordances. Loaded at the FOOT of
// history.html so the DOM it queries already exists.
// ===========================================================================
if (typeof window !== "undefined" && typeof document !== "undefined") {
  (function () {
    "use strict";

    var cfg = window.__APP_CONFIG__ || {};
    var A = window.OSKAuth;

    // The three guard regions (same contract as app.html): notice / sign-in / protected.
    var els = {
      signIn: document.getElementById("auth-signin"),
      app: document.getElementById("auth-history"),
      notice: document.getElementById("auth-notice"),
      noticeText: document.getElementById("auth-notice-text"),
    };

    // Hard fallback: if auth.js failed to load, window.OSKAuth is absent. Show a plain
    // notice rather than a silently broken page (mirrors app.html).
    if (!A) {
      if (els.notice) {
        els.notice.hidden = false;
      }
      if (els.noticeText) {
        els.noticeText.textContent = "The sign-in module failed to load. Please refresh the page.";
      }
      return;
    }

    // History-panel sub-elements (inside the protected region).
    var statusEl = document.getElementById("history-status");
    var emptyEl = document.getElementById("history-empty");
    var listEl = document.getElementById("history-list");

    // Sign-in region elements (so a signed-out visitor can sign in right here).
    var form = document.getElementById("signin-form");
    var emailEl = document.getElementById("signin-email");
    var passwordEl = document.getElementById("signin-password");
    var submitBtn = document.getElementById("signin-submit");
    var googleBtn = document.getElementById("google-btn");
    var errorEl = document.getElementById("signin-error");
    var signoutBtn = document.getElementById("signout-btn");

    // --- Sign-in helpers (same shape as app.html) -------------------------
    function showError(message) {
      if (!errorEl) {
        return;
      }
      errorEl.textContent = message || "";
      errorEl.hidden = !message;
    }
    function clearError() {
      showError("");
    }
    function setBusy(busy) {
      if (submitBtn) {
        submitBtn.disabled = busy;
      }
      if (googleBtn) {
        googleBtn.disabled = busy;
      }
    }

    // --- History panel apply ---------------------------------------------
    // Given a fetch RESULT descriptor, compute the panel view and reflect it onto the DOM.
    function applyHistoryPanel(result) {
      var panel = oskComputeHistoryPanel(result);
      if (statusEl) {
        statusEl.hidden = !panel.showStatus;
        statusEl.textContent = panel.statusText;
      }
      if (emptyEl) {
        emptyEl.hidden = !panel.showEmpty;
      }
      if (listEl) {
        listEl.hidden = !panel.showList;
        // Render on success; otherwise clear any stale rows.
        oskRenderHistoryList(document, listEl, panel.showList ? result.items : []);
      }
    }

    // Kick off a fresh fetch + render. Shows the loading line first.
    function loadHistory() {
      applyHistoryPanel({ status: "loading" });
      oskFetchHistory({
        getToken: function () {
          return A.getIdToken();
        },
        apiBaseUrl: cfg.apiBaseUrl,
        fetchImpl: window.fetch ? window.fetch.bind(window) : null,
      }).then(applyHistoryPanel);
    }

    // --- Guard render loop (same pattern as app.html) --------------------
    var wasSignedIn = false;
    function render() {
      var view = A.computeGuardView(A.getState());
      A.applyGuardView(view, els);
      if (view.mode === "signed-in") {
        // Load history only on the transition INTO signed-in, not on every repaint.
        if (!wasSignedIn) {
          loadHistory();
        }
        wasSignedIn = true;
      } else {
        wasSignedIn = false;
      }
    }

    render();
    A.onAuthStateChanged(render);
    A.ready.then(render).catch(render);

    // --- Sign-in / sign-out events (reuse OSKAuth) ------------------------
    if (form) {
      form.addEventListener("submit", function (ev) {
        ev.preventDefault();
        clearError();
        var email = emailEl ? emailEl.value.trim() : "";
        var password = passwordEl ? passwordEl.value : "";
        if (!email || !password) {
          showError("Enter your email and password.");
          return;
        }
        setBusy(true);
        A.signInWithEmailPassword(email, password)
          .then(function () {
            clearError(); /* render() runs via the auth-state change */
          })
          .catch(function (err) {
            showError(err && err.message ? err.message : "Sign-in failed.");
          })
          .then(function () {
            setBusy(false);
          });
      });
    }

    if (googleBtn) {
      googleBtn.addEventListener("click", function () {
        clearError();
        setBusy(true);
        A.signInWithGoogle()
          .then(function () {
            clearError();
          })
          .catch(function (err) {
            showError(err && err.message ? err.message : "Google sign-in failed.");
          })
          .then(function () {
            setBusy(false);
          });
      });
    }

    if (signoutBtn) {
      signoutBtn.addEventListener("click", function () {
        A.signOut().catch(function () {
          /* nothing useful to show on a sign-out error */
        });
      });
    }
  })();
}
