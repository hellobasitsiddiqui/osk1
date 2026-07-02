# Secrets & environment contract (OSK-15)

How the backend receives its runtime configuration and its one secret, and why CI
needs **no repository secrets**. This is the single, authoritative description of the
contract that three files must agree on:

- [`.env.example`](../.env.example) — the documented template of every var the app reads.
- [`backend/src/main/resources/application-prod.yml`](../backend/src/main/resources/application-prod.yml)
  — what the prod profile actually requires (the OSK-25 fail-loud validator: `@NotBlank`
  on `AppProperties` + bare `${VAR}` placeholders with no defaults).
- [`.github/workflows/deploy.yml`](../.github/workflows/deploy.yml) (OSK-47) — what the
  Cloud Run deploy actually sets.

Related: [`cloud-sql.md`](cloud-sql.md) (the DB secret + connector), [`gcp/cloud-run.md`](gcp/cloud-run.md)
(runtime SA IAM), [`gcp.env`](gcp.env) (non-secret project identifiers).

## Identity model — keyless by design

- **CI → GCP auth is keyless.** GitHub Actions federates to GCP via **Workload Identity
  Federation (OIDC)** — `google-github-actions/auth` is given a
  `workload_identity_provider` + `service_account` and mints a short-lived credential.
  There is **no service-account JSON key** anywhere in the repo or in GitHub secrets
  (no `credentials_json`, OSK-42).
- **The only runtime secret is `DB_PASSWORD`.** It lives in **Secret Manager**
  (`osk-db-app-password`) and Cloud Run injects it at deploy time via
  `--set-secrets=DB_PASSWORD=osk-db-app-password:latest`. The value never enters the
  repo, the workflow file, or the CI logs.
- **Firebase Admin uses ADC.** Token verification (OSK-28) and custom-token signing
  (OSK-133) authenticate through Application Default Credentials — the Cloud Run runtime
  SA in prod, `gcloud auth application-default login` locally. **No Firebase private key
  is committed.**

## AC-1 outcome — no GitHub Actions repo secrets required

**None.** The deploy is keyless (WIF/OIDC) and the single runtime secret is sourced from
Secret Manager, so there is nothing for a maintainer to configure under
*Settings → Secrets and variables → Actions*. The only `${{ secrets.* }}` reference in
any workflow is the auto-provided `GITHUB_TOKEN` (used by gitleaks), which is not a
configured repo secret. If a future change appears to need a repo secret, prefer Secret
Manager + WIF instead — adding a repo secret would be a regression of this design.

## The contract — required prod vars → source

All of the following are set by the deploy on the Cloud Run service. Every var the prod
profile hard-requires (no default) is present, so the OSK-25 fail-loud validator passes.

| Var | Source | Sensitive? | Notes |
| --- | --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | plain Cloud Run env var (deploy sets `prod`) | no | selects the prod profile |
| `INSTANCE_CONNECTION_NAME` | plain Cloud Run env var (deploy) | no | `project:region:instance`; builds the Cloud SQL socket-factory URL. Required (no default) |
| `DB_NAME` | plain Cloud Run env var (deploy sets `osk`) | no | database name on the instance (prod default `osk`) |
| `DB_USERNAME` | plain Cloud Run env var (deploy sets `app`) | no | Cloud SQL app user (prod default `app`) |
| `DB_PASSWORD` | **Secret Manager** `osk-db-app-password:latest` via `--set-secrets` | **YES** | the ONLY runtime secret; never committed, never echoed. Required (no default) |
| `APP_ENVIRONMENT_NAME` | plain Cloud Run env var (deploy sets `prod`) | no | `@NotBlank`; `/actuator/info` label. Required |
| `APP_PUBLIC_BASE_URL` | plain Cloud Run env var (deploy sets the Cloud Run URL) | no | `@NotBlank`; absolute links. Required |
| `GOOGLE_CLOUD_PROJECT` | plain Cloud Run env var (deploy sets `openskeleton-one`) | no | Cloud Monitoring metrics project (OSK-55) |

### Derived / platform-injected (not set by the deploy — listed for completeness)

| Var | Source | Sensitive? | Notes |
| --- | --- | --- | --- |
| `PORT` | derived — Cloud Run injects | no | server bind port |
| `K_REVISION` | derived — Cloud Run injects at runtime | no | per-deploy revision, surfaced at `/version` |
| `GIT_SHA` | derived — baked into the image at build (image.yml) | no | commit stamp, surfaced at `/version` |
| `FIREBASE_PROJECT_ID` | derived — defaults to `openskeleton-one`; credentials via ADC | no | token-verification project; no key committed |

**Local / docker-compose** uses a different, zero-secret DB path: the dev profile builds
the datasource from `DB_URL` (a plain localhost/compose JDBC URL) instead of the socket
factory. `scripts/dev-setup.sh` generates a working git-ignored `.env`. Prod ignores
`DB_URL`; dev ignores `INSTANCE_CONNECTION_NAME`/`DB_NAME`.

## AC-4 — no secret is ever printed in CI logs

Verified against [`deploy.yml`](../.github/workflows/deploy.yml):

- `DB_PASSWORD` is passed to `gcloud` as a **Secret Manager reference**
  (`--set-secrets=DB_PASSWORD=osk-db-app-password:latest`), i.e. a name + version — the
  secret **value** is never materialised in the workflow, so it cannot be echoed.
- `--set-env-vars` carries only non-sensitive identifiers (project, instance, DB name,
  DB user, URLs, profile).
- The keyless auth step prints no token; `google-github-actions/auth` writes the
  short-lived credential to a file gcloud reads — it is not echoed.
- No step enables shell command tracing (`set -x`) and nothing `echo`s a secret. The
  verify step logs only revision names and image digests.

If a future edit needs to print a value derived from `DB_PASSWORD` (or the OIDC token),
mask it with `::add-mask::` first, or better, don't surface it at all.

## Why no change to deploy.yml was needed

`deploy.yml` already sets every var the prod profile requires (the `@NotBlank` and
no-default vars are all covered) and already never echoes a secret. OSK-15 is a
**reconcile + document** ticket: it aligns `.env.example` with the real socket-factory
contract and records the delivery model here; the runtime path (OSK-47) was already
correct.
