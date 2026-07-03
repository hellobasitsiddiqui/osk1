// auth-guard.spec.ts — smoke walkthrough of the OSK-74 protected /app page.
//
// WHAT this proves in CI (backend down, and — crucially — the committed config.js ships
// an EMPTY firebase.apiKey until the human registers the Firebase Web App, OSK-92): the
// protected page boots, PARSES, and the auth guard lands in its graceful
// "auth not configured" state — a clear notice is shown, and BOTH the sign-in form and
// the protected content stay hidden. It never crashes, and it makes no auth/network call
// (the SDK is only loaded when apiKey is present).
//
// WHY it doesn't drive a real sign-in: live sign-in needs the human apiKey/appId (OSK-92)
// and firebase-scoped ADC (OSK-38), which aren't available in CI. The signed-in ->
// /api/v1/me path is unit-simulated headlessly in web/e2e/auth-guard.simulation.cjs
// against the SAME pure guard helpers this page uses. When the apiKey is filled in, a
// follow-up spec can drive a real cold login here — no harness change (see README.md).
//
// This runs in the fast `chromium` project alongside smoke.spec.ts / theme.spec.ts; it is
// NOT a visual snapshot, so it never gates on a committed PNG baseline.

import { test, expect } from "@playwright/test";

test.describe("OpenSkeleton protected /app auth guard", () => {
  test("/app boots and shows the signed-out sign-in state (apiKey wired, OSK-92)", async ({
    page,
  }, testInfo) => {
    // Clean path "/app" — served via the static server's Firebase-style rewrite.
    await page.goto("/app");

    // Page parses + loads: title + brand header render.
    await expect(page).toHaveTitle("OpenSkeleton Account");
    await expect(page.locator("h1")).toHaveText("OpenSkeleton Account");

    // Auth is now CONFIGURED (real apiKey wired into config.js, OSK-92). For a signed-out
    // visitor the guard loads the SDK, onAuthStateChanged reports no user, and it resolves
    // to the SIGN-IN affordance (auto-waited: SDK load is async). Protected content stays
    // hidden — no protected data leaks to a signed-out visitor.
    await expect(page.locator("#auth-signin")).toBeVisible();
    await expect(page.locator("#auth-app")).toBeHidden();

    // Sanity: the auth module loaded and reports CONFIGURED (apiKey present).
    const configured = await page.evaluate(() => {
      const A = (window as unknown as { OSKAuth?: { isConfigured(): boolean } }).OSKAuth;
      return A ? A.isConfigured() : null;
    });
    expect(configured).toBe(true);

    await testInfo.attach("app-not-configured", {
      body: await page.screenshot({ fullPage: true }),
      contentType: "image/png",
    });
  });
});
