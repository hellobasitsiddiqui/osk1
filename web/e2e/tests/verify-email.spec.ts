// verify-email.spec.ts — real-browser static check of the OSK-75 email-verification banner.
//
// WHAT this proves in CI (backend down, and — crucially — the committed config.js ships an
// EMPTY firebase.apiKey until the human registers the Firebase Web App, OSK-92): the
// protected /app page carries the shared verify-email banner block, the module
// (verify-email.js) loads and runs WITHOUT throwing, and — because there is no signed-in
// user (auth is not configured) — the banner stays HIDDEN. A verified/unverified user is
// never fabricated here: that signed-in behaviour is unit-simulated headlessly in
// web/e2e/verify-email.simulation.cjs (fake DOM + stubbed fetch + fake Firebase user)
// against the SAME pure helpers + controller factory this page runs. When the apiKey is
// filled in (OSK-92), a follow-up spec can drive a real signed-in cold login here and
// assert the banner shows + Resend calls the endpoint — no harness change.
//
// It also confirms the banner COEXISTS with the OSK-151 alert banner: both mounts are
// present and neither is shown in the default (no-alert, signed-out) state.
//
// Runs in the fast `chromium` project alongside smoke/auth-guard/theme; not a visual
// snapshot, so it never gates on a committed PNG baseline.

import { test, expect } from "@playwright/test";

test.describe("OpenSkeleton email-verification banner (OSK-75)", () => {
  test("/app carries the verify banner block; it stays hidden when signed out / not configured", async ({
    page,
  }, testInfo) => {
    // Fail the test on any uncaught page exception (the module must never crash the page).
    const pageErrors: string[] = [];
    page.on("pageerror", (err) => pageErrors.push(String(err)));

    // Clean path "/app" — served via the static server's Firebase-style rewrite.
    await page.goto("/app");
    await expect(page).toHaveTitle("OpenSkeleton Account");

    // The shared verify-email banner block is present in the DOM…
    const banner = page.locator("#verify-email-banner");
    await expect(banner).toHaveCount(1);
    // …and its Resend + refresh actions exist inside it.
    await expect(page.locator("#verify-email-resend")).toHaveCount(1);
    await expect(page.locator("#verify-email-refresh")).toHaveCount(1);

    // With auth NOT configured (empty apiKey) there is no signed-in user, so the banner
    // must stay hidden — a verified/absent user is never nagged.
    await expect(banner).toBeHidden();

    // The module loaded itself + its stylesheet (idempotent <link> injection), proving
    // the shared block is self-contained (no per-page <head> edit needed).
    const styleLinked = await page.evaluate(
      () => !!document.querySelector('link[data-osk="verify-email"]'),
    );
    expect(styleLinked).toBe(true);

    // The OSK-151 alert banner mount coexists and is also hidden (no alert configured):
    // the two banners never fight for the same space in this state.
    await expect(page.locator("#site-alert")).toBeHidden();

    // No uncaught exceptions from the verify-email module (or anything else) on load.
    expect(pageErrors).toEqual([]);

    await testInfo.attach("app-verify-banner-hidden", {
      body: await page.screenshot({ fullPage: true }),
      contentType: "image/png",
    });
  });
});
