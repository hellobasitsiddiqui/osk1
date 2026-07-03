// completion-gate.js — OpenSkeleton FIRST-LOGIN PROFILE-COMPLETION GATE (OSK-136).
//
// WHAT: a self-contained, dependency-free BLOCKING gate for the protected /app page.
// When a signed-in user enters the app, this module reads their profile from
// `GET /api/v1/me` (OSK-67 fields) and checks the REQUIRED first-login fields:
//   - a name      (firstName),
//   - a location  (city), and
//   - an age      (age, a NUMBER 13–120).
// If any required field is missing/empty, it overlays a minimal, focus-trapped form
// asking for JUST those fields and BLOCKS entry to the app until they are supplied.
// On submit it PATCHes only the entered fields to `PATCH /api/v1/me` with the Firebase
// Bearer token, renders inline 400 field errors from the RFC-7807 ProblemDetail
// `errors[]` (`"field: message"` shape), and — on success — dismisses the overlay and
// reveals the app. Once the required fields are present the gate never shows again
// (the backend is the source of truth, so this is naturally idempotent — no flag).
//
// WHY firstName (not firstName+lastName) is the required "name": the full self-service
// editor (OSK-86) treats lastName as OPTIONAL, and the goal of a FIRST-LOGIN gate is
// the *minimum* friction that still yields a usable profile. Requiring only firstName
// captures a usable name while leaving lastName (and everything else) to the full
// editor later. Widening the required set is a one-line config change (see below).
//
// HOW IT FITS THE HOUSE STYLE (mirrors auth.js / history.js / tour.js):
//   - Vanilla, no bundler, no framework, NO external/CDN request. A page opts in with
//     exactly ONE line — `<script src="/completion-gate.js"></script>`. This module
//     injects its OWN <style> (built from the palette tokens, so it re-skins with the
//     theme incl. sketch) and its OWN overlay DOM, so no page HTML has to change.
//   - CONFIG-DRIVEN + graceful: reads the optional `window.__APP_CONFIG__.completionGate`
//     block but needs nothing from it; a missing block, storage-blocked, or backend
//     error all degrade quietly (FAIL-OPEN — see below) instead of throwing.
//   - DELEGATES AUTH to window.OSKAuth (web/auth.js): it never touches Firebase itself,
//     it only asks OSKAuth for the signed-in state and a fresh ID token. It does NOT
//     edit auth.js.
//   - DUAL-USE like history.js: the PURE logic (missing-field detection, patch building,
//     ProblemDetail parsing, the gate view decision) plus the fetch/PATCH (against an
//     injectable `fetchImpl`/`getToken`) and the DOM builders (against an injectable
//     `document`) are all exported for a headless Node simulation
//     (web/e2e/completion-gate.simulation.cjs). The browser bootstrap only runs when
//     `window`/`document` exist, so `require()`ing this file in Node runs NO DOM code.
//   - XSS-safe: every dynamic string (labels, error messages) is written with
//     textContent only — never innerHTML.
//   - Accessible: the overlay is a role=dialog / aria-modal with a labelled heading;
//     focus is moved into it and trapped while it blocks; the first field is focused.
//
// FAIL-OPEN on uncertainty (a deliberate choice): the gate blocks ONLY when it has
// positively confirmed the profile is incomplete (a 200 `/me` with a missing required
// field). On 401 / HTTP-error / offline / no-session it FAILS OPEN (reveals the app and
// lets the app's own auth handling take over), so a transient backend hiccup can never
// lock a user out of an app they're entitled to. The one thing it must never do is let
// an *incomplete* profile slip through — that path is fail-closed.
//
// COMPOSITION with the sibling first-run gates (OSK-78 terms, OSK-82 onboarding):
// intended order is terms → profile-completion → onboarding. Each gate is an INDEPENDENT
// overlay that only shows while its OWN precondition is unmet and is IDEMPOTENT (this one
// is backed by the server, the others by their own state), so they compose safely in any
// interleaving — the user must clear all of them before using the app, and clearing one
// never re-triggers another. This module keeps a MODERATE z-index and exposes a
// programmatic API (window.OSKCompletionGate) so an orchestrator can also drive the
// ordering explicitly (set `completionGate.autoStart: false` to opt out of auto-mount).
// See the PR body for the full sequence rationale.

(function () {
  "use strict";

  // ===========================================================================
  // SECTION 1 — Pure constants: the required-field registry (the "data structure")
  // ===========================================================================

  // The known first-login fields, keyed by the OSK-67 profile property name. Each entry
  // carries everything the form + the patch builder need:
  //   label       — visible field label (textContent only, XSS-safe).
  //   type        — <input type>. "number" for age so mobile shows a numeric keypad.
  //   inputmode   — mobile keyboard hint.
  //   autocomplete— browser autofill hint.
  //   kind        — "string" | "number"; drives missing-detection + patch coercion.
  //   help        — short hint shown under the field (e.g. the 13–120 age rule).
  var FIELD_DEFS = {
    firstName: {
      label: "First name",
      type: "text",
      inputmode: "text",
      autocomplete: "given-name",
      kind: "string",
      help: "",
    },
    city: {
      label: "Location (city)",
      type: "text",
      inputmode: "text",
      autocomplete: "address-level2",
      kind: "string",
      help: "",
    },
    age: {
      label: "Age",
      type: "number",
      inputmode: "numeric",
      autocomplete: "off",
      kind: "number",
      help: "Must be a number between 13 and 120.",
    },
  };

  // The default required set for the first-login gate. Config may override this (see
  // oskResolveRequiredFields) but any name not in FIELD_DEFS is ignored, so a typo in
  // config can never crash the gate — it just falls back to the known fields.
  var DEFAULT_REQUIRED_FIELDS = ["firstName", "city", "age"];

  // ===========================================================================
  // SECTION 2 — PURE helpers (no DOM, no network). Identical in browser and Node.
  // ===========================================================================

  // Resolve the required-field list from config, defensively. Only names that exist in
  // FIELD_DEFS survive, order/duplicates are normalised, and an empty/garbage result
  // falls back to the default set. Never throws.
  function oskResolveRequiredFields(config) {
    var raw =
      config && Array.isArray(config.requiredFields) && config.requiredFields.length
        ? config.requiredFields
        : DEFAULT_REQUIRED_FIELDS;
    var out = [];
    for (var i = 0; i < raw.length; i++) {
      var name = raw[i];
      if (FIELD_DEFS[name] && out.indexOf(name) === -1) {
        out.push(name);
      }
    }
    return out.length ? out : DEFAULT_REQUIRED_FIELDS.slice();
  }

  // Is a single profile field MISSING (i.e. must be collected by the gate)? A value is
  // missing when it is null/undefined, an empty/whitespace-only string, or — for a
  // numeric field — not a finite number (covers null, "", and NaN alike). This is the
  // single source of truth for "the user still needs to provide this".
  function oskIsFieldMissing(profile, name) {
    var def = FIELD_DEFS[name];
    if (!def) {
      return false; // unknown field — never block on something we don't understand.
    }
    var value = profile ? profile[name] : undefined;
    if (value === null || value === undefined) {
      return true;
    }
    if (def.kind === "number") {
      // Accept a number or a numeric string; anything non-finite is "missing".
      var n = typeof value === "number" ? value : Number(String(value).trim());
      return !isFinite(n);
    }
    // string kind: trim and treat whitespace-only as empty.
    return String(value).trim() === "";
  }

  // The list of required fields still missing from a profile (empty => complete). If the
  // profile is null/undefined, ALL required fields are considered missing.
  function oskGetMissingRequiredFields(profile, required) {
    var req = Array.isArray(required) ? required : DEFAULT_REQUIRED_FIELDS;
    var missing = [];
    for (var i = 0; i < req.length; i++) {
      if (oskIsFieldMissing(profile, req[i])) {
        missing.push(req[i]);
      }
    }
    return missing;
  }

  // Convenience predicate: are ALL required fields present?
  function oskIsProfileComplete(profile, required) {
    return oskGetMissingRequiredFields(profile, required).length === 0;
  }

  // Build the sparse PATCH body from the form's raw string values. Mirrors the OSK-86
  // editor's buildPatch style:
  //   - only NON-EMPTY entered fields are included (never send blanks),
  //   - strings are trimmed,
  //   - a numeric field (age) is coerced to a NUMBER when it parses to a finite value;
  //     if the user typed something non-numeric it is sent AS THE RAW STRING so the
  //     backend returns a 400 field error we can render inline (rather than silently
  //     dropping it or sending null).
  // `values` is a { name: rawString } map (typically read off the form inputs).
  function oskBuildPatch(values, required) {
    var v = values || {};
    var req = Array.isArray(required) ? required : DEFAULT_REQUIRED_FIELDS;
    var patch = {};
    for (var i = 0; i < req.length; i++) {
      var name = req[i];
      var def = FIELD_DEFS[name];
      if (!def) {
        continue;
      }
      var raw = v[name];
      if (raw === null || raw === undefined) {
        continue;
      }
      var trimmed = String(raw).trim();
      if (trimmed === "") {
        continue; // never submit an empty required field — the form blocks that anyway.
      }
      if (def.kind === "number") {
        var n = Number(trimmed);
        patch[name] = isFinite(n) ? n : trimmed; // finite -> number; else raw for a 400.
      } else {
        patch[name] = trimmed;
      }
    }
    return patch;
  }

  // Parse an RFC-7807 ProblemDetail into inline-renderable errors. The backend's 400
  // shape is `{ ..., errors: ["field: message", ...] }`. We split each entry on the FIRST
  // ": " into a field name + message; entries with a known field name become per-field
  // errors, everything else becomes a general (form-level) error. Defensive against the
  // errors array being absent, a string, or an array of `{field,message}` objects.
  // Returns { fields: { name: message }, general: [message, ...] }.
  function oskParseProblemErrors(problem) {
    var result = { fields: {}, general: [] };
    if (!problem || typeof problem !== "object") {
      return result;
    }
    var errors = problem.errors;
    // Normalise to an array we can iterate.
    if (typeof errors === "string") {
      errors = [errors];
    }
    if (!Array.isArray(errors)) {
      // No structured errors — fall back to the human-readable detail/title if present.
      var fallback = problem.detail || problem.title;
      if (fallback) {
        result.general.push(String(fallback));
      }
      return result;
    }
    for (var i = 0; i < errors.length; i++) {
      var entry = errors[i];
      var field = null;
      var message = null;
      if (entry && typeof entry === "object") {
        // Defensive: some backends emit { field, message } objects instead of strings.
        field = entry.field || entry.pointer || null;
        message = entry.message || entry.detail || String(entry);
      } else {
        var str = String(entry);
        var idx = str.indexOf(":");
        if (idx > -1) {
          field = str.slice(0, idx).trim();
          message = str.slice(idx + 1).trim();
        } else {
          message = str.trim();
        }
      }
      if (field && FIELD_DEFS[field]) {
        // Keep the first error per field (usually the most relevant) but don't clobber.
        if (!result.fields[field]) {
          result.fields[field] = message || "Invalid value.";
        }
      } else if (message) {
        result.general.push(message);
      }
    }
    return result;
  }

  // Pure gate-view DECISION: given the current state, what should the gate show? Mirrors
  // auth.js's computeGuardView. Never touches DOM/network.
  //   state = {
  //     signedIn:      boolean,   // is a user signed in? (from OSKAuth)
  //     profileStatus: string,    // "loading" | "ok" | "no-session" | "unauthorized"
  //                               //           | "http-error" | "network-error"
  //     profile:       object|null,
  //     required:      string[],
  //   }
  //   returns {
  //     mode:        "inactive" | "loading" | "gate" | "complete" | "error",
  //     showLoading: boolean,     // show the brief blocking "checking…" overlay
  //     showGate:    boolean,     // show the blocking completion FORM
  //     missing:     string[],    // required fields still missing (for the form)
  //   }
  function oskComputeGateView(state) {
    var s = state || {};
    var required = Array.isArray(s.required) ? s.required : DEFAULT_REQUIRED_FIELDS;

    // Not signed in -> the gate is dormant; the OSK-74 guard shows the sign-in affordance.
    if (!s.signedIn) {
      return { mode: "inactive", showLoading: false, showGate: false, missing: [] };
    }

    // Signed in, still fetching /me -> briefly block with a neutral loader so the app is
    // never revealed before we know whether it must be gated (no flash-of-app).
    if (s.profileStatus === "loading") {
      return { mode: "loading", showLoading: true, showGate: false, missing: [] };
    }

    // Signed in with a confirmed profile -> the ONLY state that can fail closed.
    if (s.profileStatus === "ok") {
      var missing = oskGetMissingRequiredFields(s.profile, required);
      if (missing.length) {
        return { mode: "gate", showLoading: false, showGate: true, missing: missing };
      }
      return { mode: "complete", showLoading: false, showGate: false, missing: [] };
    }

    // Any error/uncertain status (401, HTTP error, offline, no session) -> FAIL OPEN.
    return { mode: "error", showLoading: false, showGate: false, missing: [] };
  }

  // ===========================================================================
  // SECTION 3 — Network (injectable deps). Browser-free + testable, like history.js.
  // ===========================================================================

  // GET /api/v1/me and classify the outcome. Injectable deps keep it browser-free:
  //   opts.getToken   — () => Promise<string|null>  (browser: OSKAuth.getIdToken)
  //   opts.apiBaseUrl — backend base URL            (browser: config.js apiBaseUrl)
  //   opts.fetchImpl  — fetch-compatible fn          (browser: window.fetch; test: stub)
  // Never throws: any rejection resolves to { status: "network-error" }.
  function oskFetchProfile(opts) {
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
        return fetchImpl(base + "/api/v1/me", {
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
            return { status: "ok", profile: body && typeof body === "object" ? body : {} };
          });
        });
      })
      .catch(function () {
        return { status: "network-error" };
      });
  }

  // PATCH /api/v1/me with a sparse `patch` body and classify the outcome. Same injectable
  // deps as oskFetchProfile plus `opts.patch` (the body). Outcomes:
  //   { status: "ok", profile }                       — 2xx, updated profile echoed back
  //   { status: "validation-error", errors, problem } — 400 RFC-7807 (parsed errors[])
  //   { status: "no-session" }                        — no token
  //   { status: "unauthorized" }                      — 401
  //   { status: "http-error", httpStatus }            — other non-2xx
  //   { status: "network-error" }                     — thrown/offline
  // Never throws.
  function oskSubmitProfile(opts) {
    var o = opts || {};
    var getToken = o.getToken;
    var fetchImpl = o.fetchImpl;
    var patch = o.patch || {};
    var base = (o.apiBaseUrl || "").replace(/\/+$/, "");
    return Promise.resolve()
      .then(function () {
        return typeof getToken === "function" ? getToken() : null;
      })
      .then(function (token) {
        if (!token) {
          return { status: "no-session" };
        }
        return fetchImpl(base + "/api/v1/me", {
          method: "PATCH",
          headers: {
            Authorization: "Bearer " + token,
            "Content-Type": "application/json",
            Accept: "application/json",
          },
          cache: "no-store",
          body: JSON.stringify(patch),
        }).then(function (r) {
          if (r.status === 401) {
            return { status: "unauthorized" };
          }
          if (r.status === 400) {
            // Parse the ProblemDetail so the caller can render inline field errors.
            return r
              .json()
              .then(
                function (body) {
                  return {
                    status: "validation-error",
                    errors: oskParseProblemErrors(body),
                    problem: body,
                  };
                },
                function () {
                  // 400 with an unparseable body — still a validation failure.
                  return { status: "validation-error", errors: { fields: {}, general: ["Please check your entries."] } };
                },
              );
          }
          if (!r.ok) {
            return { status: "http-error", httpStatus: r.status };
          }
          // 2xx — the backend echoes the updated profile; tolerate an empty/no body.
          return r.json().then(
            function (body) {
              return { status: "ok", profile: body && typeof body === "object" ? body : {} };
            },
            function () {
              return { status: "ok", profile: {} };
            },
          );
        });
      })
      .catch(function () {
        return { status: "network-error" };
      });
  }

  // ===========================================================================
  // SECTION 4 — DOM builders (injectable `doc`). XSS-safe, jsdom-free, testable.
  // ===========================================================================

  // Build the blocking completion FORM overlay for the given missing fields against an
  // injected `document`. Uses only createElement / textContent / setAttribute /
  // appendChild, so it is strictly XSS-safe AND the Node simulation can pass a tiny fake
  // `document` and assert the produced structure without jsdom.
  //
  // Returns a REFS object the controller (and the tests) drive:
  //   { root, dialog, form, submitBtn, signOutBtn, generalError,
  //     inputs: { name: <input> }, errorEls: { name: <errorNode> } }
  function oskBuildGateForm(doc, spec) {
    var s = spec || {};
    var missing = Array.isArray(s.missing) ? s.missing : [];

    // Full-viewport blocking backdrop.
    var root = doc.createElement("div");
    root.className = "osk-cg-overlay";
    root.setAttribute("data-osk-completion-gate", "1");

    // The dialog surface.
    var dialog = doc.createElement("div");
    dialog.className = "osk-cg-dialog";
    dialog.setAttribute("role", "dialog");
    dialog.setAttribute("aria-modal", "true");
    dialog.setAttribute("aria-labelledby", "osk-cg-title");

    var title = doc.createElement("h2");
    title.className = "osk-cg-title";
    title.setAttribute("id", "osk-cg-title");
    title.textContent = "Complete your profile";
    dialog.appendChild(title);

    var intro = doc.createElement("p");
    intro.className = "osk-cg-intro";
    intro.textContent =
      "Before you continue, please tell us a little about yourself. " +
      "You can change these later in your profile.";
    dialog.appendChild(intro);

    var form = doc.createElement("form");
    form.className = "osk-cg-form";
    form.setAttribute("novalidate", "novalidate");

    var inputs = {};
    var errorEls = {};

    for (var i = 0; i < missing.length; i++) {
      var name = missing[i];
      var def = FIELD_DEFS[name];
      if (!def) {
        continue;
      }
      var fieldId = "osk-cg-field-" + name;
      var errId = "osk-cg-error-" + name;

      var wrap = doc.createElement("label");
      wrap.className = "osk-cg-field";
      wrap.setAttribute("for", fieldId);

      var labelText = doc.createElement("span");
      labelText.className = "osk-cg-label";
      labelText.textContent = def.label;
      wrap.appendChild(labelText);

      var input = doc.createElement("input");
      input.className = "osk-cg-input";
      input.setAttribute("id", fieldId);
      input.setAttribute("name", name);
      input.setAttribute("type", def.type);
      input.setAttribute("inputmode", def.inputmode);
      input.setAttribute("autocomplete", def.autocomplete);
      input.setAttribute("required", "required");
      input.setAttribute("aria-describedby", errId);
      // `type`/`value` as direct props too, so a fake-DOM input (which reads `.value`)
      // and the browser both behave.
      input.type = def.type;
      input.value = "";
      wrap.appendChild(input);

      if (def.help) {
        var help = doc.createElement("span");
        help.className = "osk-cg-help";
        help.textContent = def.help;
        wrap.appendChild(help);
      }

      // Inline error slot for this field (populated from the 400 ProblemDetail).
      var err = doc.createElement("span");
      err.className = "osk-cg-field-error";
      err.setAttribute("id", errId);
      err.setAttribute("role", "alert");
      err.hidden = true;
      wrap.appendChild(err);

      form.appendChild(wrap);
      inputs[name] = input;
      errorEls[name] = err;
    }

    // Form-level (general) error line for non-field 400 messages / 401 / network errors.
    var generalError = doc.createElement("p");
    generalError.className = "osk-cg-general-error";
    generalError.setAttribute("role", "alert");
    generalError.hidden = true;
    form.appendChild(generalError);

    // Primary submit.
    var submitBtn = doc.createElement("button");
    submitBtn.className = "osk-cg-submit";
    submitBtn.setAttribute("type", "submit");
    submitBtn.textContent = "Save and continue";
    form.appendChild(submitBtn);

    dialog.appendChild(form);

    // A quiet "Sign out" escape hatch so the gate is never an inescapable trap — a user
    // who can't/won't complete can leave (the OSK-74 guard then shows sign-in again).
    var footer = doc.createElement("div");
    footer.className = "osk-cg-footer";
    var signOutBtn = doc.createElement("button");
    signOutBtn.className = "osk-cg-signout";
    signOutBtn.setAttribute("type", "button");
    signOutBtn.textContent = "Sign out";
    footer.appendChild(signOutBtn);
    dialog.appendChild(footer);

    root.appendChild(dialog);

    return {
      root: root,
      dialog: dialog,
      form: form,
      submitBtn: submitBtn,
      signOutBtn: signOutBtn,
      generalError: generalError,
      inputs: inputs,
      errorEls: errorEls,
    };
  }

  // Build the brief blocking "checking your profile…" loader overlay (shown between
  // "signed-in" and the /me result, so the app never flashes before we've verified it).
  function oskBuildLoadingOverlay(doc) {
    var root = doc.createElement("div");
    root.className = "osk-cg-overlay osk-cg-overlay--loading";
    root.setAttribute("data-osk-completion-gate", "loading");
    var msg = doc.createElement("p");
    msg.className = "osk-cg-loading-text";
    msg.setAttribute("role", "status");
    msg.textContent = "Checking your profile…";
    root.appendChild(msg);
    return { root: root };
  }

  // Apply parsed ProblemDetail errors to a REFS object's inline slots. Clears any prior
  // errors first, sets per-field messages, and collects general messages into the
  // form-level line. Returns the number of field errors shown (handy for tests).
  function oskRenderFormErrors(refs, parsed, options) {
    var r = refs || {};
    var p = parsed || { fields: {}, general: [] };
    var opts = options || {};

    // Clear existing field errors.
    var errorEls = r.errorEls || {};
    var name;
    for (name in errorEls) {
      if (Object.prototype.hasOwnProperty.call(errorEls, name)) {
        var el = errorEls[name];
        el.textContent = "";
        el.hidden = true;
        var input = r.inputs && r.inputs[name];
        if (input && typeof input.setAttribute === "function") {
          input.setAttribute("aria-invalid", "false");
        }
      }
    }

    var shown = 0;
    var fields = p.fields || {};
    for (name in fields) {
      if (Object.prototype.hasOwnProperty.call(fields, name) && errorEls[name]) {
        errorEls[name].textContent = fields[name];
        errorEls[name].hidden = false;
        var inEl = r.inputs && r.inputs[name];
        if (inEl && typeof inEl.setAttribute === "function") {
          inEl.setAttribute("aria-invalid", "true");
        }
        shown++;
      }
    }

    // General line: joined field-less messages, or an explicit override (401/network).
    var general = opts.generalOverride || (p.general || []).join(" ");
    if (r.generalError) {
      if (general) {
        r.generalError.textContent = general;
        r.generalError.hidden = false;
      } else {
        r.generalError.textContent = "";
        r.generalError.hidden = true;
      }
    }

    return shown;
  }

  // Read the current { name: value } map off a REFS object's inputs (for buildPatch).
  function oskReadValues(refs) {
    var values = {};
    var inputs = (refs && refs.inputs) || {};
    for (var name in inputs) {
      if (Object.prototype.hasOwnProperty.call(inputs, name)) {
        var el = inputs[name];
        values[name] = el && el.value !== undefined && el.value !== null ? el.value : "";
      }
    }
    return values;
  }

  // ===========================================================================
  // SECTION 5 — The GATE CONTROLLER (browser + sim). Wires the pure/net/DOM pieces
  // together against injected dependencies so the whole flow — check -> block ->
  // submit -> dismiss — can be driven headlessly in the simulation.
  // ===========================================================================

  // deps = {
  //   document,               // the (real or fake) document
  //   mountNode,              // where to append the overlay (default document.body)
  //   getToken,               // () => Promise<string|null>
  //   apiBaseUrl,             // backend base URL
  //   fetchImpl,              // fetch-compatible fn
  //   config,                 // window.__APP_CONFIG__.completionGate (optional)
  //   onSignOut,              // called when the user clicks "Sign out" (optional)
  //   lockScroll,             // fn(bool) to lock/unlock page scroll (optional)
  // }
  function oskCreateGate(deps) {
    var d = deps || {};
    var doc = d.document;
    var required = oskResolveRequiredFields(d.config);

    // Mutable controller state.
    var mounted = null; // current REFS (form) or loading refs, or null when nothing shown
    var mountedKind = null; // "loading" | "gate" | null
    var busy = false; // a PATCH is in flight
    var lastProfile = null; // the profile last fetched by check() — the merge base on submit

    function mountNode() {
      return d.mountNode || (doc && doc.body) || null;
    }

    // Remove whatever overlay is currently mounted (idempotent).
    function unmount() {
      if (mounted && mounted.root && mounted.root.parentNode) {
        mounted.root.parentNode.removeChild(mounted.root);
      } else if (mounted && mounted.root) {
        var parent = mountNode();
        if (parent && typeof parent.removeChild === "function") {
          try {
            parent.removeChild(mounted.root);
          } catch (e) {
            /* already detached — fine */
          }
        }
      }
      if (mounted && (mountedKind === "gate" || mountedKind === "loading")) {
        if (typeof d.lockScroll === "function") {
          d.lockScroll(false);
        }
      }
      mounted = null;
      mountedKind = null;
    }

    function attach(refs) {
      var parent = mountNode();
      if (parent && typeof parent.appendChild === "function") {
        parent.appendChild(refs.root);
      }
      if (typeof d.lockScroll === "function") {
        d.lockScroll(true);
      }
    }

    // Show the brief loading overlay (only if nothing already shown).
    function showLoading() {
      if (mountedKind === "loading" || mountedKind === "gate") {
        return;
      }
      unmount();
      mounted = oskBuildLoadingOverlay(doc);
      mountedKind = "loading";
      attach(mounted);
    }

    // Show the blocking completion FORM for the given missing fields.
    function showGate(missing) {
      unmount();
      var refs = oskBuildGateForm(doc, { missing: missing });
      mounted = refs;
      mountedKind = "gate";
      attach(refs);

      // Wire submit (browser dispatches a submit event; the sim calls controller.submit).
      if (refs.form && typeof refs.form.addEventListener === "function") {
        refs.form.addEventListener("submit", function (ev) {
          if (ev && typeof ev.preventDefault === "function") {
            ev.preventDefault();
          }
          submit();
        });
      }
      if (refs.signOutBtn && typeof refs.signOutBtn.addEventListener === "function") {
        refs.signOutBtn.addEventListener("click", function () {
          if (typeof d.onSignOut === "function") {
            d.onSignOut();
          }
        });
      }
      // Move focus to the first field (guarded — fake inputs have no focus()).
      var first = missing.length ? refs.inputs[missing[0]] : null;
      if (first && typeof first.focus === "function") {
        try {
          first.focus();
        } catch (e) {
          /* focus is best-effort */
        }
      }
      return refs;
    }

    // Fetch the profile, decide, and reflect the decision onto the overlay. Returns a
    // promise resolving to the computed view (handy for tests). Idempotent + safe to
    // call repeatedly.
    function check() {
      // Immediately block with the loader so the app can't flash before we know.
      showLoading();
      return oskFetchProfile({
        getToken: d.getToken,
        apiBaseUrl: d.apiBaseUrl,
        fetchImpl: d.fetchImpl,
      }).then(function (result) {
        // Remember the fetched profile so a submit can merge its edits ONTO it (the PATCH
        // response may echo only the changed fields — or nothing — so we must not lose the
        // fields that were already present when deciding "now complete?").
        if (result.status === "ok") {
          lastProfile = result.profile || {};
        }
        var view = oskComputeGateView({
          signedIn: true,
          profileStatus: result.status,
          profile: result.profile,
          required: required,
        });
        if (view.showGate) {
          showGate(view.missing);
        } else {
          // complete / error / no-session -> FAIL OPEN: remove any overlay, reveal app.
          unmount();
        }
        return view;
      });
    }

    // Submit the current form values. Reads the inputs, builds the sparse patch, PATCHes,
    // then: 2xx -> dismiss + reveal; 400 -> inline field errors; 401/other -> general
    // error (does NOT dismiss — the requirements stay unmet). Returns the PATCH outcome.
    function submit() {
      if (mountedKind !== "gate" || !mounted || busy) {
        return Promise.resolve({ status: "not-open" });
      }
      var refs = mounted;
      busy = true;
      if (refs.submitBtn) {
        refs.submitBtn.disabled = true;
      }
      // Clear any prior errors before re-validating.
      oskRenderFormErrors(refs, { fields: {}, general: [] });

      var values = oskReadValues(refs);
      var patch = oskBuildPatch(values, required);

      return oskSubmitProfile({
        getToken: d.getToken,
        apiBaseUrl: d.apiBaseUrl,
        fetchImpl: d.fetchImpl,
        patch: patch,
      })
        .then(function (outcome) {
          if (outcome.status === "ok") {
            // Re-verify before we dismiss — belt and braces so we never reveal on a partial
            // success. Merge base = the profile we fetched (retains fields that were already
            // present), overlaid by anything the PATCH echoed, overlaid by what we just sent
            // (our accepted edits win — a 2xx means the server took them).
            var merged = {};
            function overlay(src) {
              if (!src || typeof src !== "object") {
                return;
              }
              for (var kk in src) {
                if (Object.prototype.hasOwnProperty.call(src, kk)) {
                  merged[kk] = src[kk];
                }
              }
            }
            overlay(lastProfile);
            overlay(outcome.profile);
            overlay(patch);
            if (oskIsProfileComplete(merged, required)) {
              unmount(); // dismiss the gate and reveal the app.
            } else {
              // Still incomplete (shouldn't happen) — re-render the remaining fields.
              showGate(oskGetMissingRequiredFields(merged, required));
            }
          } else if (outcome.status === "validation-error") {
            oskRenderFormErrors(refs, outcome.errors);
          } else if (outcome.status === "unauthorized") {
            oskRenderFormErrors(refs, { fields: {}, general: [] }, {
              generalOverride:
                "Your session has expired. Please sign out and sign in again.",
            });
          } else if (outcome.status === "no-session") {
            oskRenderFormErrors(refs, { fields: {}, general: [] }, {
              generalOverride: "You're not signed in. Please sign in again.",
            });
          } else {
            oskRenderFormErrors(refs, { fields: {}, general: [] }, {
              generalOverride: "Couldn't save your profile. Please try again.",
            });
          }
          return outcome;
        })
        .then(function (outcome) {
          busy = false;
          if (refs.submitBtn) {
            refs.submitBtn.disabled = false;
          }
          return outcome;
        });
    }

    // Called when the user signs out / becomes signed-out — tear the gate down.
    function reset() {
      unmount();
    }

    return {
      required: required,
      check: check,
      submit: submit,
      reset: reset,
      unmount: unmount,
      isMounted: function () {
        return mountedKind === "gate";
      },
      isLoading: function () {
        return mountedKind === "loading";
      },
      // Exposed for tests / an orchestrator that drives states directly.
      _refs: function () {
        return mounted;
      },
      _showGate: showGate,
      _showLoading: showLoading,
    };
  }

  // ===========================================================================
  // SECTION 6 — Node export. Exposed only under CommonJS (a no-op in the browser).
  // Lets web/e2e/completion-gate.simulation.cjs assert the SAME code the page runs.
  // ===========================================================================
  var pureApi = {
    FIELD_DEFS: FIELD_DEFS,
    DEFAULT_REQUIRED_FIELDS: DEFAULT_REQUIRED_FIELDS,
    resolveRequiredFields: oskResolveRequiredFields,
    isFieldMissing: oskIsFieldMissing,
    getMissingRequiredFields: oskGetMissingRequiredFields,
    isProfileComplete: oskIsProfileComplete,
    buildPatch: oskBuildPatch,
    parseProblemErrors: oskParseProblemErrors,
    computeGateView: oskComputeGateView,
    fetchProfile: oskFetchProfile,
    submitProfile: oskSubmitProfile,
    buildGateForm: oskBuildGateForm,
    buildLoadingOverlay: oskBuildLoadingOverlay,
    renderFormErrors: oskRenderFormErrors,
    readValues: oskReadValues,
    createGate: oskCreateGate,
  };

  if (typeof module !== "undefined" && module.exports) {
    module.exports = pureApi;
  }

  // ===========================================================================
  // SECTION 7 — BROWSER BOOTSTRAP. Runs ONLY in a real browser (guarded). Injects the
  // gate's stylesheet, wires window.OSKAuth, and auto-mounts the gate on first login
  // when the profile is incomplete. `require()`ing this file in Node runs none of this.
  // ===========================================================================
  if (typeof window === "undefined" || typeof document === "undefined") {
    return;
  }

  (function () {
    "use strict";

    var appConfig = window.__APP_CONFIG__ || {};
    var gateConfig = appConfig.completionGate || {};

    // Master kill-switch: an operator can disable the whole gate from config.js without
    // a code change (default ON).
    if (gateConfig.enabled === false) {
      return;
    }

    var A = window.OSKAuth;
    // Without the auth module we cannot know who is signed in — do nothing (the page's
    // own OSK-74 wiring already shows a "sign-in module failed to load" notice).
    if (!A) {
      return;
    }

    // Inject the stylesheet ONCE. Built from the palette tokens (with fallbacks) so it
    // re-skins with the theme, including the sketch theme, and never assumes a page owns
    // those variables.
    injectStyleOnce();

    // Expose the pure/controller API for an orchestrator or the sibling gates.
    window.OSKCompletionGate = pureApi;

    // Body-scroll lock helper: freeze the page behind the blocking overlay, restore on
    // dismiss. Saved/restored so we never clobber a value another module set.
    var savedOverflow = null;
    function lockScroll(lock) {
      var body = document.body;
      if (!body) {
        return;
      }
      if (lock) {
        if (savedOverflow === null) {
          savedOverflow = body.style.overflow || "";
        }
        body.style.overflow = "hidden";
      } else {
        body.style.overflow = savedOverflow || "";
        savedOverflow = null;
      }
    }

    // Build the single controller for this page.
    var gate = oskCreateGate({
      document: document,
      mountNode: document.body,
      getToken: function () {
        return A.getIdToken();
      },
      apiBaseUrl: appConfig.apiBaseUrl,
      fetchImpl: window.fetch ? window.fetch.bind(window) : null,
      config: gateConfig,
      lockScroll: lockScroll,
      onSignOut: function () {
        // Tear down first so the overlay is gone even if signOut is slow, then sign out.
        gate.reset();
        if (A && typeof A.signOut === "function") {
          A.signOut().catch(function () {
            /* nothing useful to show on a sign-out error */
          });
        }
      },
    });

    // Auto-mount is on by default; an orchestrator can disable it (to sequence the gates
    // itself via window.OSKCompletionGate) with `completionGate.autoStart: false`.
    if (gateConfig.autoStart === false) {
      return;
    }

    // Drive the gate from auth state: on the transition INTO signed-in, run the check;
    // on sign-out, tear any overlay down. Idempotent — check() is safe to re-enter.
    var wasSignedIn = false;
    function onAuth(user) {
      var signedIn = !!user;
      if (signedIn && !wasSignedIn) {
        wasSignedIn = true;
        gate.check();
      } else if (!signedIn && wasSignedIn) {
        wasSignedIn = false;
        gate.reset();
      }
    }

    A.onAuthStateChanged(onAuth);
    // Also settle once auth is ready, covering the not-configured/error states that never
    // emit an auth event (the gate simply stays dormant in those cases).
    if (A.ready && typeof A.ready.then === "function") {
      A.ready.then(function () {
        onAuth(A.getState ? A.getState().user : null);
      }).catch(function () {
        /* auth failed to settle — leave the gate dormant, OSK-74 shows the notice */
      });
    }

    // -----------------------------------------------------------------------
    // Stylesheet. Kept inline (injected once) so the page needs only the <script>.
    // -----------------------------------------------------------------------
    function injectStyleOnce() {
      if (document.getElementById("osk-cg-style")) {
        return;
      }
      var css =
        ".osk-cg-overlay{position:fixed;inset:0;z-index:1000;display:flex;" +
        "align-items:center;justify-content:center;padding:1.25rem;" +
        "background:rgba(0,0,0,0.62);backdrop-filter:blur(2px);" +
        "-webkit-backdrop-filter:blur(2px);overflow-y:auto;}" +
        ".osk-cg-overlay--loading{color:var(--fg,#e6edf3);}" +
        ".osk-cg-loading-text{margin:0;font:inherit;color:var(--fg,#e6edf3);" +
        "background:var(--panel,#161b22);border:1px solid var(--border,#30363d);" +
        "border-radius:10px;padding:0.9rem 1.1rem;}" +
        ".osk-cg-dialog{width:100%;max-width:26rem;box-sizing:border-box;" +
        "background:var(--panel,#161b22);color:var(--fg,#e6edf3);" +
        "border:1px solid var(--border,#30363d);border-radius:12px;" +
        "padding:1.4rem 1.4rem 1.1rem;box-shadow:0 12px 40px rgba(0,0,0,0.45);}" +
        ".osk-cg-title{margin:0 0 0.35rem;font-size:1.15rem;letter-spacing:-0.01em;}" +
        ".osk-cg-intro{margin:0 0 1rem;color:var(--muted,#8b949e);font-size:0.88rem;}" +
        ".osk-cg-field{display:block;margin-bottom:0.9rem;}" +
        ".osk-cg-label{display:block;font-size:0.8rem;color:var(--muted,#8b949e);" +
        "margin-bottom:0.3rem;}" +
        ".osk-cg-input{width:100%;box-sizing:border-box;font:inherit;" +
        "color:var(--fg,#e6edf3);background:var(--bg,#0d1117);" +
        "border:1px solid var(--border,#30363d);border-radius:8px;" +
        "padding:0.6rem 0.7rem;-webkit-appearance:none;appearance:none;}" +
        ".osk-cg-input:focus-visible{outline:2px solid var(--accent,#4c8bf5);" +
        "outline-offset:1px;border-color:var(--accent,#4c8bf5);}" +
        ".osk-cg-input[aria-invalid=\"true\"]{border-color:var(--down,#f85149);}" +
        ".osk-cg-help{display:block;margin-top:0.3rem;font-size:0.75rem;" +
        "color:var(--muted,#8b949e);}" +
        ".osk-cg-field-error{display:block;margin-top:0.3rem;font-size:0.78rem;" +
        "color:var(--down,#f85149);}" +
        ".osk-cg-field-error[hidden]{display:none;}" +
        ".osk-cg-general-error{margin:0.4rem 0 0.9rem;font-size:0.82rem;" +
        "color:var(--down,#f85149);}" +
        ".osk-cg-general-error[hidden]{display:none;}" +
        ".osk-cg-submit{width:100%;min-height:44px;font:inherit;font-weight:600;" +
        "color:#fff;background:var(--accent,#4c8bf5);border:1px solid transparent;" +
        "border-radius:8px;padding:0.55rem 1rem;cursor:pointer;margin-top:0.2rem;" +
        "-webkit-appearance:none;appearance:none;}" +
        ".osk-cg-submit:hover:not([disabled]){filter:brightness(1.08);}" +
        ".osk-cg-submit:focus-visible{outline:2px solid var(--accent,#4c8bf5);" +
        "outline-offset:2px;}" +
        ".osk-cg-submit[disabled]{opacity:0.6;cursor:default;}" +
        ".osk-cg-footer{margin-top:0.9rem;text-align:center;}" +
        ".osk-cg-signout{font:inherit;font-size:0.8rem;color:var(--muted,#8b949e);" +
        "background:transparent;border:0;cursor:pointer;text-decoration:underline;" +
        "padding:0.3rem;}" +
        ".osk-cg-signout:hover{color:var(--fg,#e6edf3);}" +
        "@media (max-width:420px){.osk-cg-dialog{padding:1.1rem 1rem 0.9rem;}}";
      var style = document.createElement("style");
      style.id = "osk-cg-style";
      style.textContent = css;
      (document.head || document.documentElement).appendChild(style);
    }
  })();
})();
