// history.spec.ts — smoke walkthrough of the OSK-101 protected /history page.
//
// WHAT this proves in CI (backend down, and — crucially — the committed config.js ships an
// EMPTY firebase.apiKey until the human registers the Firebase Web App, OSK-92): the
// profile-history page boots, PARSES, and the reused OSK-74 auth guard lands in its
// graceful "auth not configured" state — a clear notice is shown, and BOTH the sign-in form
// and the protected history region stay hidden. It never crashes, and it makes no
// auth/network call (the Firebase SDK is only loaded when apiKey is present).
//
// WHY it doesn't drive a real sign-in or a live /api/v1/me/history call: both need the human
// apiKey/appId (OSK-92) and firebase-scoped ADC (OSK-38), which aren't available in CI. The
// signed-in -> fetch -> render / empty-state / 401 path is unit-simulated headlessly in
// web/e2e/history.simulation.cjs against the SAME pure helpers this page uses. When the
// apiKey is filled in, a follow-up spec can drive a real signed-in render here — no harness
// change (mirrors auth-guard.spec.ts).
//
// This runs in the fast `chromium` project alongside smoke.spec.ts / auth-guard.spec.ts; it
// is NOT a visual snapshot, so it never gates on a committed PNG baseline.

import { test, expect } from "@playwright/test";

test.describe("OpenSkeleton protected /history view", () => {
  test("/history boots and shows the signed-out sign-in state (apiKey wired, OSK-92)", async ({
    page,
  }, testInfo) => {
    // Clean path "/history" — served via the static server's Firebase-style rewrite.
    await page.goto("/history");

    // Page parses + loads: title + brand header render.
    await expect(page).toHaveTitle("OpenSkeleton Profile History");
    await expect(page.locator("h1")).toHaveText("Profile history");

    // Auth is now CONFIGURED (real apiKey wired into config.js, OSK-92). For a signed-out
    // visitor the guard resolves to the SIGN-IN affordance (auto-waited: SDK load is async),
    // while the protected history region stays hidden — no protected data leaks.
    await expect(page.locator("#auth-signin")).toBeVisible();
    await expect(page.locator("#auth-history")).toBeHidden();

    // Sanity: the auth module loaded and reports CONFIGURED (apiKey present).
    const configured = await page.evaluate(() => {
      const A = (window as unknown as { OSKAuth?: { isConfigured(): boolean } }).OSKAuth;
      return A ? A.isConfigured() : null;
    });
    expect(configured).toBe(true);

    await testInfo.attach("history-not-configured", {
      body: await page.screenshot({ fullPage: true }),
      contentType: "image/png",
    });
  });
});
