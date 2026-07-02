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
