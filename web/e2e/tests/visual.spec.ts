// visual.spec.ts — visual-regression snapshots of the OpenSkeleton static pages (OSK-109).
//
// WHAT this proves: the four key pages (index "/", status "/status", ops "/ops",
// help "/help") render pixel-for-pixel the same as a committed baseline PNG, in BOTH
// themes — the DEFAULT (dark) theme and the OSK-127 SKETCH (graph-paper) theme. A diff
// beyond a tiny anti-aliasing tolerance fails the run, catching unintended layout /
// colour / spacing regressions that the DOM-assertion specs (smoke, theme) can't see.
//
// WHY IT IS NIGHTLY-ONLY: pixel snapshots are brittle (font hinting, sub-pixel AA) and
// slower than assertion specs, so they must NOT gate every PR/push. Two independent
// guards keep them off the fast path:
//   1. A dedicated Playwright `project` named "visual" (see playwright.config.ts). The
//      PR/main job runs `--project=chromium` (smoke + theme only); the nightly
//      `schedule:` job runs `--project=visual`. See .github/workflows/e2e.yml.
//   2. The VISUAL env guard below. Only the nightly job sets VISUAL=1, so a bare
//      `npx playwright test` (local or otherwise) SKIPS this whole suite — the existing
//      smoke + theme specs stay fast and are not slowed by adding visual regression.
//
// DETERMINISM (AC3): the harness serves the committed web/ files with the backend down
// (nothing listens on config.js's 127.0.0.1:8080), so every page resolves to a FIXED
// state before we capture:
//   - the live health "as of" timestamps (status #banner-time, ops #health-time) change
//     every second, so they are NEUTRALISED with CSS (visibility:hidden) — the AC's
//     "or CSS to neutralize them" option, chosen over mask[] because a masked box tracks
//     the element's variable text width and would itself jitter;
//   - the build stamp (index #web-build / #backend-build) is likewise CSS-hidden so a
//     baseline never bakes in a deploy-injected git SHA or the transient "…" placeholder;
//   - the health DOT / banner state is PINNED to its resolved "unavailable" state by
//     WAITING for each page's probe to settle (backend down => web ok / backend down),
//     so we never capture the "Checking…" placeholder mid-flight.
// Baselines are generated on Linux (matching the ubuntu-latest nightly runner) so the
// committed PNGs line up with CI rendering; see web/e2e/README.md ("Updating baselines").

import { test, expect, type Page } from "@playwright/test";

// localStorage key + value that selects the sketch theme pre-paint (OSK-127). Seeding
// this before navigation makes the <head> pre-paint script apply sketch before first
// paint — no toggle click, no transition — giving a clean, stable sketch capture.
const THEME_KEY = "osk-theme";
const SKETCH_VALUE = "sketch";

// The resolved status words any health indicator settles on once its probe completes —
// used to wait past the "Checking…" placeholder without pinning to up-vs-down.
const RESOLVED_STATUS = /Operational|Degraded|Unavailable/;

// One capturable page: where it lives, its title (a cheap load sanity check), how to
// wait for its dynamic health/build to settle, and the CSS that neutralises its volatile
// text so the snapshot is deterministic.
type PageSpec = {
  name: string;
  path: string;
  title: string;
  // Resolve only once the page's live probes have painted their FINAL state, so we never
  // snapshot the "Checking…"/"…" placeholders. No-op for fully static pages.
  settle: (page: Page) => Promise<void>;
  // CSS injected just before capture to hide volatile text (timestamps / build stamp).
  // Empty for pages with nothing dynamic to neutralise.
  neutralizeCss: string;
};

const PAGES: PageSpec[] = [
  {
    name: "index",
    path: "/",
    title: "OpenSkeleton",
    // The backend build id starts as "…" and resolves (backend down => "unavailable").
    settle: async (page) => {
      await expect(page.locator("#backend-build")).not.toHaveText("…");
    },
    // Hide BOTH build ids: "dev" is a committed placeholder and the backend id is a
    // deploy-injected SHA in prod — neither should be baked into a pixel baseline.
    neutralizeCss: "#web-build, #backend-build { visibility: hidden !important; }",
  },
  {
    name: "status",
    path: "/status",
    title: "OpenSkeleton Status",
    // Wait for the overall banner AND the backend component row to leave "Checking…"
    // and settle on their resolved words (painted together in one poll cycle, so the
    // dots are settled too: web ok/green, backend unavailable/red).
    settle: async (page) => {
      await expect(page.locator("#banner-text")).not.toHaveText("Checking service status…");
      await expect(page.locator("#cmp-backend .component__label")).toHaveText(RESOLVED_STATUS);
    },
    // The banner's live "as of HH:MM:SS" timestamp changes every second — hide it.
    neutralizeCss: "#banner-time { visibility: hidden !important; }",
  },
  {
    name: "ops",
    path: "/ops",
    title: "OpenSkeleton Operations",
    // Wait for the backend-health indicator to leave "Checking…" (backend down =>
    // "Backend unavailable"), so the dot/accent colour is its settled red.
    settle: async (page) => {
      await expect(page.locator("#health-text")).not.toHaveText("Checking backend health…");
    },
    // The health indicator's live "as of HH:MM:SS" timestamp — hide it.
    neutralizeCss: "#health-time { visibility: hidden !important; }",
  },
  {
    name: "help",
    path: "/help",
    title: "OpenSkeleton Help",
    // Fully static content page — no probes, nothing to wait for.
    settle: async () => {
      /* nothing dynamic */
    },
    neutralizeCss: "",
  },
];

// The two themes every page is captured in. `seed:true` pre-seeds localStorage so the
// page loads straight into sketch pre-paint; `seed:false` is the default (dark) theme.
const THEMES: Array<{ name: string; seed: boolean }> = [
  { name: "default", seed: false },
  { name: "sketch", seed: true },
];

test.describe("OpenSkeleton visual regression (both themes)", () => {
  // NIGHTLY-ONLY guard: skip the entire suite unless VISUAL is set (only the scheduled
  // job in e2e.yml sets it). This is why a bare `npx playwright test` — locally or on a
  // PR — never runs these brittle pixel compares and the smoke/theme specs stay fast.
  test.beforeEach(() => {
    test.skip(
      !process.env.VISUAL,
      "visual regression is nightly-only — set VISUAL=1 and run --project=visual to capture/compare",
    );
  });

  for (const theme of THEMES) {
    for (const p of PAGES) {
      test(`${p.name} (${p.path}) — ${theme.name} theme`, async ({ page }) => {
        // Seed sketch BEFORE any page script runs, so the <head> pre-paint script applies
        // it before first paint (mirrors theme.spec.ts's cold-load path). Default theme
        // needs no seed — each test gets a fresh, isolated context (clean localStorage).
        if (theme.seed) {
          await page.addInitScript(
            ({ key, value }) => {
              try {
                window.localStorage.setItem(key, value);
              } catch {
                /* storage blocked — page falls back to default; the assert below flags it */
              }
            },
            { key: THEME_KEY, value: SKETCH_VALUE },
          );
        }

        await page.goto(p.path);
        await expect(page).toHaveTitle(p.title);

        // Confirm the intended theme is actually in effect before we capture, so a
        // mislabelled baseline can never be committed.
        if (theme.seed) {
          await expect(page.locator("html")).toHaveAttribute("data-theme", SKETCH_VALUE);
        } else {
          expect(await page.locator("html").getAttribute("data-theme")).toBeNull();
        }

        // Pin the live health/build content to its settled "unavailable" state.
        await p.settle(page);

        // Neutralise volatile text (build stamp + "as of" timestamp) via CSS so the
        // snapshot is stable run-to-run. Injected after settle so it wins the cascade.
        if (p.neutralizeCss) {
          await page.addStyleTag({ content: p.neutralizeCss });
        }

        // Capture / compare the full page. Baseline PNGs live in
        // tests/visual.spec.ts-snapshots/ and are committed. Tolerance + animation
        // freezing come from playwright.config.ts's expect.toHaveScreenshot config.
        await expect(page).toHaveScreenshot(`${p.name}-${theme.name}.png`, { fullPage: true });
      });
    }
  }
});
