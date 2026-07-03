// diagnostics.spec.ts — smoke walkthrough of the OSK-169 QA diagnostics page (/diagnostics).
//
// WHAT this proves in a real headless Chromium (i.e. OFF-DEVICE, plain web — the exact
// context CI runs in): the diagnostics screen boots, PARSES, reuses the merged OSK-152 /
// OSK-150 wrappers, and renders every section in its graceful web-mode state:
//   - Platform reads "Web browser" (no Capacitor bridge) — the amber "not native" dot.
//   - Every expected native plugin reads "Not loaded" (none are present in a browser).
//   - GPS shows its non-intrusive idle baseline (no permission prompt fired on load).
//   - Push/FCM shows the clear "not available" state and the copy button is disabled —
//     no plugin call is attempted, nothing throws.
// It also confirms the clean path "/diagnostics" resolves via the static server's
// Firebase-style rewrite, and that window.OSKDiagnostics is published.
//
// WHY it doesn't assert a live GPS fix or a real FCM token: both need a Capacitor Android
// DEVICE (native plugins) and, for FCM, a configured google-services.json (the OSK-165
// human gate) — none of which exists in CI. The native/granted/denied + token-present/
// absent paths are unit-simulated headlessly in web/e2e/diagnostics.simulation.cjs against
// the SAME pure helpers this page uses. When a device lane is available, a follow-up spec
// can drive the on-device values — no harness change (mirrors history.spec.ts).
//
// Runs in the fast `chromium` project alongside smoke.spec.ts; it is NOT a visual snapshot.

import { test, expect } from "@playwright/test";

test.describe("OpenSkeleton QA diagnostics /diagnostics", () => {
  test("boots off-device and renders the graceful web-mode states", async ({ page }, testInfo) => {
    // Clean path "/diagnostics" — served via the static server's Firebase-style rewrite.
    await page.goto("/diagnostics");

    // Page parses + loads: title + brand header render.
    await expect(page).toHaveTitle("OpenSkeleton Diagnostics");
    await expect(page.locator("h1")).toHaveText("OpenSkeleton Diagnostics");

    // Platform: no Capacitor bridge in a browser -> "Web browser", amber (not native) dot.
    await expect(page.locator("#diag-platform-value")).toContainText("Web browser");
    await expect(page.locator("#diag-platform-dot")).toHaveClass(/dot--warn/);

    // Native plugins: none are loaded in a plain browser.
    await expect(page.locator("#diag-plugin-SplashScreen")).toHaveText("Not loaded");
    await expect(page.locator("#diag-plugin-Geolocation")).toHaveText("Not loaded");
    await expect(page.locator("#diag-plugin-PushNotifications")).toHaveText("Not loaded");

    // GPS: non-intrusive idle baseline — no permission prompt was fired just by loading.
    await expect(page.locator("#diag-gps-coords")).toHaveText("—");

    // Push/FCM: the clear "not available" web state; copy is disabled (nothing to copy).
    await expect(page.locator("#diag-fcm-token")).toContainText("not available");
    await expect(page.locator("#diag-fcm-copy")).toBeDisabled();

    // The controller published its public handle for reuse / manual QA.
    const hasHandle = await page.evaluate(
      () => typeof (window as unknown as { OSKDiagnostics?: unknown }).OSKDiagnostics === "object"
    );
    expect(hasHandle).toBe(true);

    await testInfo.attach("diagnostics-web-mode", {
      body: await page.screenshot({ fullPage: true }),
      contentType: "image/png",
    });
  });
});
