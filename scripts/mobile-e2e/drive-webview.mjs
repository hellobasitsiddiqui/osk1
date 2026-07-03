#!/usr/bin/env node
// drive-webview.mjs — OSK-160 mobile emulator e2e driver.
//
// WHAT THIS IS
// ------------
// A tiny, ZERO-DEPENDENCY driver that proves the OpenSkeleton native Android
// shell (the Capacitor WebView from OSK-146) actually boots and renders the web
// UI on a real Android emulator in CI. It attaches to the running app's WebView
// over the Chrome DevTools Protocol (CDP), drives a minimal signed-OUT flow, and
// harvests one device screenshot per milestone as evidence:
//
//   milestone 2  ->  02-webview-home.png    (WebView loaded index.html brand card)
//   milestone 3  ->  03-signin-signed-out.png (the signed-out sign-in screen)
//
// (Milestone 1, "01-boot-splash.png", is captured by the workflow with `adb`
//  immediately after launch — before any WebView debug target exists — so it
//  lives in android-e2e.yml, not here.)
//
// WHY CDP / WHY THIS WORKS WITHOUT A REAL LOGIN
// ---------------------------------------------
// The app is built with `assembleDebug`, so it is `android:debuggable`. Capacitor
// 8 enables WebView remote debugging for debuggable builds by default
// (CapConfig.java: `webContentsDebuggingEnabled` defaults to `isDebug`; Bridge.java
// calls `WebView.setWebContentsDebuggingEnabled(...)`). That exposes a DevTools
// endpoint on an abstract unix socket (`webview_devtools_remote_<pid>`) which the
// workflow forwards to a TCP port. We speak CDP to it here to (a) read the live
// DOM as proof (not just a screenshot of "an app"), and (b) navigate the WebView
// to the sign-in page — the shell cold-boots to index.html and there is no native
// address bar, so DOM-level navigation is the only way in.
//
// The signed-out sign-in screen (`#auth-signin` in web/app.html) renders WITHOUT a
// real login: auth.js loads the Firebase SDK, `onAuthStateChanged` fires with
// user=null, and the guard reveals the sign-in form. The Email/Password console
// toggle only matters if you actually SUBMIT the form (which we never do), so this
// flow is valid even before that toggle is switched on.
//
// ZERO DEPENDENCIES: this uses only Node built-ins available on the CI Node 22
// runtime — global `fetch` (Node 18+) for DevTools target discovery and the global
// `WebSocket` (stable in Node 22) for the CDP session — so there is no package.json,
// no lockfile, and nothing for the gitleaks / dependency gates to chew on.
//
// CONTRACT: exit 0 == the whole flow rendered and every assertion passed; any
// non-zero exit (assertion failure, timeout, missing target) FAILS the CI job.
// On failure we grab a diagnostic screenshot (99-failure.png) + a page-text dump
// so a red run is triageable straight from the uploaded artifacts.

import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

// ---- Configuration (all overridable via env from the workflow) --------------
const CDP_PORT = process.env.CDP_PORT || '9222';
// Where the harvested PNGs are written. The workflow uploads this directory.
const SCREENSHOT_DIR = process.env.SCREENSHOT_DIR || 'mobile-e2e-artifacts/screenshots';
// The WebView's local origin. Capacitor serves the bundled web/ assets from
// https://localhost when `androidScheme: 'https'` (see capacitor.config.ts).
const APP_ORIGIN = process.env.APP_ORIGIN || 'https://localhost';
// The signed-out sign-in page. We target the real FILE (`/app.html`), NOT the
// clean route `/app`: the /app -> /app.html rewrite lives in firebase.json and is
// applied by Firebase Hosting, but Capacitor's local asset server does not read
// those rewrites, so inside the WebView only the concrete filename resolves.
const SIGNIN_URL = process.env.SIGNIN_URL || `${APP_ORIGIN}/app.html`;

// Timeouts (ms). Generous, because a fresh emulator + a cold WebView + the Firebase
// SDK dynamic-import from gstatic all add latency on the first run.
const TARGET_DISCOVERY_TIMEOUT = 60_000; // wait for the WebView DevTools target
const HOME_RENDER_TIMEOUT = 60_000;      // wait for index.html to paint
const SIGNIN_RENDER_TIMEOUT = 90_000;    // wait for auth.js + Firebase SDK + #auth-signin
const POLL_INTERVAL = 1_000;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---------------------------------------------------------------------------
// Device screenshot harvesting.
//
// `adb exec-out screencap -p` streams a raw PNG on stdout. We use `exec-out`
// (not `adb shell ... > file`) so the binary stream is NOT mangled by shell
// CRLF translation. We then sanity-check the PNG magic bytes so a corrupt or
// empty capture fails loudly instead of uploading a broken artifact.
// ---------------------------------------------------------------------------
const PNG_MAGIC = Buffer.from([0x89, 0x50, 0x4e, 0x47]); // \x89 P N G
function screenshot(fileName) {
  const outPath = join(SCREENSHOT_DIR, fileName);
  const buf = execFileSync('adb', ['exec-out', 'screencap', '-p'], {
    maxBuffer: 128 * 1024 * 1024, // a device screen PNG is well under this
  });
  if (buf.length < 8 || !buf.subarray(0, 4).equals(PNG_MAGIC)) {
    throw new Error(
      `screencap for ${fileName} did not return a PNG (got ${buf.length} bytes)`,
    );
  }
  writeFileSync(outPath, buf);
  console.log(`  [screenshot] ${outPath} (${buf.length} bytes)`);
  return outPath;
}

// ---------------------------------------------------------------------------
// Minimal CDP client over the built-in global WebSocket.
//
// CDP framing: a command is {id, method, params}; the reply is {id, result} on
// success or {id, error} on failure; server-initiated events are {method, params}
// with no id (we ignore them — we poll the DOM instead of racing load events).
// ---------------------------------------------------------------------------
class CDP {
  constructor(wsUrl) {
    this.ws = new WebSocket(wsUrl);
    this.nextId = 1;
    this.pending = new Map(); // id -> {resolve, reject}
    this.ws.addEventListener('message', (ev) => {
      let msg;
      try {
        msg = JSON.parse(ev.data);
      } catch {
        return; // ignore anything unparseable
      }
      if (msg.id == null) return; // an event, not a command reply
      const p = this.pending.get(msg.id);
      if (!p) return;
      this.pending.delete(msg.id);
      if (msg.error) p.reject(new Error(`CDP error: ${JSON.stringify(msg.error)}`));
      else p.resolve(msg.result);
    });
  }

  open() {
    return new Promise((resolve, reject) => {
      this.ws.addEventListener('open', () => resolve());
      this.ws.addEventListener('error', (e) =>
        reject(new Error(`WebSocket error connecting to WebView: ${e.message || e}`)),
      );
    });
  }

  send(method, params = {}) {
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.ws.send(JSON.stringify({ id, method, params }));
    });
  }

  // Evaluate a JS expression in the page and return its value-by-value. Throws
  // if the expression itself threw inside the page.
  async evaluate(expression) {
    const res = await this.send('Runtime.evaluate', {
      expression,
      returnByValue: true,
      awaitPromise: true,
    });
    if (res.exceptionDetails) {
      throw new Error(
        `page evaluation threw: ${JSON.stringify(res.exceptionDetails)}`,
      );
    }
    return res.result?.value;
  }

  close() {
    try {
      this.ws.close();
    } catch {
      /* best-effort */
    }
  }
}

// Poll `fn()` until it resolves truthy or we hit `timeout`. `label` is used in the
// timeout error so a hang points straight at the milestone that stalled.
async function waitFor(fn, { timeout, label }) {
  const deadline = Date.now() + timeout;
  let lastErr;
  for (;;) {
    try {
      const v = await fn();
      if (v) return v;
    } catch (e) {
      lastErr = e; // transient (e.g. mid-navigation context swap) — keep polling
    }
    if (Date.now() >= deadline) {
      throw new Error(
        `timed out after ${timeout}ms waiting for: ${label}` +
          (lastErr ? ` (last error: ${lastErr.message})` : ''),
      );
    }
    await sleep(POLL_INTERVAL);
  }
}

// Find the WebView's "page" DevTools target and return its WebSocket URL. The
// endpoint only appears once the WebView has initialised, so we retry.
async function discoverWebViewTarget() {
  const listUrl = `http://127.0.0.1:${CDP_PORT}/json/list`;
  return waitFor(
    async () => {
      const resp = await fetch(listUrl);
      if (!resp.ok) return null;
      const targets = await resp.json();
      // Prefer the app page served from our local origin; fall back to any page.
      const page =
        targets.find(
          (t) => t.type === 'page' && String(t.url).startsWith(APP_ORIGIN),
        ) || targets.find((t) => t.type === 'page');
      return page?.webSocketDebuggerUrl || null;
    },
    { timeout: TARGET_DISCOVERY_TIMEOUT, label: `a WebView DevTools page target on ${listUrl}` },
  );
}

// The single source of truth for "is the signed-out sign-in screen showing?".
// Returns a structured snapshot we both assert on AND log as evidence. Kept as a
// string so it runs inside the page via Runtime.evaluate.
const SIGNIN_PROBE = `(() => {
  const el = document.querySelector('#auth-signin');
  const visible = !!el
    && !el.hasAttribute('hidden')
    && getComputedStyle(el).display !== 'none'
    && getComputedStyle(el).visibility !== 'hidden'
    && el.offsetParent !== null;
  const google = document.querySelector('#google-btn');
  const submit = document.querySelector('#signin-submit');
  return {
    found: !!el,
    visible,
    googleText: google ? google.textContent.trim() : null,
    hasSubmit: !!submit,
    title: document.title,
    url: location.href,
  };
})()`;

async function main() {
  mkdirSync(SCREENSHOT_DIR, { recursive: true });
  if (typeof WebSocket === 'undefined') {
    throw new Error(
      'global WebSocket is unavailable — this driver needs Node >= 22 (CI pins Node 22)',
    );
  }

  console.log(`[1/3] discovering the WebView DevTools target on port ${CDP_PORT}…`);
  const wsUrl = await discoverWebViewTarget();
  console.log(`      attached target: ${wsUrl}`);
  const cdp = new CDP(wsUrl);
  await cdp.open();
  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');

  // --- Milestone 2: the WebView loaded the OpenSkeleton web UI (index.html). ---
  console.log('[2/3] waiting for the WebView to render the OpenSkeleton home UI…');
  await waitFor(
    async () => {
      const ready = await cdp.evaluate('document.readyState');
      const brand = await cdp.evaluate(
        `!!document.querySelector('h1') && document.querySelector('h1').textContent.includes('OpenSkeleton')`,
      );
      return ready === 'complete' && brand;
    },
    { timeout: HOME_RENDER_TIMEOUT, label: "index.html to paint the 'OpenSkeleton' brand card" },
  );
  const homeTitle = await cdp.evaluate('document.title');
  const homeUrl = await cdp.evaluate('location.href');
  if (!String(homeTitle).includes('OpenSkeleton')) {
    throw new Error(`home title assertion failed: got "${homeTitle}"`);
  }
  console.log(`      home rendered: title="${homeTitle}" url="${homeUrl}"`);
  screenshot('02-webview-home.png');

  // --- Milestone 3: navigate to the signed-out sign-in screen and assert it. ---
  console.log(`[3/3] navigating the WebView to ${SIGNIN_URL} and waiting for the signed-out sign-in screen…`);
  await cdp.send('Page.navigate', { url: SIGNIN_URL });
  const signin = await waitFor(
    async () => {
      const s = await cdp.evaluate(SIGNIN_PROBE);
      // Signed-out screen is "rendered" when #auth-signin is visible AND it shows
      // a real sign-in affordance (the Google button or the email/password submit).
      const ok =
        s &&
        s.visible &&
        ((s.googleText && /Continue with Google/i.test(s.googleText)) || s.hasSubmit);
      return ok ? s : null;
    },
    { timeout: SIGNIN_RENDER_TIMEOUT, label: 'the signed-out #auth-signin panel to become visible' },
  );
  if (!String(signin.title).includes('OpenSkeleton Account')) {
    throw new Error(
      `sign-in page identity assertion failed: title="${signin.title}" url="${signin.url}"`,
    );
  }
  console.log(
    `      signed-out sign-in screen rendered: title="${signin.title}" ` +
      `google="${signin.googleText}" hasSubmit=${signin.hasSubmit} url="${signin.url}"`,
  );
  screenshot('03-signin-signed-out.png');

  cdp.close();
  console.log('\nOK — boot -> WebView UI -> signed-out sign-in screen all rendered and asserted.');
}

main().catch(async (err) => {
  console.error(`\nFAILED: ${err.message}`);
  // Best-effort diagnostics so a red run is triageable from the artifacts alone.
  try {
    screenshot('99-failure.png');
  } catch (e) {
    console.error(`  (could not capture failure screenshot: ${e.message})`);
  }
  try {
    // Dump the native view hierarchy to a file, then read it back — more reliable
    // than dumping to a pseudo-tty. Helps triage the case where the WebView never
    // rendered (the native tree still shows what IS on screen).
    execFileSync('adb', ['shell', 'uiautomator', 'dump', '/sdcard/osk-e2e-dump.xml']);
    const dump = execFileSync('adb', ['exec-out', 'cat', '/sdcard/osk-e2e-dump.xml'], {
      maxBuffer: 8 * 1024 * 1024,
    }).toString();
    console.error(`  ui hierarchy (truncated):\n${dump.slice(0, 2000)}`);
  } catch {
    /* uiautomator dump is best-effort only */
  }
  process.exit(1);
});
