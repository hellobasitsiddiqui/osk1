# OpenSkeleton (osk1)

A production-ready, full-stack **application skeleton** — the generic base a
team forks to start a new product with the boring-but-critical foundations
already in place: CI/CD, containerisation, auth, observability, security
scanning, and a cloud deploy path.

This repository (`osk1`) is **round 1** of the OpenSkeleton fleet build: the
code is assembled by a fleet of agents replaying the `OSK` Jira ticket set. The
product spec and canonical template live in the separate `openskeleton` repo.

## Repository layout

This is a mono-repo. Each top-level directory owns one surface, so tickets that
touch different surfaces can be built in parallel without colliding:

| Directory | Purpose |
| --- | --- |
| [`backend/`](backend/) | Java + Spring Boot REST API (`/api/v1`, `/health`), Postgres via Flyway. |
| [`web/`](web/) | Browser single-page app; static build served by nginx / Firebase Hosting. |
| [`webview/`](webview/) | Shared WebView bridge between the web app and the native mobile shells. |
| [`android/`](android/) | Android native shell wrapping the web build (hybrid), with native plugins. |
| [`infra/`](infra/) | Infrastructure-as-code: GCP/Firebase, Cloud Run, Cloud SQL, CI/CD, secrets. |

Each directory currently holds a stub `README.md` stating its purpose; later
foundation tickets fill them in.

## Getting started

### Prerequisites

- **JDK 21** (the backend targets Java 21).
- **Docker** — to build/run the `web` image (and, later, container parity for the backend).
- No global Maven install needed — the backend ships the Maven wrapper (`./mvnw`).

### Backend (Spring Boot API)

The backend lives in [`backend/`](backend/) and boots with zero config using the
base/dev defaults (see [`.env.example`](.env.example) for every variable it reads).

```bash
cd backend
./mvnw spring-boot:run
```

The API then serves `/api/v1/...` with operational probes at
`/actuator/health`, `/actuator/info`, and `/actuator/metrics` on port `8080`
(override with `PORT`). To run the full build and tests instead:

```bash
cd backend
./mvnw -B -ntp verify
```

Configuration is 12-factor and env-overridable. Copy [`.env.example`](.env.example)
to `.env` and fill in real values for a non-default run; the `prod` profile
requires all app/DB vars to be supplied explicitly (they have no defaults).

### Web (static SPA)

The web front-end lives in [`web/`](web/) and is packaged into an nginx image.
Build and run it with Docker:

```bash
cd web
docker build -t osk1-web .
docker run --rm -p 8080:80 osk1-web
```

The site is then served at <http://localhost:8080> (nginx exposes a `/healthz`
endpoint used by the container health check).

### Other surfaces

`webview/`, `android/`, and `infra/` are filled in by later foundation tickets;
see each directory's `README.md` and [`ARCHITECTURE.md`](ARCHITECTURE.md) for how
the surfaces fit together.

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md) for the branching model, commit format,
and PR review flow. In short: trunk-based on `main`, one `OSK` ticket per branch,
changes land via reviewed PRs, and **agents never merge — a human/owner does.**

## License

Released under the [MIT License](LICENSE) — © 2026 Basit Siddiqui / OpenSkeleton.

## Conventions

- **Default branch:** `main`. Trunk-based — `main` is the integration branch;
  features land via reviewed PRs, and deploy is decoupled from merge (so `main`
  means *"integrated, not necessarily released"*).
- **No secrets in git.** The root [`.gitignore`](.gitignore) blocks env files and
  credential material (service-account keys, ADC JSON, `*.pem`, `*.p12`, …). The
  project is keyless (GitHub OIDC), so no long-lived key should ever exist.
- **Every change traces to an `OSK` ticket** and lands through a reviewed PR —
  agents never merge; a human does.

## Fleet / round meta

- **Round artifact:** `hellobasitsiddiqui/osk1` (this repo) — round 1's code lands here.
- **Spec + template:** `hellobasitsiddiqui/openskeleton`.
- **Build engine:** the `agents-at-work` plugin — fleet conventions, Definition
  of Done, and lessons.
- See [`KICKOFF.md`](KICKOFF.md) for how a build agent starts a round, and
  [`docs/agents/PROMPT-LOG.md`](docs/agents/PROMPT-LOG.md) for the verbatim
  human-prompt log.

## Run the whole stack locally

```
./scripts/dev-setup.sh   # writes a local .env with dev defaults (git-ignored)
docker compose up         # backend + web + Postgres, one command
```

Backend on http://localhost:8080, web on http://localhost:8081, Postgres on 5432
(all bound to 127.0.0.1). Data persists in the `pgdata` volume. For authenticated
testing once Firebase auth lands, run `gcloud auth application-default login` and add
the overlay: `docker compose -f docker-compose.yml -f docker-compose.auth.yml up`.
