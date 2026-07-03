// theme.spec.ts — theme-switch walkthrough of the OpenSkeleton static pages (OSK-112).
//
// WHAT this proves: on each key page (index "/", status "/status", ops "/ops") the
// OSK-127 theme toggle works end-to-end — the page loads in the DEFAULT (dark) theme,
// switches to the SKETCH (graph-paper) theme, the sketch theme is *actually applied*
// (not just an attribute flip — the graph-paper background, pale-paper fill, monospace
// body and sketch palette are all in effect), the choice PERSISTS across a reload (the
// <head> pre-paint script re-applies it from localStorage), and it toggles back to
// default cleanly. Throughout, the page's key elements stay visible in BOTH themes —
// so a theme switch never breaks the layout.
//
// HOW it toggles: the primary path clicks the page's own toggle button (#theme-toggle,
// the real OSK-127 control) in both directions. A separate test also seeds
// localStorage['osk-theme']='sketch' from a cold start and reloads, proving the
// alternative (persistence-driven, pre-paint) mechanism the ticket calls out.
//
// The harness serves the committed web/ files via static-server.mjs (Firebase-style
// rewrites), so the specs walk the exact clean paths a real user hits. The backend is
// NOT required to be up — the theme is applied purely client-side (pre-paint script +
// button), independent of any /version or /health probe, so nothing here is flaky on a
// down backend.
//
// Adding this walkthrough is a NEW spec file only — no change to playwright.config.ts
// or static-server.mjs (see README.md, "Add a new walkthrough").

import { test, expect, type Page } from "@playwright/test";

// localStorage key + the persisted value that selects the sketch theme (OSK-127).
const THEME_KEY = "osk-theme";
const SKETCH_VALUE = "sketch";

// The default and sketch palettes' background colour, read from the `--bg` custom
// property. These are the exact hex values declared in each page's :root / sketch
// block — a clean, page-agnostic assertion that the whole palette actually swapped.
const DEFAULT_BG_HEX = "#0d1117"; // dark theme
const SKETCH_BG_HEX = "#eef1e6"; // pale graph paper
// The sketch `--bg` resolved to an rgb() string, as getComputedStyle reports the
// body's *computed* background-color (sketch sets `background-color: var(--bg)`).
const SKETCH_BG_RGB = "rgb(238, 241, 230)";

// The three key pages and, per page, a handful of KEY ELEMENTS that must render in
// BOTH themes. Kept small and stable (headline + the page's signature widgets) — the
// point is "no layout break on theme switch", not to re-run the full smoke suite.
const PAGES: Array<{
  name: string;
  path: string;
  title: string;
  keyElements: Array<{ desc: string; find: (page: Page) => ReturnType<Page["locator"]> }>;
}> = [
  {
    name: "index",
    path: "/",
    title: "OpenSkeleton",
    keyElements: [
      { desc: "headline", find: (p) => p.locator("h1") },
      { desc: "brand logo", find: (p) => p.locator(".logo") },
      { desc: "product tag", find: (p) => p.locator(".tag") },
      { desc: "status link", find: (p) => p.locator('.status-link a[href="/status"]') },
    ],
  },
  {
    name: "status",
    path: "/status",
    title: "OpenSkeleton Status",
    keyElements: [
      { desc: "headline", find: (p) => p.locator("h1") },
      { desc: "status banner", find: (p) => p.locator("#banner") },
      { desc: "web component row", find: (p) => p.locator("#cmp-web") },
      { desc: "backend component row", find: (p) => p.locator("#cmp-backend") },
    ],
  },
  {
    name: "ops",
    path: "/ops",
    title: "OpenSkeleton Operations",
    keyElements: [
      { desc: "headline", find: (p) => p.locator("h1") },
      { desc: "health indicator", find: (p) => p.locator("#health") },
      {
        desc: "backend service panel",
        find: (p) => p.getByRole("heading", { name: "Backend service" }),
      },
    ],
  },
];

// Read a computed style value off an element (body or the root <html>) in the page.
function computed(page: Page, selector: "body" | "html", prop: string): Promise<string> {
  return page.evaluate(
    ({ sel, p }) => getComputedStyle(document.querySelector(sel) as Element).getPropertyValue(p),
    { sel: selector, p: prop },
  );
}

// Read a CSS custom property off the root element (where both palettes declare `--bg`).
function cssVar(page: Page, name: string): Promise<string> {
  return page.evaluate(
    (n) => getComputedStyle(document.documentElement).getPropertyValue(n).trim(),
    name,
  );
}

// Assert the page is in the DEFAULT (dark) theme: no data-theme attribute, the dark
// palette, the default radial-gradient background (NOT the sketch graph paper), and
// the toggle button reads "Theme: Default" / aria-pressed=false.
async function assertDefaultTheme(page: Page): Promise<void> {
  // The pre-paint / toggle logic removes the attribute entirely for default.
  expect(await page.locator("html").getAttribute("data-theme")).toBeNull();

  // Palette actually swapped back to the dark `--bg`.
  expect(await cssVar(page, "--bg")).toBe(DEFAULT_BG_HEX);

  // Default body background is the dark radial-gradient — and crucially NOT the
  // sketch graph-paper (repeating-linear-gradient), so the sketch cue is gone.
  const bgImage = await computed(page, "body", "background-image");
  expect(bgImage).toContain("radial-gradient");
  expect(bgImage).not.toContain("repeating-linear-gradient");

  // Toggle control reflects the active theme.
  const btn = page.locator("#theme-toggle");
  await expect(btn).toHaveText("Theme: Default");
  await expect(btn).toHaveAttribute("aria-pressed", "false");
}

// Assert the page is in the SKETCH (graph-paper) theme and it is genuinely APPLIED:
// the attribute is set, the sketch palette is in effect, and the sketch-only visual
// cues (graph-paper background, pale-paper fill, monospace body) are all present.
async function assertSketchTheme(page: Page): Promise<void> {
  await expect(page.locator("html")).toHaveAttribute("data-theme", SKETCH_VALUE);

  // Palette swapped to the pale graph-paper `--bg`.
  expect(await cssVar(page, "--bg")).toBe(SKETCH_BG_HEX);

  // THE sketch-only visual cue: the graph-paper grid, drawn as repeating-linear-
  // gradients on the body. If this is present the sketch CSS is truly in effect,
  // not just an attribute set with no styling applied.
  const bgImage = await computed(page, "body", "background-image");
  expect(bgImage).toContain("repeating-linear-gradient");

  // Further sketch cues: pale paper fill behind the grid + monospace "drawing" body.
  expect(await computed(page, "body", "background-color")).toBe(SKETCH_BG_RGB);
  expect(await computed(page, "body", "font-family")).toContain("monospace");

  // Toggle control reflects the active theme.
  const btn = page.locator("#theme-toggle");
  await expect(btn).toHaveText("Theme: Sketch");
  await expect(btn).toHaveAttribute("aria-pressed", "true");
}

// Assert every declared key element for a page is visible — used in BOTH themes to
// prove a theme switch never drops or hides a key element (no layout break).
async function assertKeyElementsVisible(
  page: Page,
  spec: (typeof PAGES)[number],
): Promise<void> {
  for (const el of spec.keyElements) {
    await expect(el.find(page), `${spec.name}: "${el.desc}" should be visible`).toBeVisible();
  }
}

test.describe("OpenSkeleton theme switch (default <-> sketch)", () => {
  for (const p of PAGES) {
    test(`${p.name} (${p.path}) toggles default <-> sketch, persists, no layout break`, async ({
      page,
    }, testInfo) => {
      await page.goto(p.path);
      await expect(page).toHaveTitle(p.title);

      // 1) Loads in the DEFAULT theme — the default-theme marker renders, and every
      //    key element is visible.
      await assertDefaultTheme(page);
      await assertKeyElementsVisible(page, p);
      await testInfo.attach(`${p.name}-default`, {
        body: await page.screenshot({ fullPage: true }),
        contentType: "image/png",
      });

      // 2) Toggle to SKETCH via the page's own toggle button (the real OSK-127
      //    control). Assert the sketch theme is genuinely applied (attribute +
      //    graph-paper cue + palette), and the key elements STILL render — no break.
      await page.locator("#theme-toggle").click();
      await assertSketchTheme(page);
      await assertKeyElementsVisible(page, p);
      await testInfo.attach(`${p.name}-sketch`, {
        body: await page.screenshot({ fullPage: true }),
        contentType: "image/png",
      });

      // 3) PERSISTENCE: the toggle wrote 'sketch' to localStorage; reload and confirm
      //    the <head> pre-paint script re-applies it, so sketch survives a full load.
      await page.reload();
      await assertSketchTheme(page);

      // 4) Toggle BACK to default and confirm the page fully returns to the default
      //    theme (attribute cleared, dark palette + radial background restored) with
      //    all key elements still visible.
      await page.locator("#theme-toggle").click();
      await assertDefaultTheme(page);
      await assertKeyElementsVisible(page, p);
    });
  }

  // The ticket's alternative toggle mechanism, exercised from a COLD start: seed
  // localStorage['osk-theme']='sketch' BEFORE the page loads, then navigate. The
  // <head> pre-paint script must apply sketch before first paint (no click involved),
  // proving the persistence contract independently of the button. index is enough —
  // the pre-paint script is identical across all three pages.
  test("cold load with localStorage['osk-theme']='sketch' applies sketch pre-paint", async ({
    page,
  }) => {
    // addInitScript runs before any page script on the next navigation, so the seed
    // is in place when the <head> pre-paint script reads it.
    await page.addInitScript(
      ({ key, value }) => {
        try {
          window.localStorage.setItem(key, value);
        } catch {
          /* storage blocked — the page would fall back to default; test would flag it */
        }
      },
      { key: THEME_KEY, value: SKETCH_VALUE },
    );

    await page.goto("/");
    await expect(page).toHaveTitle("OpenSkeleton");
    // Sketch is applied straight from persisted state — no toggle click needed.
    await assertSketchTheme(page);
  });
});
