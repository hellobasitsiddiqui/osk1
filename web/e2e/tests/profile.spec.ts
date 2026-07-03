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
  test("/profile.html boots and shows the graceful 'auth not configured' state (empty apiKey)", async ({
    page,
  }, testInfo) => {
    // Served as a real static file on disk (no clean-path rewrite needed) — the same file
    // Firebase Hosting ships. Deliberately linked as /profile.html to avoid editing the
    // shared firebase.json while other agents edit the web pages concurrently.
    await page.goto("/profile.html");

    // Page parses + loads: title + brand header render.
    await expect(page).toHaveTitle("Edit your profile — OpenSkeleton");
    await expect(page.locator("h1")).toHaveText("Edit your profile");

    // The guard resolves to the not-configured NOTICE (committed apiKey is empty). The
    // notice text explains the human gate (OSK-92) — proving it reuses the OSK-74 guard.
    const notice = page.locator("#auth-notice");
    await expect(notice).toBeVisible();
    await expect(page.locator("#auth-notice-text")).toContainText("not configured");
    await expect(page.locator("#auth-notice-text")).toContainText("OSK-92");

    // With auth not configured, BOTH the sign-in affordance and the protected edit form
    // stay hidden — nothing interactive is offered and no profile data is fetched.
    await expect(page.locator("#auth-signin")).toBeHidden();
    await expect(page.locator("#profile-app")).toBeHidden();

    // Sanity: the shared auth module loaded and reports "not configured" (no crash, no SDK).
    const configured = await page.evaluate(() => {
      const A = (window as unknown as { OSKAuth?: { isConfigured(): boolean } }).OSKAuth;
      return A ? A.isConfigured() : null;
    });
    expect(configured).toBe(false);

    await testInfo.attach("profile-not-configured", {
      body: await page.screenshot({ fullPage: true }),
      contentType: "image/png",
    });
  });
});
