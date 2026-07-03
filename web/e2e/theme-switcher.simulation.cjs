// theme-switcher.simulation.cjs — headless Node simulation of the OSK-171 in-app theme
// switcher (web/theme-switcher.js).
//
// WHY this exists: the switcher's browser bootstrap needs a real DOM + a live page, but
// every DECISION it makes is a PURE data->data transform (or builds DOM against an
// injectable `document`) — web/theme-switcher.js exports those helpers for exactly this
// reason (its browser bootstrap is skipped because `window` is undefined under Node). This
// script requires those helpers and drives them with a FAKE root element, FAKE storage and
// a FAKE document, asserting the ACs the ticket calls out:
//   - toggle/cycle CHANGES the <html> data-theme attribute (default = attr removed, any
//     other theme = data-theme="<id>"),
//   - the choice PERSISTS to localStorage["osk-theme"],
//   - the persisted choice is APPLIED ON LOAD (init) across themes,
//   - CYCLING walks the registry and WRAPS (and works for >2 themes, proving "more themes
//     are easy"),
//   - the footer #theme-toggle button stays byte-compatible with the old inline script
//     ("Theme: <Label>" + aria-pressed), and the floating switcher renders + stays in sync,
//   - bad input (unknown/garbage stored id, throwing storage, broken config) never throws
//     and falls back to the default theme.
//
// It is a plain assertion harness (no framework, no deps): it prints each check and exits
// non-zero on the first failure, so it doubles as a CI-friendly gate. It is a `.cjs` file
// OUTSIDE web/e2e/tests/, so Playwright (testDir ./tests) never picks it up. Mirrors the
// sibling *.simulation.cjs files.
//
// Run:  node web/e2e/theme-switcher.simulation.cjs

"use strict";

const path = require("node:path");
// Require the REAL module under test — the SAME code the page runs.
const T = require(path.resolve(__dirname, "..", "theme-switcher.js"));

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
  assert(
    label + " (got " + JSON.stringify(actual) + ")",
    typeof actual === "string" && regex.test(actual),
  );
}

// ---------------------------------------------------------------------------
// Tiny FAKE DOM/storage — just the surface theme-switcher.js touches. No jsdom.
// ---------------------------------------------------------------------------

// A duck-typed element: attributes + textContent + className + children + click wiring.
function makeEl(tag) {
  return {
    tagName: tag,
    type: "",
    id: "",
    className: "",
    textContent: "",
    attributes: {},
    childNodes: [],
    listeners: {},
    appendChild(child) {
      this.childNodes.push(child);
      return child;
    },
    setAttribute(name, value) {
      this.attributes[name] = String(value);
    },
    getAttribute(name) {
      return Object.prototype.hasOwnProperty.call(this.attributes, name) ? this.attributes[name] : null;
    },
    removeAttribute(name) {
      delete this.attributes[name];
    },
    addEventListener(type, fn) {
      (this.listeners[type] = this.listeners[type] || []).push(fn);
    },
    // Test helper: fire a click as the browser would.
    click() {
      (this.listeners.click || []).forEach((fn) => fn());
    },
  };
}

function makeFakeDoc() {
  return { createElement: makeEl };
}

// A fake localStorage. `blow:true` makes every access throw (private-mode / disabled).
function makeStorage(initial, blow) {
  const map = Object.assign({}, initial || {});
  return {
    getItem(k) {
      if (blow) {
        throw new Error("storage blocked");
      }
      return Object.prototype.hasOwnProperty.call(map, k) ? map[k] : null;
    },
    setItem(k, v) {
      if (blow) {
        throw new Error("storage blocked");
      }
      map[k] = String(v);
    },
    _dump() {
      return map;
    },
  };
}

// Depth-first collect textContent of an element tree.
function allText(node) {
  let out = node.textContent || "";
  for (const c of node.childNodes || []) {
    out += " " + allText(c);
  }
  return out;
}

const DEFAULT = T.DEFAULT_THEMES; // [{default},{sketch}]

(function main() {
  // -------------------------------------------------------------------------
  // 1) normalizeThemes / resolveThemes — the config-driven registry.
  // -------------------------------------------------------------------------
  console.log("\nnormalizeThemes() / resolveThemes():");
  assertEqual("empty -> built-in default list length", T.normalizeThemes([]).length, 2);
  assertEqual("invalid input -> falls back (length)", T.normalizeThemes(null).length, 2);
  {
    // A config missing "default" gets it prepended (default is always the cycle home).
    const norm = T.normalizeThemes([{ id: "sketch", label: "Sketch" }]);
    assertEqual("missing default is prepended", norm[0].id, "default");
    assertEqual("declared theme retained", norm[1].id, "sketch");
  }
  {
    // "default" declared out of order is moved to the front.
    const norm = T.normalizeThemes([
      { id: "sketch", label: "Sketch" },
      { id: "default", label: "Default" },
    ]);
    assertEqual("default moved to front", norm[0].id, "default");
  }
  {
    // Label fallback + dedupe + dropping id-less entries.
    const norm = T.normalizeThemes([
      { id: "default" },
      { id: "neon" },
      { id: "neon", label: "IGNORED DUP" },
      { label: "no id" },
    ]);
    assertEqual("dedup by id (neon once)", norm.filter((t) => t.id === "neon").length, 1);
    assertEqual("label falls back to capitalised id", T.labelFor(norm, "neon"), "Neon");
    assertEqual("id-less entry dropped (length)", norm.length, 2);
  }
  {
    const themes = T.resolveThemes({ theme: { themes: [{ id: "default" }, { id: "sketch" }, { id: "neon" }] } });
    assertEqual("resolveThemes reads config (length)", themes.length, 3);
    const fallback = T.resolveThemes({});
    assertEqual("resolveThemes falls back to default list", fallback.length, 2);
  }

  // -------------------------------------------------------------------------
  // 2) switcherEnabled — default ON, explicit false OFF, <2 themes OFF.
  // -------------------------------------------------------------------------
  console.log("\nswitcherEnabled():");
  assertEqual("default (no config) => ON", T.switcherEnabled({}, DEFAULT), true);
  assertEqual("explicit switcher:false => OFF", T.switcherEnabled({ theme: { switcher: false } }, DEFAULT), false);
  assertEqual("switcher:true => ON", T.switcherEnabled({ theme: { switcher: true } }, DEFAULT), true);
  assertEqual("single theme => OFF (nothing to switch)", T.switcherEnabled({}, [{ id: "default", label: "Default" }]), false);

  // -------------------------------------------------------------------------
  // 3) Pure lookups: sanitize / label / buttonText / nextThemeId / aria.
  // -------------------------------------------------------------------------
  console.log("\npure lookups:");
  assertEqual("sanitize known id", T.sanitizeThemeId(DEFAULT, "sketch"), "sketch");
  assertEqual("sanitize unknown -> default", T.sanitizeThemeId(DEFAULT, "bogus"), "default");
  assertEqual("sanitize null -> default", T.sanitizeThemeId(DEFAULT, null), "default");
  assertEqual("buttonText default", T.buttonText(DEFAULT, "default"), "Theme: Default");
  assertEqual("buttonText sketch", T.buttonText(DEFAULT, "sketch"), "Theme: Sketch");
  assertEqual("next(default) -> sketch", T.nextThemeId(DEFAULT, "sketch"), "default"); // wraps
  assertEqual("next(sketch) -> default (wrap)", T.nextThemeId(DEFAULT, "sketch"), "default");
  assertEqual("next(default) -> sketch again", T.nextThemeId(DEFAULT, "default"), "sketch");
  assertEqual("next(unknown) -> first theme (home/default)", T.nextThemeId(DEFAULT, "??"), "default");
  assertMatch("aria label mentions current theme", T.switcherAriaLabel(DEFAULT, "sketch"), /Sketch/);

  // A 3-theme registry cycles + wraps: default -> sketch -> neon -> default.
  {
    const three = T.normalizeThemes([{ id: "default" }, { id: "sketch" }, { id: "neon" }]);
    assertEqual("3-theme cycle step 1", T.nextThemeId(three, "default"), "sketch");
    assertEqual("3-theme cycle step 2", T.nextThemeId(three, "sketch"), "neon");
    assertEqual("3-theme cycle wraps", T.nextThemeId(three, "neon"), "default");
  }

  // -------------------------------------------------------------------------
  // 4) applyThemeAttr — default REMOVES the attribute; other themes SET it.
  // -------------------------------------------------------------------------
  console.log("\napplyThemeAttr() (fake root):");
  {
    const root = makeEl("html");
    T.applyThemeAttr(root, "sketch", "default");
    assertEqual("sketch sets data-theme", root.getAttribute("data-theme"), "sketch");
    T.applyThemeAttr(root, "default", "default");
    assertEqual("default removes data-theme", root.getAttribute("data-theme"), null);
  }

  // -------------------------------------------------------------------------
  // 5) readStoredTheme / persistTheme — persistence + graceful degradation.
  // -------------------------------------------------------------------------
  console.log("\nreadStoredTheme() / persistTheme():");
  assertEqual("stored sketch read back", T.readStoredTheme(makeStorage({ "osk-theme": "sketch" }), "osk-theme", DEFAULT), "sketch");
  assertEqual("empty storage -> default", T.readStoredTheme(makeStorage({}), "osk-theme", DEFAULT), "default");
  assertEqual("garbage stored -> default", T.readStoredTheme(makeStorage({ "osk-theme": "zzz" }), "osk-theme", DEFAULT), "default");
  assertEqual("throwing storage -> default (no throw)", T.readStoredTheme(makeStorage({}, true), "osk-theme", DEFAULT), "default");
  {
    const s = makeStorage({});
    assertEqual("persist returns true", T.persistTheme(s, "osk-theme", "sketch"), true);
    assertEqual("persisted value written", s._dump()["osk-theme"], "sketch");
    assertEqual("throwing storage persist -> false (no throw)", T.persistTheme(makeStorage({}, true), "osk-theme", "sketch"), false);
  }

  // -------------------------------------------------------------------------
  // 6) createController — the CORE: apply-on-load, toggle -> attr change +
  //    persistence, cycling, subscriptions.
  // -------------------------------------------------------------------------
  console.log("\ncreateController():");

  // (a) init() with EMPTY storage -> default (attr absent), currentId default.
  {
    const root = makeEl("html");
    const storage = makeStorage({});
    const c = T.createController({ themes: DEFAULT, root, storage, key: "osk-theme", defaultId: "default" });
    c.init();
    assertEqual("cold init -> current default", c.current(), "default");
    assertEqual("cold init -> no data-theme attr", root.getAttribute("data-theme"), null);
    assertEqual("cold init -> persisted default", storage._dump()["osk-theme"], "default");
  }

  // (b) APPLY ON LOAD: init() with stored sketch -> sketch applied pre-interaction.
  {
    const root = makeEl("html");
    const storage = makeStorage({ "osk-theme": "sketch" });
    const c = T.createController({ themes: DEFAULT, root, storage, key: "osk-theme", defaultId: "default" });
    c.init();
    assertEqual("apply-on-load -> current sketch", c.current(), "sketch");
    assertEqual("apply-on-load -> data-theme=sketch", root.getAttribute("data-theme"), "sketch");
  }

  // (c) TOGGLE/CYCLE: default -> sketch changes the attr AND persists; back again clears.
  {
    const root = makeEl("html");
    const storage = makeStorage({});
    const c = T.createController({ themes: DEFAULT, root, storage, key: "osk-theme", defaultId: "default" });
    c.init(); // default

    c.cycle();
    assertEqual("cycle 1 -> sketch current", c.current(), "sketch");
    assertEqual("cycle 1 -> data-theme=sketch", root.getAttribute("data-theme"), "sketch");
    assertEqual("cycle 1 -> persisted sketch", storage._dump()["osk-theme"], "sketch");

    c.cycle();
    assertEqual("cycle 2 -> default current", c.current(), "default");
    assertEqual("cycle 2 -> attr removed", root.getAttribute("data-theme"), null);
    assertEqual("cycle 2 -> persisted default", storage._dump()["osk-theme"], "default");
  }

  // (d) set() applies directly; subscribers fire on change.
  {
    const root = makeEl("html");
    const storage = makeStorage({});
    const c = T.createController({ themes: DEFAULT, root, storage, key: "osk-theme", defaultId: "default" });
    let seen = [];
    c.subscribe((id) => seen.push(id));
    c.set("sketch");
    assertEqual("set(sketch) applied", root.getAttribute("data-theme"), "sketch");
    c.set("bogus"); // sanitised to default
    assertEqual("set(bogus) sanitised to default", c.current(), "default");
    assertEqual("set(bogus) removed attr", root.getAttribute("data-theme"), null);
    assertEqual("subscriber fired twice", seen.length, 2);
    assertEqual("subscriber saw sketch then default", seen.join(","), "sketch,default");
  }

  // (e) Controller over a 3-theme registry cycles through all three.
  {
    const three = T.normalizeThemes([{ id: "default" }, { id: "sketch" }, { id: "neon" }]);
    const root = makeEl("html");
    const c = T.createController({ themes: three, root, storage: makeStorage({}), key: "osk-theme", defaultId: "default" });
    c.init();
    c.cycle();
    assertEqual("3-theme cycle -> sketch", root.getAttribute("data-theme"), "sketch");
    c.cycle();
    assertEqual("3-theme cycle -> neon", root.getAttribute("data-theme"), "neon");
    c.cycle();
    assertEqual("3-theme cycle wraps -> default (attr removed)", root.getAttribute("data-theme"), null);
  }

  // -------------------------------------------------------------------------
  // 7) renderToggleButton — footer button stays byte-compatible with theme.spec.
  // -------------------------------------------------------------------------
  console.log("\nrenderToggleButton() (fake button):");
  {
    const btn = makeEl("button");
    T.renderToggleButton(btn, DEFAULT, "default", "default");
    assertEqual("default label", btn.textContent, "Theme: Default");
    assertEqual("default aria-pressed false", btn.getAttribute("aria-pressed"), "false");
    T.renderToggleButton(btn, DEFAULT, "sketch", "default");
    assertEqual("sketch label", btn.textContent, "Theme: Sketch");
    assertEqual("sketch aria-pressed true", btn.getAttribute("aria-pressed"), "true");
  }

  // -------------------------------------------------------------------------
  // 8) buildSwitcher / syncSwitcher / switcherStyles — the floating control.
  // -------------------------------------------------------------------------
  console.log("\nbuildSwitcher() (fake DOM):");
  {
    const doc = makeFakeDoc();
    const btn = T.buildSwitcher(doc, DEFAULT, "default");
    assertEqual("switcher is a button", btn.tagName, "button");
    assertEqual("switcher has id", btn.id, T.SWITCHER_ID);
    assertEqual("switcher class set", btn.className, "osk-theme-switcher");
    assertMatch("switcher shows Theme label", allText(btn), /Theme/);
    assertMatch("switcher aria-label announces current theme", btn.getAttribute("aria-label"), /Default/);
    // Sync to a new theme updates aria-label + title.
    T.syncSwitcher(btn, DEFAULT, "sketch");
    assertMatch("sync updates aria-label to Sketch", btn.getAttribute("aria-label"), /Sketch/);
    assertMatch("sync updates title to Sketch", btn.getAttribute("title"), /Sketch/);
  }
  {
    const css = T.switcherStyles();
    assertMatch("styles dock bottom-left (left:1rem)", css, /left:1rem/);
    assertMatch("styles avoid tour launcher corner (no right dock in base)", css, /\.osk-theme-switcher\{[^}]*left:1rem/);
    assertMatch("styles have >=44px tap target", css, /min-height:44px/);
    assertMatch("styles include a sketch override", css, /\[data-theme='sketch'\] \.osk-theme-switcher/);
    assertMatch("styles are responsive (<=640px)", css, /max-width:640px/);
  }

  // -------------------------------------------------------------------------
  // 9) INTEGRATION — mirror the browser bootstrap: one controller drives BOTH a
  //    footer button and a floating switcher; a click on either moves everything.
  // -------------------------------------------------------------------------
  console.log("\nintegration (footer + floating driven by one controller):");
  {
    const doc = makeFakeDoc();
    const root = makeEl("html");
    const storage = makeStorage({});
    const themes = DEFAULT;
    const c = T.createController({ themes, root, storage, key: "osk-theme", defaultId: "default" });
    c.init();

    // Footer button wired like the bootstrap.
    const toggle = makeEl("button");
    toggle.addEventListener("click", () => c.cycle());
    c.subscribe((id) => T.renderToggleButton(toggle, themes, id, "default"));
    T.renderToggleButton(toggle, themes, c.current(), "default");

    // Floating switcher wired like the bootstrap.
    const switcher = T.buildSwitcher(doc, themes, c.current());
    switcher.addEventListener("click", () => c.cycle());
    c.subscribe((id) => T.syncSwitcher(switcher, themes, id));

    // Click the FOOTER button -> everything switches to sketch.
    toggle.click();
    assertEqual("footer click -> data-theme=sketch", root.getAttribute("data-theme"), "sketch");
    assertEqual("footer click -> persisted sketch", storage._dump()["osk-theme"], "sketch");
    assertEqual("footer click -> footer label updated", toggle.textContent, "Theme: Sketch");
    assertMatch("footer click -> switcher aria updated", switcher.getAttribute("aria-label"), /Sketch/);

    // Click the FLOATING switcher -> everything switches back to default.
    switcher.click();
    assertEqual("switcher click -> attr removed", root.getAttribute("data-theme"), null);
    assertEqual("switcher click -> persisted default", storage._dump()["osk-theme"], "default");
    assertEqual("switcher click -> footer label reset", toggle.textContent, "Theme: Default");
    assertEqual("switcher click -> footer aria-pressed false", toggle.getAttribute("aria-pressed"), "false");
  }

  // -------------------------------------------------------------------------
  // Summary + exit code.
  // -------------------------------------------------------------------------
  console.log("\n----------------------------------------");
  console.log("theme-switcher simulation: " + passed + " passed, " + failed + " failed");
  if (failed > 0) {
    process.exitCode = 1;
  }
})();
