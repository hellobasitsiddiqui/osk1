// tour.simulation.cjs — headless Node simulation of the OSK-105 onboarding product tour.
//
// WHY this exists: the tour's coachmark RENDERING needs a real browser, but its
// DECISION + STATE logic is PURE (no DOM, no window) — web/tour.js exports it for
// exactly this reason (mirroring how web/auth.js exports its guard helpers, tested by
// auth-guard.simulation.cjs). This script drives those pure helpers with a FAKE
// localStorage and a FAKE per-page visibility map to assert the behaviours the ticket
// calls out:
//   - first-run auto-start fires exactly ONCE for a new visitor, never nags after,
//   - pause + resume keeps the exact step,
//   - replay restarts the tour from the beginning,
//   - skip pauses without re-triggering first-run,
//   - cross-page progress: a walkthrough hands off to the next page and resumes there,
//   - empty / absent / hidden targets are handled gracefully (filtered out, never a
//     coachmark pointing at nothing; an all-absent page ends the tour cleanly).
//
// Plain assertion harness (no framework, no deps): prints each check, exits non-zero
// on the first failure. A `.cjs` file OUTSIDE web/e2e/tests/, so Playwright never runs
// it as a spec.
//
// Run:  node web/e2e/tour.simulation.cjs

"use strict";

const path = require("node:path");
// Require the REAL module under test. tour.js is a browser script that also exports
// its pure helpers when required from Node (its DOM bootstrap is skipped because
// `window` is undefined here), so this asserts the SAME code the pages run.
const tour = require(path.resolve(__dirname, "..", "tour.js"));

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

// ---------------------------------------------------------------------------
// Test doubles: a fake localStorage + the tiny persistence wrappers the browser
// layer uses, rebuilt here from the same STORAGE keys the module exports. This is
// the exact contract the browser code relies on, so testing it here is meaningful.
// ---------------------------------------------------------------------------
function fakeStorage() {
  const map = new Map();
  return {
    getItem: (k) => (map.has(k) ? map.get(k) : null),
    setItem: (k, v) => map.set(k, String(v)),
    removeItem: (k) => map.delete(k),
    _dump: () => Object.fromEntries(map),
  };
}

const K = tour.STORAGE; // { progress, firstRun }

function loadProgress(store) { return tour.parseProgress(store.getItem(K.progress)); }
function saveProgress(store, p) { store.setItem(K.progress, tour.serializeProgress(p)); }
function clearProgress(store) { store.removeItem(K.progress); }
function firstRunDone(store) { return store.getItem(K.firstRun) === "1"; }
function markFirstRun(store) { store.setItem(K.firstRun, "1"); }

// A page "engine" mirroring what tour.js's boot()/goNext() do, but pure. `visible` is a
// Set of selectors currently on-screen for this page; a step with a null target (or a
// visible target) survives filtering.
function activeStepsFor(page, visible) {
  const isVisible = (sel) => !sel || visible.has(sel);
  return tour.filterVisibleSteps(tour.TOURS[page] || [], isVisible);
}
function decideBoot(store, page, visible) {
  const steps = activeStepsFor(page, visible);
  return {
    steps,
    decision: tour.decideOnLoad({
      progress: loadProgress(store),
      firstRunDone: firstRunDone(store),
      pageKey: page,
      hasSteps: steps.length > 0,
      config: { enabled: true, autoStartFirstRun: true },
    }),
  };
}
// Everything a real page can show as selectors (so "all visible" is easy to express).
function allTargets(page) {
  const set = new Set();
  (tour.TOURS[page] || []).forEach((s) => { if (s.target) { set.add(s.target); } });
  return set;
}

// ===========================================================================
// 1) FIRST-RUN AUTO-START — fires once, then never again.
// ===========================================================================
console.log("\nfirst-run auto-start (once, then never nags):");
{
  const store = fakeStorage();
  // Brand-new visitor lands on /index with everything visible.
  const boot1 = decideBoot(store, "index", allTargets("index"));
  assertEqual("new visitor auto-starts", boot1.decision.action, "autostart");

  // The browser marks first-run done IMMEDIATELY on auto-start, then starts.
  markFirstRun(store);
  saveProgress(store, tour.startProgress("index", true));

  // Same visitor reloads mid-tour (unpaused) -> resumes, does NOT re-autostart.
  const boot2 = decideBoot(store, "index", allTargets("index"));
  assertEqual("reload mid-tour resumes (not autostart)", boot2.decision.action, "resume");

  // Visitor finishes the tour: progress cleared, first-run flag stays set.
  clearProgress(store);
  const boot3 = decideBoot(store, "index", allTargets("index"));
  assertEqual("after finish, no auto-start", boot3.decision.action, "idle");
  assertEqual("after finish, not flagged resumable", boot3.decision.resumable, false);

  // Even on a DIFFERENT page later, first-run never fires again.
  const boot4 = decideBoot(store, "status", allTargets("status"));
  assertEqual("later page never re-autostarts", boot4.decision.action, "idle");
}

// ===========================================================================
// 2) PAUSE + RESUME — keeps the exact step.
// ===========================================================================
console.log("\npause + resume (position preserved):");
{
  const store = fakeStorage();
  markFirstRun(store);
  // Start on /app and advance to step index 2.
  let p = tour.startProgress("app", true);
  const total = activeStepsFor("app", allTargets("app")).length;
  const c1 = tour.nextCommand(p, total); // step 0 -> 1
  assertEqual("advance 0->1 is a step", c1.type, "step");
  p.step = c1.step;
  const c2 = tour.nextCommand(p, total); // step 1 -> 2
  p.step = c2.step;
  assertEqual("now on step index 2", p.step, 2);

  // User hits Skip/Esc -> pause persists page + step.
  saveProgress(store, tour.pauseProgress(p));

  // Reload the page: the launcher offers RESUME (idle + resumable), not a fresh start.
  const boot = decideBoot(store, "app", allTargets("app"));
  assertEqual("paused reload is idle", boot.decision.action, "idle");
  assertEqual("paused reload is resumable", boot.decision.resumable, true);
  assertEqual("resume step preserved", boot.decision.step, 2);

  // Clicking Resume restores that step, unpaused.
  const resumed = loadProgress(store);
  assertEqual("stored still paused before resume", resumed.paused, true);
  assertEqual("stored step is 2", resumed.step, 2);
}

// ===========================================================================
// 3) REPLAY — relaunch restarts from the beginning.
// ===========================================================================
console.log("\nreplay (relaunch from the start):");
{
  const store = fakeStorage();
  markFirstRun(store);
  // A completed tour: no progress, first-run done.
  clearProgress(store);
  const boot = decideBoot(store, "status", allTargets("status"));
  assertEqual("completed tour is idle on load", boot.decision.action, "idle");

  // Launcher click -> startTour(page, walkthrough=true) at step 0.
  const restarted = tour.startProgress("status", true);
  assertEqual("replay starts at step 0", restarted.step, 0);
  assertEqual("replay is active", restarted.active, true);
  assertEqual("replay not paused", restarted.paused, false);
  assertEqual("replay is a walkthrough", restarted.walkthrough, true);
}

// ===========================================================================
// 4) SKIP — pauses without ever re-triggering first-run.
// ===========================================================================
console.log("\nskip (quiet exit, no re-nag):");
{
  const store = fakeStorage();
  // First-run auto-starts...
  const boot1 = decideBoot(store, "index", allTargets("index"));
  assertEqual("auto-start on first run", boot1.decision.action, "autostart");
  markFirstRun(store);
  let p = tour.startProgress("index", true);
  // ...user immediately Skips on step 0 -> pause persisted.
  saveProgress(store, tour.pauseProgress(p));

  // Any later load never auto-starts again (dismissal is respected).
  const boot2 = decideBoot(store, "index", allTargets("index"));
  assertEqual("after skip, resumable not autostart", boot2.decision.action, "idle");
  assertEqual("after skip, launcher can resume", boot2.decision.resumable, true);

  // And after clearing progress (e.g. Done later), still no auto-start.
  clearProgress(store);
  const boot3 = decideBoot(store, "index", allTargets("index"));
  assertEqual("after skip+clear, still idle", boot3.decision.action, "idle");
}

// ===========================================================================
// 5) CROSS-PAGE PROGRESS — walkthrough hands off + resumes on the next page.
// ===========================================================================
console.log("\ncross-page walkthrough (hand off + resume, all the way to finish):");
{
  const store = fakeStorage();
  markFirstRun(store);

  // Walk the WHOLE site. Start on the first walk page, drive each page to its last
  // step, then follow the "page" command to the next, exactly as goNext() does.
  let page = tour.WALK_ORDER[0]; // "index"
  saveProgress(store, tour.startProgress(page, true));

  const visitOrder = [];
  let guard = 0;
  while (guard++ < 50) {
    const boot = decideBoot(store, page, allTargets(page));
    // On arrival the walkthrough resumes at the persisted step (0 for a fresh hop).
    assert("arrives on " + page + " and resumes", boot.decision.action === "resume");
    visitOrder.push(page);

    // Fast-forward this page to its last step.
    let p = loadProgress(store);
    const total = boot.steps.length;
    p.step = total - 1;

    // Hit Next on the last step -> either hop to next page or finish.
    const cmd = tour.nextCommand(p, total);
    if (cmd.type === "page") {
      // Hand off: persist a fresh start on the next page + "navigate".
      saveProgress(store, tour.startProgress(cmd.page, true));
      page = cmd.page;
    } else {
      assertEqual("final page ends with finish", cmd.type, "finish");
      clearProgress(store);
      break;
    }
  }
  assert("visited every walk page in order", visitOrder.join(",") === tour.WALK_ORDER.join(","));
  // Landing directly on /status WITHOUT an active walkthrough must NOT resume.
  const cold = decideBoot(store, "status", allTargets("status"));
  assertEqual("cold visit to a page (no walkthrough) is idle", cold.decision.action, "idle");
}

// ===========================================================================
// 6) EMPTY / ABSENT / HIDDEN TARGETS — handled gracefully.
// ===========================================================================
console.log("\nabsent/hidden targets (filtered out, never point at nothing):");
{
  // app.html has a "#auth-app" step that is HIDDEN for a signed-out visitor.
  const signedOut = allTargets("app");
  signedOut.delete("#auth-app"); // simulate the signed-out DOM
  const stepsOut = activeStepsFor("app", signedOut);
  const fullCount = tour.TOURS.app.length;
  assertEqual("signed-out drops the hidden #auth-app step", stepsOut.length, fullCount - 1);
  assert("no surviving step targets #auth-app", stepsOut.every((s) => s.target !== "#auth-app"));

  // A page where EVERY target is absent -> zero visible steps -> the tour can't start
  // (startTour returns early; boot reports hasSteps=false so nothing shows).
  const noneVisible = new Set();
  const emptySteps = activeStepsFor("ops", noneVisible);
  assertEqual("all-absent page yields no visible steps", emptySteps.length, 0);
  const bootNone = tour.decideOnLoad({
    progress: null, firstRunDone: false, pageKey: "ops",
    hasSteps: emptySteps.length > 0, config: { enabled: true, autoStartFirstRun: true },
  });
  assertEqual("all-absent page does not auto-start", bootNone.action, "idle");

  // A null-target (centred) step is ALWAYS kept even with nothing visible.
  const withCentre = tour.filterVisibleSteps(
    [{ target: null, title: "hi" }, { target: "#gone", title: "x" }],
    () => false,
  );
  assertEqual("null-target step survives, absent one dropped", withCentre.length, 1);
  assertEqual("the survivor is the centred step", withCentre[0].target, null);
}

// ===========================================================================
// 7) PERSISTENCE ROUND-TRIP + robustness.
// ===========================================================================
console.log("\npersistence (round-trip + malformed input):");
{
  const p = { active: true, paused: true, walkthrough: true, page: "help", step: 3 };
  const back = tour.parseProgress(tour.serializeProgress(p));
  assertEqual("round-trip page", back.page, "help");
  assertEqual("round-trip step", back.step, 3);
  assertEqual("round-trip paused", back.paused, true);
  assertEqual("round-trip walkthrough", back.walkthrough, true);

  assertEqual("null raw -> null", tour.parseProgress(null), null);
  assertEqual("garbage JSON -> null", tour.parseProgress("{not json"), null);
  assertEqual("wrong shape (no page) -> null", tour.parseProgress('{"step":2}'), null);
  assertEqual("progress label is 1-based", tour.progressLabel(1, 5), "2 of 5");
}

// ===========================================================================
// 8) CONFIG — master switch + first-run toggle.
// ===========================================================================
console.log("\nconfig (enabled / autoStartFirstRun switches):");
{
  assertEqual("default enabled", tour.tourConfig(undefined).enabled, true);
  assertEqual("default autoStart", tour.tourConfig(undefined).autoStartFirstRun, true);
  assertEqual("explicit disable", tour.tourConfig({ tour: { enabled: false } }).enabled, false);
  assertEqual(
    "explicit no-autostart",
    tour.tourConfig({ tour: { autoStartFirstRun: false } }).autoStartFirstRun,
    false,
  );
  // Disabled config => idle even for a brand-new visitor.
  const d = tour.decideOnLoad({
    progress: null, firstRunDone: false, pageKey: "index",
    hasSteps: true, config: { enabled: false, autoStartFirstRun: true },
  });
  assertEqual("disabled config never auto-starts", d.action, "idle");
}

// ---------------------------------------------------------------------------
console.log("\n----------------------------------------");
console.log("tour simulation: " + passed + " passed, " + failed + " failed");
if (failed > 0) { process.exitCode = 1; }
