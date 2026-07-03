// tour.spec.ts — real in-browser walkthrough of the OSK-105 onboarding product tour.
//
// WHAT this proves in a live headless Chromium (served by static-server.mjs, exactly
// like the other specs):
//   - the tour does NOT auto-pop for an automated browser (the navigator.webdriver
//     guard) — so it never fights the smoke/theme/auth-guard specs,
//   - the always-present "?" launcher is there and opens a COACHMARK: a role=dialog
//     bubble over a spotlight/backdrop, with a "1 of N" progress label,
//   - Next advances the step, and
//   - Esc PAUSES (coachmark closes, launcher flips to "Resume tour") and the launcher
//     RESUMES at the exact paused step — i.e. progress persists.
//
// The DECISION/persistence logic (first-run-once, cross-page walkthrough, absent-
// target filtering, replay) is asserted exhaustively + headlessly in the Node
// simulation web/e2e/tour.simulation.cjs; this spec is the thin "it really renders and
// is drivable in a browser" layer. Uses "/" (index) because its four coachmark targets
// (.logo, .status-link, .get-app, #theme-toggle) are always present regardless of
// backend/auth state, keeping the spec deterministic.

import { test, expect } from "@playwright/test";

test.describe("OpenSkeleton onboarding product tour (OSK-105)", () => {
  test("launcher opens coachmarks; Next advances; Esc pauses and resumes", async ({
    page,
  }, testInfo) => {
    await page.goto("/");

    // The tour must NOT auto-start for an automated browser (webdriver guard): there is
    // no coachmark on load, so the page is unobstructed for other specs.
    await expect(page.locator(".osk-tour-bubble")).toHaveCount(0);

    // The replay affordance — the "?" launcher — is present and invites the tour.
    const launcher = page.locator("#osk-tour-launcher");
    await expect(launcher).toBeVisible();
    await expect(launcher).toContainText("Take the tour");

    // Launch it: a coachmark (role=dialog bubble) appears over the spotlight/backdrop,
    // showing the first step's progress "1 of N".
    await launcher.click();
    const bubble = page.locator(".osk-tour-bubble[role='dialog']");
    await expect(bubble).toBeVisible();
    await expect(page.locator(".osk-tour-backdrop")).toBeVisible();
    await expect(bubble.locator(".osk-tour-title")).toBeVisible();
    await expect(page.locator(".osk-tour-progress")).toHaveText(/^1 of \d+$/);

    // Next advances the coachmark to step two.
    await bubble.getByRole("button", { name: "Next" }).click();
    await expect(page.locator(".osk-tour-progress")).toHaveText(/^2 of \d+$/);

    // Esc PAUSES: the coachmark closes and the launcher offers to resume.
    await page.keyboard.press("Escape");
    await expect(page.locator(".osk-tour-bubble")).toHaveCount(0);
    await expect(launcher).toContainText("Resume tour");

    // Resume re-opens at the SAME step ("2 of N"), proving progress persisted.
    await launcher.click();
    await expect(page.locator(".osk-tour-bubble[role='dialog']")).toBeVisible();
    await expect(page.locator(".osk-tour-progress")).toHaveText(/^2 of \d+$/);

    await testInfo.attach("tour-coachmark", {
      body: await page.screenshot({ fullPage: true }),
      contentType: "image/png",
    });
  });
});
