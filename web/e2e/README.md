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
├── social-login.simulation.cjs # headless Node sim of OSK-131 social/federated logins
├── completion-gate.simulation.cjs # headless Node sim of the OSK-136 first-login gate
├── badges.simulation.cjs       # headless Node sim of the OSK-70 account-state badges
├── history.simulation.cjs      # headless Node sim of the OSK-101 profile-history view
├── avatar.simulation.cjs       # headless Node sim of the OSK-83 avatar upload flow
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

## Social / federated logins (OSK-131) — headless simulation

`web/app.html` offers additional federated sign-in providers (Google, plus **Facebook**
and **Apple**, structured so more are trivial) alongside email/password. A **live** run is
impossible in CI — each provider must be **enabled in the Firebase console** (client
IDs/secrets, a HUMAN step) and the flow needs a real popup + OAuth round-trip — so the
non-OAuth surface is covered headlessly:

- **`social-login.simulation.cjs`** — a zero-dependency Node script that `require()`s the
  **pure** provider helpers `auth.js` exports and asserts, with a **stubbed Firebase**
  namespace and fake button elements (no browser, no network):
  - **config-gating** — `enabledProviders` / `isProviderEnabled` reflect
    `config.firebase.providers` (Google on by default; Facebook/Apple only when toggled
    on **and** the apiKey is present), and the **committed** `config.js` ships _Google
    only_ so unconfigured providers never render a broken button;
  - **button rendering** — `applyProviderVisibility` reveals exactly the enabled
    providers' buttons and hides the rest;
  - **per-provider sign-in call** — the shared builder constructs the right Firebase
    provider per id (`GoogleAuthProvider` / `FacebookAuthProvider` /
    `OAuthProvider("apple.com")` with the right scopes) and a stubbed `signInWithPopup`
    resolves — the exact path the browser's `signInWithProvider` runs; disabled/unknown
    providers are refused before any popup opens; and
  - **error handling** — `describeAuthError` maps the codes the OAuth flow produces,
    including `account-exists-with-different-credential` and `popup-closed-by-user`, to
    friendly, provider-personalised copy.

  Run it with `node social-login.simulation.cjs` (exits non-zero on any failure).

Enabling Facebook/Apple is a console step (see `config.js` `firebase.providers`); once a
provider is enabled there and its toggle flipped, its button appears — no harness change.

## Account-state badges (OSK-70) — headless simulation

The signed-in `/app` (and `/profile`) view shows a compact **account-state badge row**
(`web/badges.js` + `web/badges.css`) — email-verified, age-verified and MFA — read from
`GET /api/v1/me`. It renders only for a signed-in user, so a live run needs the human
Firebase apiKey (**OSK-92**); the pure state-mapping + render logic is exported for
headless coverage instead:

- **`badges.simulation.cjs`** — a zero-dependency Node script that `require()`s the pure
  helpers `badges.js` exports and asserts, with a fake DOM + a **stubbed fetch**: the
  flag→state mapping (`computeBadges` — email/age booleans → verified/unverified; the MFA
  **tri-state** where `mfaEnabled === null` → **"unknown", never "off"**, with an optional
  `mfaUnknownMode: "hide"`); the controller render (one `<li>` per badge with the right
  icon/label/`data-state`/`aria-label`/`title`); and graceful degradation — **signed-out**
  (no token), **401**, **500** and **network error** all render **nothing** (mount cleared
  + hidden). It also machine-checks the **XSS-safe** contract: a poisoned `innerHTML`
  setter must never fire, and a hostile string in an unrelated `/me` field never reaches
  the DOM. Run it with `node badges.simulation.cjs` (exits non-zero on any failure).

When OSK-92 supplies the apiKey, a real signed-in walkthrough asserting the visible badges
can drop in as a new `tests/*.spec.ts` using this same harness — no infra change.

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

## Avatar upload (OSK-83) — headless simulation

The edit-profile page (`web/profile.html` + `web/profile.js`) lets a signed-in user pick an
image, preview it, and upload it as their profile photo. The bytes go **straight to Cloud
Storage** under the caller's own `users/{uid}/…` area (the Firebase Storage Web SDK via
`web/auth.js` → `OSKAuth.uploadAvatar`, owner-only per the committed `storage.rules`); the
backend then reflects the object as the user's Firebase `photoURL`
(`PUT /api/v1/me/avatar`), which `GET /api/v1/me` already surfaces live (OSK-73). A live run
needs the human apiKey (**OSK-92**), firebase-scoped ADC (**OSK-38**) and the Storage product
enabled (**OSK-95**), so the whole flow is covered headlessly:

- **`avatar.simulation.cjs`** — a zero-dependency Node script that `require()`s the pure
  helpers + DOM-injectable **avatar controller** `profile.js` exports (plus the pure
  `avatarObjectPath` from `auth.js`) and asserts, with a fake DOM + a **stubbed fetch** + a
  **stubbed upload seam** (so no real Storage is touched): the content-type + size
  **`validateAvatarFile`** gate, the per-user **`avatarObjectPath`** builder (always under
  `users/<uid>/avatar/`, extension from filename/MIME/png-fallback), pick→preview→**arm**,
  the happy **upload → PUT `{objectPath, downloadUrl}` → adopt the returned `photoURL`**
  path, and graceful **no-file / 400 / 401 / 503 / Storage-failure / no-token** handling. Run
  it with `node avatar.simulation.cjs` (exits non-zero on any failure).

The avatar panel lives inside the signed-in `#profile-app` region, so it is **hidden when
signed-out / not-configured** and does not affect `tests/profile.spec.ts`'s "signed-out"
assertions.

## First-login profile-completion gate (OSK-136) — headless simulation

The protected `/app` page also loads `web/completion-gate.js`, a **blocking first-login
gate**: when a signed-in user's `GET /api/v1/me` is missing a required field (name / city /
age) it overlays a minimal form and blocks entry until the fields are `PATCH`ed. A live run
needs the human Firebase apiKey (**OSK-92**), so — like the guard — its pure logic, its
fetch/PATCH client and its DOM builders are exported and covered headlessly:

- **`completion-gate.simulation.cjs`** — a zero-dependency Node script that `require()`s the
  helpers `completion-gate.js` exports and asserts, with a fake DOM + a **stubbed fetch**:
  required-field detection (`getMissingRequiredFields`), the sparse **`buildPatch`** (only
  entered fields; age coerced to a **number**), RFC-7807 **`parseProblemErrors`** (the
  `errors: ["field: message"]` shape), the `computeGateView` decision (inactive / loading /
  **gate** / complete / fail-open-on-error), and `fetchProfile` / `submitProfile` request
  shapes (method, URL, `Authorization: Bearer`, JSON body). It then drives the whole
  **controller** end-to-end: the gate **shows** when a field is missing, stays **hidden**
  when all are present, **submit PATCHes only the entered fields**, renders **inline 400**
  field errors while staying open, and **handles 401** without dismissing. Run it with
  `node completion-gate.simulation.cjs` (exits non-zero on any failure).

The gate is **dormant** when signed-out / not-configured, so it does not affect the
existing `tests/auth-guard.spec.ts` "not configured" assertions. When OSK-92 supplies the
apiKey, a real first-login walkthrough can drop in as a `tests/*.spec.ts` — no infra change.

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
