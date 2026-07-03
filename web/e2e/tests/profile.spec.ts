// profile.spec.ts — smoke walkthrough of the OSK-86 self-service edit-profile page.
//
// WHAT this proves in CI (backend down, and — crucially — the committed config.js ships an
// EMPTY firebase.apiKey until the human registers the Firebase Web App, OSK-92): the
// edit-profile page boots, PARSES, and the SHARED auth guard (reused from web/auth.js) lands
// in its graceful "auth not configured" state — a clear notice is shown, and BOTH the
// sign-in form and the protected edit form stay hidden. It never crashes, and it makes no
// auth/network call (the Firebase SDK is only loaded when apiKey is present).
//
// WHY it doesn't drive a real load/edit/save: that path needs a signed-in user, which needs
// the human apiKey/appId (OSK-92) and firebase-scoped ADC (OSK-38) — unavailable in CI. The
// signed-in load / dirty-tracking / patch-only-changed / 400-render / 401 behaviour is
// unit-simulated headlessly in web/e2e/profile.simulation.cjs against the SAME pure helpers
// and DOM-injectable controller this page runs. When the apiKey is filled in, a follow-up
// spec can drive a real cold login + edit here — no harness change (see README.md).
//
// This runs in the fast `chromium` project alongside smoke/theme/auth-guard specs; it is NOT
// a visual snapshot, so it never gates on a committed PNG baseline.

import { test, expect } from "@playwright/test";

test.describe("OpenSkeleton self-service edit-profile page", () => {
  test("/profile.html boots and shows the signed-out sign-in state (apiKey wired, OSK-92)", async ({
    page,
  }, testInfo) => {
    // Served as a real static file on disk (no clean-path rewrite needed) — the same file
    // Firebase Hosting ships. Deliberately linked as /profile.html to avoid editing the
    // shared firebase.json while other agents edit the web pages concurrently.
    await page.goto("/profile.html");

    // Page parses + loads: title + brand header render.
    await expect(page).toHaveTitle("Edit your profile — OpenSkeleton");
    await expect(page.locator("h1")).toHaveText("Edit your profile");

    // Auth is now CONFIGURED (real apiKey wired into config.js, OSK-92). For a signed-out
    // visitor the shared OSK-74 guard resolves to the SIGN-IN affordance (auto-waited: SDK
    // load is async), while the protected edit form stays hidden — no profile data fetched.
    await expect(page.locator("#auth-signin")).toBeVisible();
    await expect(page.locator("#profile-app")).toBeHidden();

    // Sanity: the shared auth module loaded and reports CONFIGURED (apiKey present).
    const configured = await page.evaluate(() => {
      const A = (window as unknown as { OSKAuth?: { isConfigured(): boolean } }).OSKAuth;
      return A ? A.isConfigured() : null;
    });
    expect(configured).toBe(true);

    await testInfo.attach("profile-signed-out", {
      body: await page.screenshot({ fullPage: true }),
      contentType: "image/png",
    });
  });
});
