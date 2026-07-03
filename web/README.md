# web

Browser front-end: the single-page web application users interact with.

**Stack:** a static build (bundled HTML/CSS/JS) served by nginx in a container
and deployed to Firebase Hosting. Talks to the `backend` API over `/api/v1`.

**Status:** static one-pager served by nginx, and now continuously deployed to
Firebase Hosting on merge to `main` (OSK-13). A minimal branded page, the nginx
config (SPA routing + caching), the container build and the Hosting CD workflow
live here. Real app tooling (bundler, client-side router) lands in later tickets.

## Contents

- `index.html` — minimal, self-contained branded landing page (no external
  requests, no secrets). Loads `config.js` for its runtime API config.
- `app.html` — **protected account area** (OSK-74), reachable at `/app`. An auth
  guard renders it only when signed in; when signed out it shows an email/password
  form plus "Sign in with Google", and when the Firebase Web App isn't registered
  yet it shows a clear "auth not configured" notice. When signed in it calls the
  backend `GET /api/v1/me` with the Firebase ID token as proof of end-to-end wiring.
- `auth.js` — the web auth module (OSK-74). Reads `window.__APP_CONFIG__.firebase`,
  lazily loads the Firebase JS SDK (v10 modular) from gstatic at a **pinned** version,
  and exposes `window.OSKAuth` (`signInWithEmailPassword`, `signInWithGoogle`,
  `signOut`, `onAuthStateChanged`, `getIdToken`) plus the **pure** guard helpers
  (`computeGuardView` / `applyGuardView`) it also exports for Node unit-simulation. If
  `apiKey` is empty it never crashes — it resolves to a "not configured" state and
  makes no network calls.
- `config.js` — runtime config exposing `window.__APP_CONFIG__.apiBaseUrl`, the alert
  block, and (OSK-74) a `firebase` block for the auth guard. The committed
  `apiBaseUrl` is the **local dev default** (`http://127.0.0.1:8080`); CD rewrites it
  with the real backend URL at deploy time and refuses to ship a loopback value to the
  live channel (see Deploy & rollback below). The `firebase.apiKey` / `firebase.appId`
  are left **empty** on purpose — a human registers the Firebase Web App in the
  console (**OSK-92**, needs firebase-scoped ADC **OSK-38**) and those two values are
  injected exactly like `apiBaseUrl`; the other three firebase values are known,
  non-secret project identifiers and are prefilled.
- `tour.js` — the onboarding **product tour** (OSK-105): per-page **coachmarks**
  (spotlight/backdrop cut-out + arrow bubble with Next/Back/Skip and "2 of 5"
  progress) plus a pausable, replayable **whole-site walkthrough** that chains the
  per-page tours across pages (progress persisted in `localStorage`). Self-contained
  and hand-rolled (no CDN/library): every page opts in with a single
  `<script src="/tour.js"></script>` include; the module injects its own themed styles
  (palette tokens, so it re-skins incl. the sketch theme) and a "?" launcher. A new
  visitor is auto-started once (guarded by a `localStorage` flag, and suppressed for
  automated `navigator.webdriver` sessions so it never fights the e2e specs). The
  optional `window.__APP_CONFIG__.tour` block (`enabled` / `autoStartFirstRun`) is a
  runtime kill-switch. Pure state/persistence logic is exported for the headless Node
  simulation `web/e2e/tour.simulation.cjs`; the in-browser walkthrough is
  `web/e2e/tests/tour.spec.ts`.
- `nginx.conf` — server config: SPA `try_files` fallback to `index.html` plus
  caching headers (immutable 1-year cache for hashed assets, `no-cache` for
  `index.html`) and a `/healthz` liveness endpoint.
- `Dockerfile` — `nginx:alpine` runtime that copies the static assets and our
  nginx config into the image.

## Deploy & rollback (Firebase Hosting)

Deploy is automatic: every merge to `main` runs
`.github/workflows/hosting-deploy.yml`, which authenticates to GCP keyless (WIF /
OIDC — no key, no CI token), injects the backend URL into `config.js`, guards
against a loopback URL shipping, then runs `firebase deploy --only hosting` to
the **live** channel of site `openskeleton-one`
(<https://openskeleton-one.web.app>).

**Rollback** — Firebase Hosting retains every release, so rollback is instant and
needs no rebuild:

```bash
# Re-point the live channel at the immediately previous release:
firebase hosting:rollback --project openskeleton-one --site openskeleton-one

# Or list releases and roll back to a specific one (Firebase console -> Hosting
# -> release history -> "Rollback" does the same via the UI):
firebase hosting:releases:list --project openskeleton-one --site openskeleton-one
```

A git revert of the offending commit on `main` also works: the next push
re-deploys the reverted assets.

> Note: the live deploy runs on **merge** (push to `main`). The backend-URL
> injection stays a no-op (loopback default neutralised to same-origin) until
> OSK-47 deploys the backend Cloud Run service; it then activates automatically.

## Build step

There is **no build tooling yet** — the "build" is a no-op. The static assets
(`index.html`) are committed as-is and copied straight into the image, so the
build step is simply:

```bash
# From the web/ directory. Produces the runnable nginx image.
docker build -t osk-web .
```

When a real bundler (Vite/webpack/etc.) is added, it will emit a `dist/`
directory and the `Dockerfile` should gain a first build stage that runs the
bundler and `COPY --from=builder /app/dist` into nginx's web root, replacing the
current straight copy of `index.html`.

## Run locally

```bash
docker run -d --rm -p 8088:80 --name osk-web osk-web
# Root serves the page:
curl -sS -o /dev/null -w '%{http_code}\n' http://localhost:8088/          # 200
# Deep routes fall back to index.html (SPA routing):
curl -sS -o /dev/null -w '%{http_code}\n' http://localhost:8088/some/deep/route  # 200
docker stop osk-web
```

Then open <http://localhost:8088/> in a browser to see the page render.

## Notes

- No secrets in static assets — the page makes no external calls and embeds no
  keys. API calls (once the app exists) go through the `backend` over
  `/api/v1`.
- Caching: fingerprinted assets are safe to cache for a year because their
  filename changes when content changes; `index.html` is never cached so new
  deploys are picked up immediately.
