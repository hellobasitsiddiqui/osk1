# web

Browser front-end: the single-page web application users interact with.

**Stack:** a static build (bundled HTML/CSS/JS) served by nginx in a container
and deployed to Firebase Hosting. Talks to the `backend` API over `/api/v1`.

**Status:** static one-pager served by nginx. A minimal branded page, the nginx
config (SPA routing + caching) and the container build now live here. Real app
tooling (bundler, client-side router) and the Firebase Hosting deploy land in
later tickets.

## Contents

- `index.html` — minimal, self-contained branded landing page (no external
  requests, no secrets).
- `nginx.conf` — server config: SPA `try_files` fallback to `index.html` plus
  caching headers (immutable 1-year cache for hashed assets, `no-cache` for
  `index.html`) and a `/healthz` liveness endpoint.
- `Dockerfile` — `nginx:alpine` runtime that copies the static assets and our
  nginx config into the image.

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
