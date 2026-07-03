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

  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
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
