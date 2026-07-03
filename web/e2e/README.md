# web/e2e — browser end-to-end harness (Playwright)

Reusable Playwright harness that drives the OpenSkeleton **static web pages** in a
real headless Chromium browser. It serves the committed `web/` files with a tiny
zero-dependency static server that mirrors the Firebase Hosting rewrites, then walks
the pages a user actually hits (`/`, `/status`, `/ops`) and asserts they render and
**degrade gracefully when the backend is down**.

This is a self-contained package (its own `package.json`, its own dependency) so it
never touches the backend build or the web container. It is gated **off the PR path**
— see [When it runs](#when-it-runs).

## Layout

```
web/e2e/
├── package.json           # this package + its only dep (@playwright/test)
├── package-lock.json      # pinned install for reproducible CI (npm ci)
├── playwright.config.ts   # headless Chromium; boots the static server; baseURL
├── static-server.mjs      # zero-dep static server, mirrors firebase.json rewrites
├── tests/
│   └── smoke.spec.ts      # the static-pages smoke walkthrough
└── README.md              # this file
```

## Run locally

From `web/e2e/`:

```bash
npm install                                   # install the harness dep
npx playwright install --with-deps chromium   # one-time: fetch the browser
npx playwright test                           # run the suite (boots the server itself)
```

You do **not** need to start a server first — `playwright.config.ts` has a
`webServer` block that launches `static-server.mjs` on port `4310`, waits for it to
answer, runs the specs, then tears it down. Override the port with `PORT=5000 …`.

Handy variants:

```bash
npx playwright test --headed   # watch it drive a visible browser
npx playwright test --ui       # interactive UI mode
npx playwright show-report     # open the HTML report from the last run
node static-server.mjs         # just serve web/ at http://localhost:4310 to poke by hand
```

## What the smoke spec asserts

Against the real committed pages (backend intentionally not required to be up):

- **`/` (index.html)** — title + `<h1>` are `OpenSkeleton`, the `OS` logo mark, the
  `web · nginx · container` tag, the OSK-100 build stamp (web build id renders as
  `dev`; the backend build id resolves away from its `…` placeholder — i.e. the
  `/version` fetch degraded gracefully), and the footer links to `/status` and `/ops`.
- **`/status` (status.html)** — title + `<h1>`, the overall status **banner** is
  visible and resolves away from `Checking service status…` to a real state (with the
  backend down it settles on `Service outage`), both **component** rows render and the
  backend row resolves to a status word, and the Metrics placeholder renders.
- **`/ops` (ops.html)** — title + `<h1>`, the live backend-**health** indicator is
  visible and resolves away from `Checking backend health…` (with the backend down it
  settles on `Backend unavailable`), the three link-group panels render, and the
  backend links are wired at runtime (the Health link `href` ends `/health`).

Each test attaches a full-page **screenshot** to the report/artifacts.

Because CI has nothing on the backend port, every live probe fails fast and each page
must render its graceful `unavailable`/`outage` state — that graceful degradation is
exactly what the spec proves.

## When it runs

The dedicated workflow [`.github/workflows/e2e.yml`](../../.github/workflows/e2e.yml)
runs this suite on **push to `main`** and **manual `workflow_dispatch` only** — never
on feature pull requests, so the PR gate (`ci.yml`) stays fast. It installs the
Chromium browser, runs `npx playwright test` (which serves `web/` itself), and uploads
the HTML report + screenshots/video as an artifact **on failure**.

## Add a new walkthrough

A new walkthrough is a **new spec file**, not new infrastructure:

1. Add `tests/<name>.spec.ts`.
2. `import { test, expect } from "@playwright/test";` and navigate with `page.goto("/…")`
   (relative to the configured `baseURL`).
3. Run `npx playwright test`. No change to `playwright.config.ts`, `static-server.mjs`,
   or `e2e.yml` is needed — the config picks up any `tests/*.spec.ts` automatically.

If a walkthrough needs a URL path that isn't a real file and isn't already rewritten,
add the mapping to `REWRITES` in `static-server.mjs` (mirroring `firebase.json`).

### Deferred: the admin-console walkthrough

The OSK-81 ticket's headline walkthrough — **login → users admin console → disable a
user → see the audit entry** — is intentionally **not** built here. The admin console
and login UI it needs do **not exist yet** (they are OSK-72 / OSK-74). Faking a
login/admin flow would test nothing real, so this cut ships the reusable harness plus a
genuine smoke walkthrough of the pages that exist today. When OSK-72 lands, that
walkthrough drops in as a new `tests/admin-users.spec.ts` using this same harness — no
infra change.
