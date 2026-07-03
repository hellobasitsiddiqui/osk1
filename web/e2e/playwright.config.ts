// playwright.config.ts — configuration for the OpenSkeleton browser e2e harness (OSK-81).
//
// WHAT: runs the specs under tests/ in headless Chromium against the committed
// static web/ pages, which are served by our tiny zero-dependency static-server.mjs
// (it replicates the Firebase Hosting rewrites so /status and /ops resolve exactly
// as they do in production — see static-server.mjs and firebase.json).
//
// WHY a single project (Chromium, headless): this is a fast smoke gate, not a
// cross-browser matrix. It runs on push to main + manual dispatch only (never on
// PRs — see .github/workflows/e2e.yml), so it must stay quick and deterministic.
//
// Adding a walkthrough = adding a spec file under tests/ — NO change to this config
// or to static-server.mjs. See README.md ("Add a new walkthrough").

import { defineConfig, devices } from "@playwright/test";

// Single source of truth for the port. Both the static server (via the PORT env we
// pass it) and the baseURL below use this, so there is one number to change.
const PORT = Number(process.env.PORT || 4310);
const BASE_URL = `http://localhost:${PORT}`;

export default defineConfig({
  testDir: "./tests",
  // Fail the whole run if a test is accidentally left with `test.only` committed.
  forbidOnly: !!process.env.CI,
  // A green smoke run should be green first try; one retry in CI absorbs a rare
  // cold-start/network blip without masking a real failure locally (0 retries).
  retries: process.env.CI ? 1 : 0,
  // Deterministic, readable CI output plus the HTML report we upload on failure.
  reporter: process.env.CI
    ? [["list"], ["html", { open: "never" }]]
    : [["list"], ["html", { open: "never" }]],

  use: {
    baseURL: BASE_URL,
    // Artifacts for debugging a red run — uploaded by the workflow on failure.
    screenshot: "only-on-failure",
    video: "retain-on-failure",
    trace: "on-first-retry",
  },

  // Visual-regression snapshot config (OSK-109). Only the nightly `visual` project
  // captures screenshots (tests/visual.spec.ts); the smoke/theme specs don't. A small
  // pixel tolerance absorbs sub-pixel font anti-aliasing between the (Linux) machine
  // that generated the committed baselines and the (Linux) ubuntu-latest nightly runner,
  // without hiding a genuine layout/colour regression.
  expect: {
    toHaveScreenshot: {
      // Allow up to 2% of pixels to differ — enough for AA/hinting jitter, far below
      // any real layout or colour break.
      maxDiffPixelRatio: 0.02,
      // Freeze CSS animations/transitions and hide the text caret so a capture is never
      // taken mid-animation (both are Playwright defaults; set explicitly for intent).
      animations: "disabled",
      caret: "hide",
      // Compare at CSS pixels so a high-DPI capture doesn't double the baseline size.
      scale: "css",
    },
  },

  projects: [
    {
      // The fast PR/main gate: the smoke + theme specs. It explicitly IGNORES the visual
      // spec, so the visual-regression work (OSK-109) never runs here and never slows the
      // PR gate. The PR/push/dispatch job in e2e.yml selects this with --project=chromium.
      name: "chromium",
      testIgnore: /visual\.spec\.ts/,
      use: { ...devices["Desktop Chrome"] },
    },
    {
      // NIGHTLY-ONLY visual-regression project (OSK-109). Runs ONLY tests/visual.spec.ts,
      // and only when the scheduled job in e2e.yml selects it with --project=visual (and
      // sets VISUAL=1, which the spec's guard also requires). A fixed viewport keeps the
      // full-page captures deterministic across machines.
      name: "visual",
      testMatch: /visual\.spec\.ts/,
      use: { ...devices["Desktop Chrome"], viewport: { width: 1280, height: 720 } },
    },
  ],

  // Boot the static server before the specs and tear it down after. Playwright waits
  // for BASE_URL to answer, so the specs never race the server's startup. We serve
  // the real committed web/ files — nothing is mocked or faked.
  webServer: {
    command: "node static-server.mjs",
    url: BASE_URL,
    env: { PORT: String(PORT) },
    reuseExistingServer: !process.env.CI,
    timeout: 30_000,
  },
});
