// smoke.spec.ts — real smoke walkthrough of the OpenSkeleton static pages (OSK-81).
//
// WHAT this proves: the three static pages that EXIST TODAY (index, status, ops)
// each boot, render their key elements, and — crucially — degrade GRACEFULLY when
// the backend is down. The harness serves the committed web/ files via
// static-server.mjs (which mirrors the Firebase Hosting rewrites), so the specs
// walk the exact CLEAN paths a real user hits: "/", "/status", "/ops".
//
// The backend is deliberately NOT required to be up. In CI nothing listens on the
// committed config.js apiBaseUrl (127.0.0.1:8080), so every live probe fails fast
// and each page must resolve to its graceful "unavailable"/"outage" state rather
// than hang on its "Checking…" placeholder. These assertions prove that.
//
// NOTE — the admin walkthrough (login -> users console -> disable user -> audit)
// from the OSK-81 ticket is intentionally NOT here: the admin console + login UI
// (OSK-72 / OSK-74) do not exist yet. When they land, that walkthrough is a NEW
// spec file in this directory — no harness change. See README.md.

import { test, expect } from "@playwright/test";

// The three resolved status words any health indicator settles on once its probe
// completes. Used to assert "no longer Checking…" without pinning to up-vs-down,
// so the suite is green whether or not a backend happens to be running.
const RESOLVED_STATUS = /Operational|Degraded|Unavailable/;

test.describe("OpenSkeleton static pages smoke walkthrough", () => {
  test("index (/) renders brand card, build stamp and status/ops links", async ({
    page,
  }, testInfo) => {
    await page.goto("/");

    // Title + headline brand mark.
    await expect(page).toHaveTitle("OpenSkeleton");
    await expect(page.locator("h1")).toHaveText("OpenSkeleton");
    await expect(page.locator(".logo")).toHaveText("OS");

    // The product tag line ("web · nginx · container").
    await expect(page.locator(".tag")).toContainText("nginx");

    // OSK-100 build/version stamp. The web build id comes straight from config.js's
    // committed buildSha ("dev"). The backend build id starts as the "…" loading
    // placeholder and must resolve — with the backend down it becomes "unavailable",
    // proving the /version fetch degraded gracefully instead of leaving "…".
    await expect(page.locator("#web-build")).toHaveText("dev");
    await expect(page.locator("#backend-build")).not.toHaveText("…");

    // Footer links out to the two sibling pages this suite also walks.
    await expect(page.locator('.status-link a[href="/status"]')).toBeVisible();
    await expect(page.locator('.status-link a[href="/ops"]')).toBeVisible();

    // Capture a screenshot into the report/artifacts (AC3).
    await testInfo.attach("index", {
      body: await page.screenshot({ fullPage: true }),
      contentType: "image/png",
    });
  });

  test("status (/status) renders banner, components and resolves gracefully", async ({
    page,
  }, testInfo) => {
    // Clean path "/status" — served via the static server's Firebase-style rewrite.
    await page.goto("/status");

    await expect(page).toHaveTitle("OpenSkeleton Status");
    await expect(page.locator("h1")).toHaveText("OpenSkeleton Status");

    // The overall status banner (the page's headline health indicator) is present…
    const banner = page.locator("#banner");
    await expect(banner).toBeVisible();
    // …and its text resolves away from the "Checking…" placeholder to a real state.
    // With the backend down this settles on "Service outage" — the graceful state.
    const bannerText = page.locator("#banner-text");
    await expect(bannerText).not.toHaveText("Checking service status…");
    await expect(bannerText).toHaveText(
      /All systems operational|Degraded performance|Service outage/,
    );

    // Components panel: both component rows render and the backend row resolves to a
    // known status word (Unavailable when the backend is down) — never stuck.
    await expect(page.locator("#cmp-web")).toBeVisible();
    await expect(page.locator("#cmp-backend")).toBeVisible();
    await expect(page.locator("#cmp-backend .component__label")).toHaveText(
      RESOLVED_STATUS,
    );

    // Metrics panel (deferred-charts placeholders) still renders.
    await expect(page.getByText("Metrics unavailable — charts coming soon").first()).toBeVisible();

    await testInfo.attach("status", {
      body: await page.screenshot({ fullPage: true }),
      contentType: "image/png",
    });
  });

  test("ops (/ops) renders health indicator, link panels and wires backend links", async ({
    page,
  }, testInfo) => {
    // Clean path "/ops" — served via the static server's Firebase-style rewrite.
    await page.goto("/ops");

    await expect(page).toHaveTitle("OpenSkeleton Operations");
    await expect(page.locator("h1")).toHaveText("OpenSkeleton Operations");

    // Live backend-health indicator is present and resolves away from "Checking…".
    // With the backend down it settles on "Backend unavailable" (graceful, AC3).
    const health = page.locator("#health");
    await expect(health).toBeVisible();
    const healthText = page.locator("#health-text");
    await expect(healthText).not.toHaveText("Checking backend health…");
    await expect(healthText).toHaveText(
      /Backend operational|Backend degraded|Backend unavailable/,
    );

    // The three link-group panels render.
    await expect(page.getByRole("heading", { name: "Backend service" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Observability" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Source & CI" })).toBeVisible();

    // Backend links are wired at runtime from config.js's apiBaseUrl + data-ops-path.
    // Assert the Health link's href actually got populated to end with "/health".
    await expect(page.locator('a[data-ops-path="/health"]')).toHaveAttribute(
      "href",
      /\/health$/,
    );

    await testInfo.attach("ops", {
      body: await page.screenshot({ fullPage: true }),
      contentType: "image/png",
    });
  });
});
