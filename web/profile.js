// profile.js — self-service edit-profile logic for the OpenSkeleton web front-end (OSK-86).
//
// WHAT: powers web/profile.html — the signed-in user's own edit-profile screen. It
//   - loads the caller's current profile from the backend `GET /api/v1/me`,
//   - fills an HTML form with the editable OSK-67 fields (firstName, lastName,
//     displayName, city, age, phone, notificationPreference, timezone, locale),
//   - on save, computes a SPARSE diff (only the fields the user actually changed) and
//     sends it as `PATCH /api/v1/me`,
//   - renders the backend's 400 RFC-7807 ProblemDetail field errors INLINE next to the
//     offending inputs, shows a success line on save, and degrades gracefully on
//     401/403/network errors, and
//   - gates the whole page behind Firebase Auth by REUSING the pure guard helpers that
//     web/auth.js already exports (computeGuardView / applyGuardView) — the exact same
//     show/hide decision logic the OSK-74 /app page uses. auth.js is NOT modified.
//
// WHY it is a plain (non-module) script AND Node-requirable (like auth.js): the other web
//   pages load their scripts as plain, non-hashed <script src> tags so CD can inject
//   config at deploy time without a bundler. profile.js follows that convention
//   (<script src="/profile.js"> in profile.html). It ALSO exports its pure helpers and a
//   dependency-injected "controller" factory via module.exports when required from Node,
//   so the load / dirty-tracking / patch-only-changed / validation-error-render / 401
//   behaviours can be unit-simulated with a FAKE DOM + a STUBBED fetch and ZERO browser —
//   see web/e2e/profile.simulation.cjs. `typeof window` guards the browser bootstrap so
//   `node --check profile.js` and `require()` never touch the DOM or network.
//
// XSS-safe: every dynamic string (profile values, error messages) reaches the DOM only via
//   `.value` / `.textContent` — never innerHTML. NO SECRETS live here: the backend base URL
//   comes from config.js and the Firebase ID token from auth.js; access is enforced by the
//   backend verifying the token on /api/v1/me, never by hiding this file.

// ===========================================================================
// FIELD SPEC — the single source of truth for the editable profile fields.
// Both the pure diff builder and the browser wiring iterate this list, so the set
// of fields (and their coercion types) is defined in exactly one place.
//
//   key  — matches BOTH the /api/v1/me JSON property AND the PATCH body property AND
//          the backend bean-validation FIELD name (the record component name), so a
//          "age: must be at least 13" error maps straight back to the `age` input.
//   type — how the raw form-string is compared/coerced when building the PATCH body:
//            "text" — string; sent as-is (trimmed). "" is a real value (sets empty).
//            "int"  — integer (age); parsed to a JSON number; cannot be cleared to null.
//            "enum" — a fixed string set (notificationPreference); sent as-is.
// ===========================================================================
var OSK_PROFILE_FIELDS = [
  { key: "firstName", type: "text" },
  { key: "lastName", type: "text" },
  { key: "displayName", type: "text" },
  { key: "city", type: "text" },
  { key: "age", type: "int" },
  { key: "phone", type: "text" },
  { key: "notificationPreference", type: "enum" },
  { key: "timezone", type: "text" },
  { key: "locale", type: "text" },
];

// The read-only identity fields shown for context (never editable via this form — they
// come from the verified token / are authorization-controlled on the backend).
var OSK_PROFILE_READONLY_FIELDS = ["uid", "email", "role"];

// ===========================================================================
// PURE, ENVIRONMENT-AGNOSTIC HELPERS
// These take plain data and return plain data. They touch no globals, no DOM and no
// network, so they run identically in the browser and under `node`/`require` — which is
// what makes the save/diff and error-parsing behaviour unit-testable without a browser.
// ===========================================================================

// Normalise a loaded profile value to the string an input would hold: null/undefined
// become "" (an empty input); everything else is String()-ified. Used both to populate
// inputs and to compare "what was loaded" against "what is in the input now".
function oskProfileValueToString(value) {
  return value === null || value === undefined ? "" : String(value);
}

// Build the SPARSE PATCH body: an object containing ONLY the fields whose current form
// value differs from the originally-loaded value. This is the "send only changed fields"
// requirement — a partial PATCH the backend applies without clobbering untouched fields.
//
//   original — the profile object as last loaded/saved from GET/PATCH /api/v1/me.
//   current  — { field: rawInputString } as read from the form right now.
//
// Coercion / edge rules (documented because they mirror the backend's sparse-PATCH
// contract in ProfileUpdate.java, where a null component means "leave unchanged"):
//   - text  — trimmed; a changed value (including "" ) is sent. Sending "" SETS an empty
//             string (the field cannot be reset to JSON null through this path). Note the
//             one asymmetry: clearing `phone` sends "" which the backend rejects as blank
//             (@Pattern) → a 400 we render inline on the phone field. That is honest: the
//             backend genuinely cannot null a phone via sparse PATCH.
//   - int   — `age`; compared as strings, then sent as a JSON NUMBER. An emptied age is
//             SKIPPED (an integer cannot be cleared to null via sparse PATCH), and a
//             non-numeric value is skipped rather than sent as garbage.
//   - enum  — sent as-is; an empty selection is skipped (there is always a real choice).
// Returns {} when nothing changed (the caller shows "No changes to save.").
function oskBuildPatch(original, current) {
  var orig = original || {};
  var cur = current || {};
  var patch = {};

  for (var i = 0; i < OSK_PROFILE_FIELDS.length; i++) {
    var field = OSK_PROFILE_FIELDS[i];
    var key = field.key;

    // A field the page did not render (undefined) is never part of the diff.
    if (cur[key] === undefined) { continue; }

    var origStr = oskProfileValueToString(orig[key]);

    if (field.type === "int") {
      var intStr = String(cur[key] === null || cur[key] === undefined ? "" : cur[key]).trim();
      if (intStr === origStr) { continue; }       // unchanged
      if (intStr === "") { continue; }            // cannot clear an integer to null (sparse PATCH)
      var n = Number(intStr);
      if (!isFinite(n)) { continue; }             // non-numeric — let the input constraint catch it
      patch[key] = Math.trunc(n);
    } else if (field.type === "enum") {
      var enumStr = String(cur[key] === null || cur[key] === undefined ? "" : cur[key]);
      if (enumStr === origStr) { continue; }       // unchanged
      if (enumStr === "") { continue; }            // no empty enum value
      patch[key] = enumStr;
    } else {
      // text
      var textStr = String(cur[key] === null || cur[key] === undefined ? "" : cur[key]).trim();
      if (textStr === origStr) { continue; }       // unchanged
      patch[key] = textStr;                        // may be "" — sets empty string (see note above)
    }
  }

  return patch;
}

// Parse a backend RFC-7807 ProblemDetail (already JSON-parsed) into a shape the UI can
// render: per-field messages keyed by our field name, plus any leftover general messages
// and the top-level `detail`. The 400 validation body looks like:
//   { title:"Bad Request", status:400, detail:"Request validation failed.",
//     errors:["age: must be at least 13", "timezone: must be a valid IANA time zone"] }
// (see GlobalExceptionHandler.handleValidation). Each `errors` entry is "field: message"
// where `field` is the record component name, matching our OSK_PROFILE_FIELDS keys — so a
// message maps straight back to its input. An `errors`-less 400 (e.g. a malformed body via
// HttpMessageNotReadableException) yields just the `detail` as a general message.
// Defensive against a null / non-object / array-less body.
function oskParseProblemErrors(body) {
  var result = { fieldErrors: {}, generalMessages: [], detail: "" };
  if (!body || typeof body !== "object") { return result; }

  if (typeof body.detail === "string") { result.detail = body.detail; }

  // The set of field names we can pin to an input; anything else is a general message.
  var known = {};
  for (var i = 0; i < OSK_PROFILE_FIELDS.length; i++) { known[OSK_PROFILE_FIELDS[i].key] = true; }

  var errors = body.errors;
  if (Array.isArray(errors)) {
    for (var j = 0; j < errors.length; j++) {
      var item = errors[j];
      if (typeof item !== "string") { result.generalMessages.push(String(item)); continue; }
      var idx = item.indexOf(":");
      if (idx === -1) { result.generalMessages.push(item); continue; }
      var fieldName = item.slice(0, idx).trim();
      var message = item.slice(idx + 1).trim();
      if (known[fieldName]) {
        // Keep the FIRST message per field (bean-validation may emit several).
        if (!result.fieldErrors[fieldName]) { result.fieldErrors[fieldName] = message; }
      } else {
        result.generalMessages.push(item);
      }
    }
  }

  return result;
}

// ===========================================================================
// PROFILE CONTROLLER — dependency-injected so it runs in the browser AND under a fake
// DOM + stubbed fetch in Node. It owns ONLY the profile form (load, diff, save, and the
// rendering of status / success / general + per-field errors). The auth GATE (show/hide
// the sign-in vs form regions) is handled separately by the browser bootstrap using the
// pure guard helpers auth.js already exports — this controller assumes it is only asked to
// load/save when the user is signed in.
//
// deps = {
//   doc         — a DOM-ish object exposing getElementById(id) -> element|null. Elements
//                 need only the properties this controller touches: inputs/select use
//                 `.value`; error/status/success use `.textContent` and `.hidden`; the
//                 save button uses `.disabled`; inputs optionally `.setAttribute`. The
//                 Node sim passes tiny stand-ins — no jsdom, no browser.
//   fetchFn     — fetch(url, opts) -> Promise<{ ok, status, json() }>. Real fetch in the
//                 browser; a canned stub in the sim.
//   getIdToken  — () -> Promise<string|null>. OSKAuth.getIdToken in the browser; a fake in
//                 the sim. null => no active session (handled gracefully).
//   apiBaseUrl  — backend base URL (config.js apiBaseUrl); trailing slashes trimmed.
// }
// ===========================================================================
function oskCreateProfileController(deps) {
  var d = deps || {};
  var doc = d.doc;
  var fetchFn = d.fetchFn;
  var getIdToken = d.getIdToken;
  var apiBase = String(d.apiBaseUrl || "").replace(/\/+$/, ""); // no trailing slash => no "//api"

  // The profile as last loaded/saved. Dirty-tracking diffs the form against THIS, so it is
  // updated on every successful load and every successful save (never on a failed save, so
  // the user's unsaved edits still count as changes to retry).
  var original = null;

  // --- element accessors (all guarded — a partial DOM never throws) ----------
  function el(id) { return doc && doc.getElementById ? doc.getElementById(id) : null; }
  function inputEl(key) { return el("pf-" + key); }
  function errEl(key) { return el("err-" + key); }

  // --- small DOM helpers (only .value / .textContent / .hidden / .disabled) --
  function setText(id, text) {
    var node = el(id);
    if (node) { node.textContent = text || ""; node.hidden = !text; }
  }
  function setHidden(id, hidden) {
    var node = el(id);
    if (node) { node.hidden = !!hidden; }
  }
  function setBusy(busy) {
    var btn = el("save-btn");
    if (btn) { btn.disabled = !!busy; }
  }
  function setInvalid(node, invalid) {
    if (node && typeof node.setAttribute === "function") {
      if (invalid) { node.setAttribute("aria-invalid", "true"); }
      else if (typeof node.removeAttribute === "function") { node.removeAttribute("aria-invalid"); }
    }
  }

  // Read the current raw string values of every editable input (undefined when the input
  // is absent, so oskBuildPatch skips it). This is exactly the `current` map buildPatch wants.
  function readCurrentValues() {
    var values = {};
    for (var i = 0; i < OSK_PROFILE_FIELDS.length; i++) {
      var key = OSK_PROFILE_FIELDS[i].key;
      var node = inputEl(key);
      values[key] = node ? node.value : undefined;
    }
    return values;
  }

  // Populate every input from a profile object (null/undefined -> empty input). Also fills
  // the read-only identity context lines. Never innerHTML — `.value` / `.textContent` only.
  function applyValues(profile) {
    var p = profile || {};
    for (var i = 0; i < OSK_PROFILE_FIELDS.length; i++) {
      var key = OSK_PROFILE_FIELDS[i].key;
      var node = inputEl(key);
      if (node) { node.value = oskProfileValueToString(p[key]); }
    }
    for (var j = 0; j < OSK_PROFILE_READONLY_FIELDS.length; j++) {
      var rkey = OSK_PROFILE_READONLY_FIELDS[j];
      var rnode = el("pf-" + rkey);
      if (rnode) {
        var v = oskProfileValueToString(p[rkey]);
        rnode.textContent = v === "" ? "—" : v;
      }
    }
  }

  // Clear every per-field inline error + the general error + the success line.
  function clearAllErrors() {
    for (var i = 0; i < OSK_PROFILE_FIELDS.length; i++) {
      var key = OSK_PROFILE_FIELDS[i].key;
      var e = errEl(key);
      if (e) { e.textContent = ""; e.hidden = true; }
      setInvalid(inputEl(key), false);
    }
    setHidden("profile-general-error", true);
  }
  function clearMessages() {
    clearAllErrors();
    setHidden("profile-success", true);
  }

  // Render per-field inline errors (and mark those inputs invalid). Any field without an
  // error is cleared. Returns the count of fields that got an error.
  function applyFieldErrors(fieldErrors) {
    var fe = fieldErrors || {};
    var count = 0;
    for (var i = 0; i < OSK_PROFILE_FIELDS.length; i++) {
      var key = OSK_PROFILE_FIELDS[i].key;
      var e = errEl(key);
      var msg = fe[key];
      if (msg) { count++; }
      if (e) { e.textContent = msg || ""; e.hidden = !msg; }
      setInvalid(inputEl(key), !!msg);
    }
    return count;
  }

  function showGeneralError(message) { setText("profile-general-error", message); }
  function showSuccess(message) { setText("profile-success", message); }
  function showStatus(message) { setText("profile-status", message); }

  // --- Load: GET /api/v1/me and populate the form -----------------------------
  // Resolves to a small outcome object ({ ok, status?, reason? }) so the caller (and the
  // sim) can assert what happened without scraping the DOM.
  function loadProfile() {
    clearMessages();
    setHidden("profile-form", true);
    showStatus("Loading your profile…");

    return Promise.resolve()
      .then(function () { return getIdToken(); })
      .then(function (token) {
        if (!token) {
          showStatus("");
          showGeneralError("You're not signed in. Sign in to edit your profile.");
          return { ok: false, reason: "no-token" };
        }
        return fetchFn(apiBase + "/api/v1/me", {
          headers: { Authorization: "Bearer " + token, Accept: "application/json" },
          cache: "no-store",
        }).then(function (res) {
          if (res.status === 401) {
            showStatus("");
            showGeneralError("Your session isn't authorized (401). Try signing out and back in.");
            return { ok: false, status: 401 };
          }
          if (res.status === 403) {
            showStatus("");
            showGeneralError("You don't have permission to view this profile (403).");
            return { ok: false, status: 403 };
          }
          if (!res.ok) {
            showStatus("");
            showGeneralError("Couldn't load your profile (HTTP " + res.status + ").");
            return { ok: false, status: res.status };
          }
          return res.json().then(function (profile) {
            original = profile && typeof profile === "object" ? profile : {};
            applyValues(original);
            clearAllErrors();
            showStatus("");
            setHidden("profile-form", false);
            return { ok: true, status: res.status };
          });
        });
      })
      .catch(function () {
        showStatus("");
        showGeneralError("Couldn't reach the backend to load your profile.");
        return { ok: false, reason: "network" };
      });
  }

  // --- Save: diff the form, PATCH only the changed fields ---------------------
  // Resolves to an outcome object so the sim can assert the path taken. On a 400 it renders
  // the ProblemDetail field errors inline; on 200 it updates `original` (so a second save
  // is correctly a no-op) and shows success.
  function save() {
    clearMessages();

    var patch = oskBuildPatch(original || {}, readCurrentValues());
    var changedKeys = Object.keys(patch);
    if (changedKeys.length === 0) {
      showSuccess("No changes to save.");
      return Promise.resolve({ ok: true, noChanges: true, patch: patch });
    }

    setBusy(true);
    return Promise.resolve()
      .then(function () { return getIdToken(); })
      .then(function (token) {
        if (!token) {
          showGeneralError("You're not signed in. Sign in to edit your profile.");
          return { ok: false, reason: "no-token", patch: patch };
        }
        return fetchFn(apiBase + "/api/v1/me", {
          method: "PATCH",
          headers: {
            Authorization: "Bearer " + token,
            "Content-Type": "application/json",
            Accept: "application/json",
          },
          cache: "no-store",
          body: JSON.stringify(patch),
        }).then(function (res) {
          if (res.status === 401) {
            showGeneralError("Your session isn't authorized (401). Try signing out and back in.");
            return { ok: false, status: 401, patch: patch };
          }
          if (res.status === 403) {
            showGeneralError("You don't have permission to update this profile (403).");
            return { ok: false, status: 403, patch: patch };
          }
          if (res.status === 400) {
            return res.json().then(
              function (problem) {
                var parsed = oskParseProblemErrors(problem);
                var count = applyFieldErrors(parsed.fieldErrors);
                var general = parsed.generalMessages.slice();
                if (count === 0 && parsed.detail) { general.unshift(parsed.detail); }
                if (general.length) { showGeneralError(general.join(" ")); }
                else if (count > 0) { showGeneralError("Please fix the highlighted fields."); }
                return { ok: false, status: 400, fieldErrorCount: count, patch: patch };
              },
              function () {
                // 400 with an unreadable body — still tell the user it failed.
                showGeneralError("Your changes were rejected (400).");
                return { ok: false, status: 400, fieldErrorCount: 0, patch: patch };
              }
            );
          }
          if (!res.ok) {
            showGeneralError("Couldn't save your changes (HTTP " + res.status + ").");
            return { ok: false, status: res.status, patch: patch };
          }
          // 2xx — adopt the returned (authoritative) profile so dirty-tracking resets.
          return res.json().then(
            function (updated) {
              if (updated && typeof updated === "object") {
                original = updated;
                applyValues(original);
              }
              clearAllErrors();
              showSuccess("Profile saved.");
              return { ok: true, status: res.status, patch: patch };
            },
            function () {
              // Saved but the response body was unreadable — still a success for the user.
              clearAllErrors();
              showSuccess("Profile saved.");
              return { ok: true, status: res.status, patch: patch };
            }
          );
        });
      })
      .catch(function () {
        showGeneralError("Couldn't reach the backend to save your changes.");
        return { ok: false, reason: "network", patch: patch };
      })
      .then(function (outcome) {
        setBusy(false);
        return outcome;
      });
  }

  // Expose the internals the sim (and the bootstrap) need. `getOriginal`/`setOriginal` let
  // the sim seed a loaded profile without a round-trip; the rest are the real API.
  return {
    loadProfile: loadProfile,
    save: save,
    readCurrentValues: readCurrentValues,
    applyValues: applyValues,
    clearMessages: clearMessages,
    getOriginal: function () { return original; },
    setOriginal: function (p) { original = p; },
  };
}

// ---------------------------------------------------------------------------
// Node export. When required (CommonJS), expose the pure helpers + the controller factory
// so the page's behaviour can be simulated headlessly. `typeof module` is "undefined" in a
// classic browser <script>, so this block is a no-op there.
// ---------------------------------------------------------------------------
if (typeof module !== "undefined" && module.exports) {
  module.exports = {
    OSK_PROFILE_FIELDS: OSK_PROFILE_FIELDS,
    OSK_PROFILE_READONLY_FIELDS: OSK_PROFILE_READONLY_FIELDS,
    profileValueToString: oskProfileValueToString,
    buildPatch: oskBuildPatch,
    parseProblemErrors: oskParseProblemErrors,
    createProfileController: oskCreateProfileController,
  };
}

// ===========================================================================
// BROWSER BOOTSTRAP
// Runs ONLY in a real browser (guarded by `typeof window`). It wires the auth GATE (reusing
// the pure guard helpers from window.OSKAuth), the sign-in affordance shown when signed
// out, and the profile controller shown when signed in. Mirrors app.html's inline guard
// script but drives the profile FORM instead of a read-only profile dump.
// ===========================================================================
if (typeof window !== "undefined") {
  (function () {
    "use strict";

    document.addEventListener("DOMContentLoaded", function () {
      var cfg = window.__APP_CONFIG__ || {};
      var A = window.OSKAuth;

      // The guard regions (same contract as app.html). applyGuardView toggles their `.hidden`.
      var els = {
        signIn: document.getElementById("auth-signin"),
        app: document.getElementById("profile-app"),
        notice: document.getElementById("auth-notice"),
        noticeText: document.getElementById("auth-notice-text"),
      };

      // Hard fallback: if auth.js failed to load, OSKAuth is absent. Show a plain notice
      // rather than a silently broken page.
      if (!A) {
        if (els.notice) { els.notice.hidden = false; }
        if (els.noticeText) {
          els.noticeText.textContent = "The sign-in module failed to load. Please refresh the page.";
        }
        return;
      }

      // The profile controller, wired to the REAL DOM, fetch, token source and API base.
      var controller = oskCreateProfileController({
        doc: document,
        fetchFn: window.fetch.bind(window),
        getIdToken: function () { return A.getIdToken(); },
        apiBaseUrl: cfg.apiBaseUrl || "",
      });

      // --- Sign-in affordance (shown when signed out) --------------------------
      // Reuses the exported OSKAuth sign-in API (email/password + Google) so a user can
      // sign in and edit in one place. auth.js is untouched; we only call its public API.
      var signinForm = document.getElementById("signin-form");
      var emailEl = document.getElementById("signin-email");
      var passwordEl = document.getElementById("signin-password");
      var submitBtn = document.getElementById("signin-submit");
      var googleBtn = document.getElementById("google-btn");
      var signinError = document.getElementById("signin-error");
      var signoutBtn = document.getElementById("signout-btn");

      function showSigninError(message) {
        if (!signinError) { return; }
        signinError.textContent = message || "";
        signinError.hidden = !message;
      }
      function setSigninBusy(busy) {
        if (submitBtn) { submitBtn.disabled = busy; }
        if (googleBtn) { googleBtn.disabled = busy; }
      }

      if (signinForm) {
        signinForm.addEventListener("submit", function (ev) {
          ev.preventDefault();
          showSigninError("");
          var email = emailEl ? emailEl.value.trim() : "";
          var password = passwordEl ? passwordEl.value : "";
          if (!email || !password) { showSigninError("Enter your email and password."); return; }
          setSigninBusy(true);
          A.signInWithEmailPassword(email, password)
            .then(function () { showSigninError(""); /* render() runs via auth-state change */ })
            .catch(function (err) { showSigninError((err && err.message) ? err.message : "Sign-in failed."); })
            .then(function () { setSigninBusy(false); });
        });
      }
      if (googleBtn) {
        googleBtn.addEventListener("click", function () {
          showSigninError("");
          setSigninBusy(true);
          A.signInWithGoogle()
            .then(function () { showSigninError(""); })
            .catch(function (err) { showSigninError((err && err.message) ? err.message : "Google sign-in failed."); })
            .then(function () { setSigninBusy(false); });
        });
      }
      if (signoutBtn) {
        signoutBtn.addEventListener("click", function () {
          A.signOut().catch(function () { /* nothing useful to show on sign-out error */ });
        });
      }

      // --- Save wiring ---------------------------------------------------------
      var profileForm = document.getElementById("profile-form");
      if (profileForm) {
        profileForm.addEventListener("submit", function (ev) {
          ev.preventDefault();
          controller.save();
        });
      }

      // --- Guard render loop (identical decision logic to app.html) -----------
      // Recompute the view from the current auth state and apply it; on the transition INTO
      // signed-in, load the profile once (not on every repaint).
      var wasSignedIn = false;
      function render() {
        var view = A.computeGuardView(A.getState());
        A.applyGuardView(view, els);
        if (view.mode === "signed-in") {
          if (!wasSignedIn) { controller.loadProfile(); }
          wasSignedIn = true;
        } else {
          wasSignedIn = false;
        }
      }

      render();
      A.onAuthStateChanged(render);
      A.ready.then(render).catch(render);
    });
  })();
}
