// theme-switcher.js — shared, config-driven in-app theme switcher (OSK-171).
//
// WHAT: one small module that gives EVERY page a consistent, discoverable,
//       mobile-friendly way to change the UI theme. It:
//         1. drives the existing footer "Theme: …" toggle button (#theme-toggle)
//            where a page already has one — REUSING it, never duplicating its
//            logic (this file replaces the per-page inline OSK-127 toggle scripts,
//            which were copy-pasted across index/app/status/ops/help/…), and
//         2. mounts an always-present floating switcher docked in a screen corner
//            so the control is reachable WITHOUT scrolling to the footer — the
//            fix for "no way to change theme on mobile / in the Capacitor WebView"
//            (OSK-146), where the footer sits far below the fold.
//       Both controls drive ONE shared controller, so there is a single source of
//       truth for the active theme and they never disagree.
//
// WHY a separate file (loaded per page as `<script src="/theme-switcher.js">`):
//       same rationale as tour.js / terms.js — a self-contained, dependency-free
//       classic script that injects its own styles + control and reads the optional
//       `window.__APP_CONFIG__.theme` block, so a page opts in with a single include
//       and, at most, keeps its existing #theme-toggle button. No bundler.
//
// THEME MODEL (OSK-110/127): the active theme is expressed as a `data-theme`
//       attribute on <html>. The DEFAULT (dark) theme is the ABSENCE of the
//       attribute; any other theme (today just "sketch") sets
//       data-theme="<id>". The choice is persisted under localStorage["osk-theme"]
//       and applied BEFORE first paint by a tiny inline <head> script on each page
//       (so there is no flash of the wrong theme). THIS module coexists with that
//       pre-paint script: on load it re-reads the same key and re-applies, which is
//       idempotent, and it keeps every control's label in sync.
//
// EXTENSIBILITY: themes are a config-driven, ordered REGISTRY (id + label). Adding a
//       third theme is: (a) add its CSS under `[data-theme="<id>"]`, and (b) add
//       `{ id, label }` to `window.__APP_CONFIG__.theme.themes` (or leave config
//       alone and it inherits the built-in default list). The controls "cycle"
//       through the registry, so no code change is needed to support more themes.
//
// TESTABILITY: every decision here is a pure data->data helper (or builds DOM
//       against an injected `document`), exported via `module.exports` when required
//       from Node — exactly like the sibling modules. web/e2e/theme-switcher.simulation.cjs
//       drives these with a FAKE DOM + FAKE storage to prove toggle -> data-theme
//       change, localStorage persistence, apply-on-load across themes, and cycling,
//       with no browser. The browser bootstrap at the foot only runs when a real
//       `window`/`document` exist, so requiring this file in Node is side-effect free.
//
// SECURITY: XSS-safe — only textContent and fixed attribute values are ever written;
//       no innerHTML, no untrusted data. Theme ids from storage/config are validated
//       against the registry before use (an unknown id falls back to default).

"use strict";

// ---------------------------------------------------------------------------
// Constants + the built-in theme registry.
// ---------------------------------------------------------------------------

// The single localStorage key the whole theme system shares (OSK-110/127). MUST
// match the <head> pre-paint script's key on every page.
var OSK_THEME_KEY = "osk-theme";

// The base theme's id. The default (dark) theme is represented by the ABSENCE of a
// data-theme attribute, so this id is the one we NEVER write onto <html>.
var OSK_THEME_DEFAULT_ID = "default";

// The built-in, ordered registry used when config supplies nothing. Order defines the
// cycle order; the first entry MUST be the base "default" theme. Extend via
// window.__APP_CONFIG__.theme.themes rather than editing this list.
var OSK_THEME_DEFAULT_THEMES = [
  { id: "default", label: "Default" },
  { id: "sketch", label: "Sketch" },
];

// DOM ids/classes for the injected floating switcher + its style block (single-mount
// guards key off these).
var OSK_THEME_STYLE_ID = "osk-theme-switcher-styles";
var OSK_THEME_SWITCHER_ID = "osk-theme-switcher";
var OSK_THEME_TOGGLE_ID = "theme-toggle"; // the existing footer button, where present

// ---------------------------------------------------------------------------
// Pure helpers — no DOM, no globals. These are the unit-testable core.
// ---------------------------------------------------------------------------

// Turn ONE proposed capitalised label out of a raw id, e.g. "sketch" -> "Sketch".
// Used only as a fallback when a theme has no explicit label.
function oskCapitalize(id) {
  var s = String(id == null ? "" : id);
  return s.length ? s.charAt(0).toUpperCase() + s.slice(1) : s;
}

// Normalise a proposed themes list into a clean, ordered registry:
//   - drop entries without a usable string id,
//   - default a missing label to the capitalised id,
//   - de-duplicate by id (first wins),
//   - GUARANTEE the base "default" theme exists and is FIRST (the cycle's home).
// Any invalid/empty input falls back to the built-in default registry, so the module
// is always safe to run even with a broken config block.
function oskNormalizeThemes(list) {
  var out = [];
  var seen = {};
  var validCount = 0; // how many usable entries the caller actually supplied
  if (Array.isArray(list)) {
    for (var i = 0; i < list.length; i++) {
      var t = list[i];
      if (!t || typeof t.id !== "string" || !t.id) {
        continue;
      }
      if (Object.prototype.hasOwnProperty.call(seen, t.id)) {
        continue;
      }
      seen[t.id] = true;
      validCount++;
      out.push({
        id: t.id,
        label: typeof t.label === "string" && t.label ? t.label : oskCapitalize(t.id),
      });
    }
  }
  // A wholly empty/broken config (no usable entries at all) -> the built-in registry, so
  // the user still gets the full default+sketch set (not a lone, un-switchable default).
  if (validCount < 1) {
    return OSK_THEME_DEFAULT_THEMES.slice();
  }
  // Ensure the base default theme is present and sits first.
  if (!Object.prototype.hasOwnProperty.call(seen, OSK_THEME_DEFAULT_ID)) {
    out.unshift({ id: OSK_THEME_DEFAULT_ID, label: "Default" });
  } else if (out.length && out[0].id !== OSK_THEME_DEFAULT_ID) {
    var idx = -1;
    for (var j = 0; j < out.length; j++) {
      if (out[j].id === OSK_THEME_DEFAULT_ID) {
        idx = j;
        break;
      }
    }
    if (idx > 0) {
      var def = out.splice(idx, 1)[0];
      out.unshift(def);
    }
  }
  return out;
}

// Resolve the effective registry from the runtime config block (or defaults).
function oskResolveThemes(config) {
  var themeCfg = (config && config.theme) || {};
  return oskNormalizeThemes(themeCfg.themes);
}

// Whether the floating switcher should be mounted, per config. Defaults to ON (the
// whole point of the ticket is a discoverable control) unless an operator sets
// window.__APP_CONFIG__.theme.switcher === false. Also requires >=2 themes — with a
// single theme there is nothing to switch, so no control is shown.
function oskSwitcherEnabled(config, themes) {
  var themeCfg = (config && config.theme) || {};
  var on = themeCfg.switcher !== false; // default true; only an explicit false disables
  return on && Array.isArray(themes) && themes.length >= 2;
}

// Find a theme object by id (or null).
function oskThemeById(themes, id) {
  for (var i = 0; i < themes.length; i++) {
    if (themes[i].id === id) {
      return themes[i];
    }
  }
  return null;
}

// Coerce an arbitrary id to a KNOWN theme id, falling back when unknown/absent. This
// is the guard that stops a stale/garbage localStorage or config value ever selecting
// a theme that doesn't exist.
function oskSanitizeThemeId(themes, id, fallback) {
  var fb = fallback == null ? OSK_THEME_DEFAULT_ID : fallback;
  return oskThemeById(themes, id) ? id : fb;
}

// The human label for a theme id (falls back to the capitalised id).
function oskLabelFor(themes, id) {
  var t = oskThemeById(themes, id);
  return t ? t.label : oskCapitalize(id);
}

// The footer toggle button's exact text, e.g. "Theme: Sketch". Kept identical to the
// legacy inline script's wording so the existing theme.spec assertions still hold.
function oskButtonText(themes, id) {
  return "Theme: " + oskLabelFor(themes, id);
}

// The next theme id in the cycle (wraps to the start). An unknown current id resolves to
// the first theme (the default "home") — in practice `currentId` is always sanitised
// before this is called, so this only guards a corrupt state.
function oskNextThemeId(themes, currentId) {
  if (!themes.length) {
    return OSK_THEME_DEFAULT_ID;
  }
  var idx = -1;
  for (var i = 0; i < themes.length; i++) {
    if (themes[i].id === currentId) {
      idx = i;
      break;
    }
  }
  return themes[(idx + 1) % themes.length].id;
}

// Accessible label for the floating switcher, announcing the CURRENT theme + that the
// control cycles. e.g. "Switch theme (current: Default)".
function oskSwitcherAriaLabel(themes, id) {
  return "Switch theme (current: " + oskLabelFor(themes, id) + ")";
}

// ---------------------------------------------------------------------------
// Attribute / storage side-effects, each isolated + injectable so the sim can pass a
// FAKE root element and FAKE storage.
// ---------------------------------------------------------------------------

// Apply a theme to the root element: the default theme REMOVES the attribute (its CSS
// is the attribute-absent base), any other theme SETS data-theme="<id>". Tolerates a
// duck-typed root (get/set/removeAttribute) so the sim can drive it.
function oskApplyThemeAttr(root, id, defaultId) {
  if (!root) {
    return;
  }
  var base = defaultId == null ? OSK_THEME_DEFAULT_ID : defaultId;
  if (id === base) {
    root.removeAttribute("data-theme");
  } else {
    root.setAttribute("data-theme", id);
  }
}

// Read the persisted theme id, validated against the registry. A missing key, an
// unknown value, or a throwing storage all resolve to the fallback (default) — the
// module never fails closed on a bad read.
function oskReadStoredTheme(storage, key, themes, fallback) {
  var fb = fallback == null ? OSK_THEME_DEFAULT_ID : fallback;
  var raw = null;
  try {
    raw = storage && storage.getItem ? storage.getItem(key) : null;
  } catch (e) {
    /* storage unavailable (private mode / disabled) — fall back to default */
    return fb;
  }
  return oskSanitizeThemeId(themes, raw, fb);
}

// Persist the chosen theme id. Best-effort: a throwing/absent storage is swallowed so
// the theme still applies for the current session (persistence is a bonus, not a
// requirement, for the switch to work). Returns whether the write succeeded.
function oskPersistTheme(storage, key, id) {
  try {
    if (storage && storage.setItem) {
      storage.setItem(key, id);
      return true;
    }
  } catch (e) {
    /* persistence unavailable — theme still applies for this session */
  }
  return false;
}

// ---------------------------------------------------------------------------
// The controller — the single source of truth binding the registry, a root element
// and a storage together. Everything (the footer button, the floating switcher, the
// window.OSKTheme API) drives THIS, so all controls stay in lock-step. Pure of the
// browser: `root` + `storage` are injected, so the sim constructs one over fakes.
// ---------------------------------------------------------------------------
function oskCreateController(opts) {
  var options = opts || {};
  var themes = options.themes && options.themes.length
    ? options.themes
    : OSK_THEME_DEFAULT_THEMES.slice();
  var root = options.root || null;
  var storage = options.storage || null;
  var key = options.key || OSK_THEME_KEY;
  var defaultId = options.defaultId || OSK_THEME_DEFAULT_ID;

  var currentId = defaultId;
  var subs = [];

  function notify() {
    for (var i = 0; i < subs.length; i++) {
      try {
        subs[i](currentId, themes);
      } catch (e) {
        /* a broken subscriber must never break the switch */
      }
    }
  }

  // Apply + persist + broadcast an (arbitrary, sanitised) theme id. The one write path.
  function apply(id) {
    currentId = oskSanitizeThemeId(themes, id, defaultId);
    oskApplyThemeAttr(root, currentId, defaultId);
    oskPersistTheme(storage, key, currentId);
    notify();
    return currentId;
  }

  return {
    themes: themes,
    defaultId: defaultId,
    // Read the persisted choice and apply it. Called once on load; idempotent with the
    // <head> pre-paint script (both read the same key + produce the same attribute).
    init: function () {
      return apply(oskReadStoredTheme(storage, key, themes, defaultId));
    },
    current: function () {
      return currentId;
    },
    set: function (id) {
      return apply(id);
    },
    // Advance to the next theme in the registry (the toggle/cycle action).
    cycle: function () {
      return apply(oskNextThemeId(themes, currentId));
    },
    // Register a listener called on every change (and never on register — callers do an
    // explicit initial render). Returns an unsubscribe fn.
    subscribe: function (fn) {
      if (typeof fn === "function") {
        subs.push(fn);
      }
      return function () {
        var i = subs.indexOf(fn);
        if (i >= 0) {
          subs.splice(i, 1);
        }
      };
    },
  };
}

// ---------------------------------------------------------------------------
// DOM builders — take an injected `document`/element so the sim can drive them with a
// fake DOM (mirrors terms.js buildGate / tour.js).
// ---------------------------------------------------------------------------

// Reflect the active theme onto the legacy footer toggle button: exact "Theme: <Label>"
// text + aria-pressed (true when a non-default theme is active). Kept byte-compatible
// with the old inline script so theme.spec.ts's assertions are unchanged.
function oskRenderToggleButton(btn, themes, id, defaultId) {
  if (!btn) {
    return;
  }
  var base = defaultId == null ? OSK_THEME_DEFAULT_ID : defaultId;
  btn.textContent = oskButtonText(themes, id);
  btn.setAttribute("aria-pressed", id !== base ? "true" : "false");
}

// Build the floating switcher button (hidden nothing — it is always visible). A pill
// with a contrast glyph + a short "Theme" label so it is obviously a theme control; the
// live theme is carried on aria-label/title (the page itself is the visual feedback, so
// the visible label stays fixed-width — no layout shift on switch).
function oskBuildSwitcher(doc, themes, id) {
  var btn = doc.createElement("button");
  btn.type = "button";
  btn.id = OSK_THEME_SWITCHER_ID;
  btn.className = "osk-theme-switcher";

  var icon = doc.createElement("span");
  icon.className = "osk-theme-switcher__icon";
  icon.setAttribute("aria-hidden", "true");
  icon.textContent = "◐"; // ◐ half-filled circle = contrast/theme

  var label = doc.createElement("span");
  label.className = "osk-theme-switcher__label";
  label.textContent = "Theme";

  btn.appendChild(icon);
  btn.appendChild(label);

  oskSyncSwitcher(btn, themes, id);
  return btn;
}

// Keep the floating switcher's accessible name + tooltip in step with the active theme.
function oskSyncSwitcher(btn, themes, id) {
  if (!btn) {
    return;
  }
  var text = oskSwitcherAriaLabel(themes, id);
  btn.setAttribute("aria-label", text);
  btn.setAttribute("title", text);
}

// The floating switcher's CSS (dark default + sketch override). Mirrors the tour.js
// launcher language (same corner-dock idiom, tap target, palette tokens) but docks
// bottom-LEFT so it never collides with tour.js's bottom-right "?" launcher.
function oskSwitcherStyles() {
  return [
    ".osk-theme-switcher{position:fixed;left:1rem;bottom:1rem;z-index:2147482000;" +
      "min-height:44px;min-width:44px;padding:0 0.9rem;border-radius:999px;" +
      "display:inline-flex;align-items:center;gap:0.4rem;" +
      "font:inherit;font-weight:600;font-size:0.85rem;line-height:1;" +
      "background:var(--panel,#161b22);color:var(--fg,#e6edf3);" +
      "border:1px solid var(--border,#30363d);box-shadow:0 4px 14px rgba(1,4,9,0.4);" +
      "cursor:pointer;-webkit-appearance:none;appearance:none;-webkit-tap-highlight-color:transparent;}",
    ".osk-theme-switcher:hover{border-color:var(--accent,#4c8bf5);}",
    ".osk-theme-switcher:focus-visible{outline:2px solid var(--accent,#4c8bf5);outline-offset:2px;}",
    ".osk-theme-switcher__icon{font-size:1.05rem;line-height:1;}",
    // Phone-width: nudge into the corner like the tour launcher does.
    "@media (max-width:640px){.osk-theme-switcher{left:0.75rem;bottom:0.75rem;}}",
    // Honour reduced-motion (no transitions to kill today, but future-proofs restyles).
    "@media (prefers-reduced-motion:reduce){.osk-theme-switcher{transition:none!important;}}",
    // Sketch theme: wobbly hand-drawn edge, matching tour.js's launcher + the panels.
    "[data-theme='sketch'] .osk-theme-switcher{border-radius:14px 225px 14px 225px/225px 14px 225px 14px;" +
      "box-shadow:1px 2px 0 rgba(22,40,59,0.13);}",
  ].join("\n");
}

// ---------------------------------------------------------------------------
// Node export (CommonJS only — a classic browser <script> has no `module`). Lets
// web/e2e/theme-switcher.simulation.cjs assert the SAME code the page runs.
// ---------------------------------------------------------------------------
if (typeof module !== "undefined" && module.exports) {
  module.exports = {
    THEME_KEY: OSK_THEME_KEY,
    DEFAULT_ID: OSK_THEME_DEFAULT_ID,
    DEFAULT_THEMES: OSK_THEME_DEFAULT_THEMES,
    SWITCHER_ID: OSK_THEME_SWITCHER_ID,
    TOGGLE_ID: OSK_THEME_TOGGLE_ID,
    normalizeThemes: oskNormalizeThemes,
    resolveThemes: oskResolveThemes,
    switcherEnabled: oskSwitcherEnabled,
    themeById: oskThemeById,
    sanitizeThemeId: oskSanitizeThemeId,
    labelFor: oskLabelFor,
    buttonText: oskButtonText,
    nextThemeId: oskNextThemeId,
    switcherAriaLabel: oskSwitcherAriaLabel,
    applyThemeAttr: oskApplyThemeAttr,
    readStoredTheme: oskReadStoredTheme,
    persistTheme: oskPersistTheme,
    createController: oskCreateController,
    renderToggleButton: oskRenderToggleButton,
    buildSwitcher: oskBuildSwitcher,
    syncSwitcher: oskSyncSwitcher,
    switcherStyles: oskSwitcherStyles,
  };
}

// ===========================================================================
// BROWSER BOOTSTRAP
// Runs ONLY in a real browser (guarded by typeof window + typeof document). Wires the
// existing footer toggle (if any) + mounts the floating switcher, both onto one shared
// controller, and exposes window.OSKTheme. Loaded at the FOOT of each page so <html>
// and the footer button already exist. Additive: it mutates NO existing markup beyond
// (re)labelling the footer button it was designed to drive; it only APPENDS its own
// style block + switcher button.
// ===========================================================================
if (typeof window !== "undefined" && typeof document !== "undefined") {
  (function () {
    var config = window.__APP_CONFIG__ || {};
    var themes = oskResolveThemes(config);

    // One controller over the real <html> + localStorage.
    var controller = oskCreateController({
      themes: themes,
      root: document.documentElement,
      storage: window.localStorage,
      key: OSK_THEME_KEY,
      defaultId: OSK_THEME_DEFAULT_ID,
    });

    // Apply the persisted theme now (idempotent with the <head> pre-paint script), and
    // seed currentId from storage so the very first render of every control is correct.
    controller.init();

    // (1) REUSE the existing footer toggle button, where the page has one. Clicking it
    //     cycles; every change re-labels it. This REPLACES the old per-page inline
    //     OSK-127 script — so there is exactly one click handler, never a double toggle.
    var toggleBtn = document.getElementById(OSK_THEME_TOGGLE_ID);
    if (toggleBtn) {
      toggleBtn.addEventListener("click", function () {
        controller.cycle();
      });
      controller.subscribe(function (id) {
        oskRenderToggleButton(toggleBtn, themes, id, OSK_THEME_DEFAULT_ID);
      });
      // Initial label (the pre-paint script may have already set sketch).
      oskRenderToggleButton(toggleBtn, themes, controller.current(), OSK_THEME_DEFAULT_ID);
    }

    // (2) MOUNT the always-present floating switcher (unless disabled or <2 themes) — the
    //     discoverable, mobile-friendly control the ticket calls for. Injected once.
    if (oskSwitcherEnabled(config, themes)) {
      if (!document.getElementById(OSK_THEME_STYLE_ID)) {
        var style = document.createElement("style");
        style.id = OSK_THEME_STYLE_ID;
        style.textContent = oskSwitcherStyles();
        (document.head || document.documentElement).appendChild(style);
      }
      if (!document.getElementById(OSK_THEME_SWITCHER_ID)) {
        var switcher = oskBuildSwitcher(document, themes, controller.current());
        switcher.addEventListener("click", function () {
          controller.cycle();
        });
        controller.subscribe(function (id) {
          oskSyncSwitcher(switcher, themes, id);
        });
        document.body.appendChild(switcher);
      }
    }

    // (3) Small public API for other modules / debugging. Reads + drives the same
    //     controller, so programmatic changes update every control too.
    window.OSKTheme = {
      themes: themes,
      get: function () {
        return controller.current();
      },
      set: function (id) {
        return controller.set(id);
      },
      cycle: function () {
        return controller.cycle();
      },
    };

    // Broadcast changes for any interested module (optional; guarded for old engines).
    controller.subscribe(function (id) {
      if (typeof window.CustomEvent === "function") {
        window.dispatchEvent(new window.CustomEvent("osk:themechange", { detail: { theme: id } }));
      }
    });
  })();
}
