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
├── playwright.config.ts   # headless Chromium; boots the static server; baseURL; projects
├── static-server.mjs      # zero-dep static server, mirrors firebase.json rewrites
├── auth-guard.simulation.cjs   # headless Node sim of the OSK-74 guard (no browser)
├── history.simulation.cjs      # headless Node sim of the OSK-101 profile-history view
├── tests/
│   ├── smoke.spec.ts      # the static-pages smoke walkthrough
│   ├── theme.spec.ts      # the default <-> sketch theme-switch walkthrough
│   ├── auth-guard.spec.ts # OSK-74 /app guard: the graceful "not configured" state
│   ├── history.spec.ts    # OSK-101 /history view: the graceful "not configured" state
│   ├── visual.spec.ts     # NIGHTLY-ONLY visual-regression snapshots (both themes)
│   └── visual.spec.ts-snapshots/   # committed baseline PNGs for the above
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

## Auth guard (OSK-74) — spec + headless simulation

The protected `/app` page (`web/app.html`) is guarded by `web/auth.js`. Two things cover
it without needing a live Firebase sign-in (which requires the human apiKey/appId from
**OSK-92** and firebase-scoped ADC **OSK-38**, neither available in CI):

- **`tests/auth-guard.spec.ts`** (runs in the fast `chromium` project) — loads `/app` and
  proves it parses, boots, and lands in the graceful **"auth not configured"** state that
  the committed empty `apiKey` produces: the notice is shown (and names OSK-92) while the
  sign-in form and the protected content stay hidden. No network/auth call is made.
- **`auth-guard.simulation.cjs`** — a zero-dependency Node script that `require()`s the
  **pure** guard helpers `auth.js` exports (`computeGuardView` / `applyGuardView`) and
  asserts the show/hide logic across **signed-out**, **signed-in**, **not-configured**,
  loading and error states — the signed-in path the browser can't reach in CI. Run it
  with `node auth-guard.simulation.cjs` (exits non-zero on any failure).

When OSK-92 supplies the apiKey/appId, a real **cold-login** walkthrough can drop in as a
new `tests/*.spec.ts` using this same harness — no infra change.

## Admin users console (OSK-72) — headless simulation

The admin console (`web/admin.html` + `web/admin.js`) is gated to signed-in **ADMINs**,
so a live end-to-end run needs both the human Firebase apiKey (**OSK-92**) and an ADMIN
test user — neither available in CI. As with the auth guard, the console's decision +
request logic is **pure** and `admin.js` exports it, so it is covered headlessly:

- **`admin-console.simulation.cjs`** — a zero-dependency Node script that `require()`s the
  pure helpers `admin.js` exports and asserts, with a fake DOM + a **stubbed fetch**:
  the admin-access state machine (`computeAdminView`: not-configured / loading / error /
  signed-out / checking / **signed-in-non-admin** / **signed-in-ADMIN**, plus 401/403 on
  `/api/v1/me`); the client-side search/filter (`applyUserFilters`); the list-render row
  model; the pager model; and the API client (`createAdminApi`) — proving every request's
  shape (method, URL incl. `?page=&size=`, `Authorization: Bearer`, JSON `{role}` /
  `{enabled}` body) and its 401 / 403 / signed-out / network-error handling. Run it with
  `node admin-console.simulation.cjs` (exits non-zero on any failure).

When OSK-92 + an ADMIN test user are available, a real admin walkthrough can drop in as a
new `tests/*.spec.ts` using this same harness — no infra change.

## Profile history (OSK-101) — spec + headless simulation

The protected `/history` page (`web/history.html` + `web/history.js`) reuses the same
OSK-74 guard and calls `GET /api/v1/me/history`. It's covered the same two ways, again
without a live sign-in:

- **`tests/history.spec.ts`** (fast `chromium` project) — loads `/history` and proves it
  parses, boots, and lands in the graceful **"auth not configured"** state (notice shown +
  names OSK-92; sign-in form and the history region hidden). No network/auth call is made.
- **`history.simulation.cjs`** — a zero-dependency Node script that `require()`s the
  **pure** helpers `history.js` exports and asserts field/value/timestamp formatting, the
  **newest-first** ordering, the **empty state**, the **401** / HTTP-error / offline panel
  states, and that `fetchHistory` sends the right `Authorization: Bearer` header — the
  signed-in fetch/render path the browser can't reach in CI. It renders into a fake DOM and
  uses a stubbed `fetch`. Run it with `node history.simulation.cjs` (exits non-zero on any
  failure).

## When it runs

The dedicated workflow [`.github/workflows/e2e.yml`](../../.github/workflows/e2e.yml)
has **two jobs**:

- **`e2e` (fast gate)** — the smoke + theme specs. Runs on **push to `main`**, on
  **`pull_request` when the PR touches `web/**`** (OSK-108 no-rot guard), and on manual
  **`workflow_dispatch`**. It runs `npx playwright test --project=chromium`, which
  **ignores** `visual.spec.ts`, so the visual snapshots never slow the PR/main gate. It
  is still deliberately absent from `ci.yml`.
- **`visual` (nightly)** — the visual-regression spec. Runs **only** on the nightly
  `schedule:` cron (`0 3 * * *` UTC), **never** on a PR or push, via
  `VISUAL=1 npx playwright test --project=visual`.

Both jobs upload the HTML report + screenshots/video/traces as an artifact **on
failure** (the visual report embeds the expected/actual/diff images).

## Visual regression (nightly only)

`tests/visual.spec.ts` captures full-page screenshots of the four key pages
(`/`, `/status`, `/ops`, `/help`) in **both** themes (default + sketch) with
`expect(page).toHaveScreenshot()`, and compares them against committed baseline PNGs in
`tests/visual.spec.ts-snapshots/`.

**Why nightly only** — pixel snapshots are brittle (font hinting, sub-pixel
anti-aliasing) and slower than DOM assertions, so they must not gate every PR. They are
gated off the default run by **two** independent guards:

1. a dedicated Playwright **`project` named `visual`** — the PR/main job runs
   `--project=chromium` (smoke + theme), the nightly job runs `--project=visual`; and
2. a **`VISUAL` env guard** — the spec `test.skip`s itself unless `VISUAL` is set, which
   only the nightly job does. So a bare `npx playwright test` (locally or on a PR) skips
   the whole visual suite.

**Determinism** — the backend is down in CI, so each page is pinned to its resolved
`unavailable`/`outage` state before capture, and the volatile bits are neutralised:

- the live health **"as of" timestamps** (`#banner-time`, `#health-time`) and the
  **build stamp** (`#web-build` / `#backend-build`) are hidden with injected CSS
  (`visibility: hidden`) — the AC's "CSS to neutralize" option (chosen over `mask[]`,
  whose box would track the elements' variable text width and jitter);
- the **health dot / banner state** is pinned by *waiting* for each page's live probe to
  settle (web reachable / backend unavailable) before the snapshot.

### Updating baselines

Baselines are committed and are **platform-specific** — the nightly runs on Linux
(`ubuntu-latest`), so the committed PNGs are the **Linux** renders. Regenerate them in a
Linux environment that matches CI (do **not** commit macOS/Windows renders — they won't
match the runner):

```bash
# From the repo root, using the official Playwright image pinned to our version.
# Mounts web/ (the static-server's web root) and keeps node_modules container-local.
docker run --rm -v "$PWD/web":/web -v /web/e2e/node_modules \
  -w /web/e2e -e VISUAL=1 mcr.microsoft.com/playwright:v1.61.1-noble \
  bash -lc "npm ci && npx playwright test --project=visual --update-snapshots"
```

Then review the changed PNGs (`git status tests/visual.spec.ts-snapshots/`) and commit
them. The nightly job runs **without** `--update-snapshots`, so it **fails** on a real
visual drift rather than silently rewriting the baseline — an intentional change is a
deliberate `--update-snapshots` + commit.

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
user → see the audit entry** — is intentionally **not** built here. The **login UI** now
exists (OSK-74, the `/app` guard — see the auth-guard section above), but the **admin
users console** it drives (OSK-72) does **not** yet, and a real login needs the human
Firebase apiKey (OSK-92). Faking an admin flow would test nothing real, so this cut ships
the reusable harness plus genuine smoke walkthroughs of the pages that exist today. When
OSK-72 lands (and OSK-92 supplies the apiKey), that walkthrough drops in as a new
`tests/admin-users.spec.ts` using this same harness — no infra change.
