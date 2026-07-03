<!--
  ADR 0006 — records why PR-preview backends get their OWN database (osk_preview)
  instead of sharing the prod `osk` database, and how the preview workflow
  guarantees a preview can never again migrate prod.

  Follows the 0000-template.md structure (context / decision / consequences).
  This ADR doubles as the root-cause analysis (RCA) for OSK-58 / TM-65 — the
  backend PR-preview startup-probe failure — so the failure chain lives here in
  one place, not just in the incident thread.
-->

# 0006. Isolate the PR-preview database (dedicated `osk_preview`, never prod `osk`)

- **Status:** Accepted
- **Date:** 2026-07-03

## Context

### The symptom (OSK-58 / TM-65)

Backend PR-preview revisions (OSK-30, `preview.yml`) were intermittently failing
their Cloud Run **startup probe**, and — worse — a failing preview would stall
the *production* deploy on `main`. A preview environment is meant to be a
throwaway sandbox that can never affect live users, so a preview breaking prod is
a design fault, not a flake.

### The root cause — a SHARED database

`preview.yml` was cloned from `deploy.yml` and reused its runtime env **verbatim**,
including `DB_NAME=osk` and the prod Cloud SQL instance
(`openskeleton-one:europe-west2:osk-pg`). So an *unmerged* PR's preview backend
ran **its own Flyway migrations against the production `osk` database and its
Flyway schema-history table** (first recorded as LESSONS **L12**).

That is the load-bearing defect. Everything below is a consequence of it.

### The failure chain (stale revision → probe failure → prod stall)

1. **Previews migrate prod out of merge order.** Preview revisions boot in
   PR/CI-timing order, not merge order. Two open PRs each carrying an independent
   migration (e.g. PR-A adds `V4__create_audit_events`, PR-B adds
   `V3__add_soft_delete_and_version_to_users`) apply them to the *shared* prod
   `osk` history in whatever order their previews happen to run — so prod's
   `flyway_schema_history` can reach **V4 before V3 is present** (observed:
   prod schema jumped to v4 from the #53/#54 previews *before* those PRs merged).

2. **The gap makes Flyway `validate` fail at boot.** Flyway's default
   `validateOnMigrate` rejects a *resolved* migration that has not been applied
   in order — `"resolved migration not applied to database: 3"` — so the **next**
   revision to boot against `osk` (a preview *or the real prod deploy*) throws at
   startup before the app is ready.

3. **A boot failure is a startup-probe failure.** The `/health` startup probe
   only passes once Spring finishes booting. Flyway runs during context
   initialisation, so a `validate` failure means the container never becomes
   Ready → the Cloud Run **startup probe fails** (the OSK-58/TM-65 symptom).

4. **On prod, that becomes the TM-131 stale-revision trap.** When the failing
   boot is the prod `deploy.yml` revision, the new revision never goes Ready,
   Cloud Run keeps serving the **old** revision, and (before the AC-H1/H2
   verify was added) the deploy looked green while prod served stale code. So a
   pre-merge preview could wedge the *production* release pipeline.

### What is already mitigated (and why it is not enough)

Two stop-gaps are already committed:

- `spring.flyway.out-of-order=true` in `application-prod.yml` — lets a lower
  missing version apply *after* a higher one, so the existing gap self-heals and
  prod stays deployable. This treats the *symptom* (ordering) but leaves previews
  writing to prod, so a **destructive or never-merged** preview migration would
  still corrupt prod. Our migrations are all additive today, so the residual risk
  is low — but "low" is not "impossible", which is the bar for prod data.
- `preview.yml` was **disabled** (`gh workflow disable`) to stop the pollution at
  the source. That is a hard stop, not a fix — it removes the preview feature
  entirely rather than making it safe.

The tracked follow-up (LESSONS L12) is to remove the shared-DB root cause so
previews can be safely restored. That is this ADR.

### Facts grounding the decision (verified read-only, `--project=openskeleton-one`)

- Instance `osk-pg` is **POSTGRES_16**, `RUNNABLE`.
- Databases present: `postgres`, `osk`. **No `osk_preview` yet** — the workflow
  creates it on first run.
- Users: `app` and `postgres`, both `BUILT_IN`. `app` was created through the
  Cloud SQL user API, so it is a member of the `cloudsqlsuperuser` role.
- The deploy service account `gh-deployer@openskeleton-one.iam.gserviceaccount.com`
  holds `roles/cloudsql.client` (connect only) — **not**
  `roles/cloudsql.admin`/`editor`, so it cannot *create* a database today. This
  is the one prerequisite for re-enabling (see below).

## Decision

**We will give PR previews their own database — a dedicated `osk_preview` on the
same `osk-pg` Cloud SQL instance — and point every preview backend at it via
`DB_NAME=osk_preview`. A preview's Flyway therefore migrates only `osk_preview`
and can never touch the prod `osk` schema or its Flyway history again.**

Concretely, `preview.yml`:

1. Defines a single source of truth `PREVIEW_DB_NAME: osk_preview` in workflow
   `env`, and a **hard guard** that fails the job if that value is ever empty or
   equals `osk` — so a future edit cannot silently re-point previews at prod.
2. Adds an idempotent **"ensure preview database exists"** step *before* the image
   build: it `describe`s `osk_preview` (read-only) and only `create`s it if
   missing — a `CREATE DATABASE IF NOT EXISTS`-style, workflow-owned, one-time
   operation. It touches **only** `osk_preview`; it never reads, writes, or alters
   `osk`.
3. Deploys the preview revision with `DB_NAME=${PREVIEW_DB_NAME}` (the only
   change vs. the prod env — the runtime SA, instance, secret and probes are
   otherwise identical, so the preview stays a faithful mirror of prod).

The preview keeps the **existing `app` user and its existing password secret**
(`osk-db-app-password:latest`). Because `app` is a `cloudsqlsuperuser` member and
`osk_preview` is created through the Cloud SQL API (owned by `cloudsqlsuperuser`),
`app` can create the schema-history table and all objects in `osk_preview` with
**no extra GRANT and no new secret** — exactly the mechanism by which `app`
already migrates `osk`.

### Why this option (alternatives weighed)

- **Dedicated `osk_preview` on the same instance (chosen).** Simplest change that
  fully removes the root cause: one env value + one idempotent create step, no new
  Cloud SQL instance (no extra standing cost), no new DB user, no new secret. All
  previews share `osk_preview`, which is acceptable because it is *not prod* — the
  only property we must guarantee is "never `osk`", and this delivers it.
- **Per-PR ephemeral database (`osk_pr_<N>`), created on open / dropped on close.**
  Stronger isolation — each PR gets a pristine schema, so previews never see each
  other's migrations. But it adds real orchestration: create-per-PR, drop-on-close,
  and a sweep for abandoned/never-closed PRs (a leaked DB per stale PR). For a
  round-1 skeleton the shared `osk_preview` is enough; the residual it leaves
  (below) is preview-only and self-healing. Documented here as the clean upgrade
  path if preview-vs-preview migration interference becomes a real nuisance.
- **A whole separate Cloud SQL instance for previews.** Maximum isolation, but a
  second always-on instance is standing cost and ops for no prod-safety benefit
  over `osk_preview` — rejected for a skeleton.
- **Keep sharing `osk`, rely only on `out-of-order=true`.** Rejected: it treats
  the symptom, still lets a destructive/never-merged preview migration hit prod,
  and keeps prod releases hostage to preview timing. It stays as defence-in-depth,
  not the fix.

## Consequences

- **Prod can no longer be polluted by a preview — by construction.** The prod
  `osk` database name appears in a preview code path in exactly zero places after
  this change; the JDBC URL is `jdbc:postgresql:///${DB_NAME}?cloudSqlInstance=…`,
  and `DB_NAME` for a preview is now `osk_preview`. Even an *accidental* re-enable
  of the workflow is prod-safe, because the isolation lives in the file, not in
  the enabled/disabled state. The hard guard makes a regression a red build.
- **No new secret, no new DB user, no prod data touched.** The preview reuses the
  `app` user + existing password secret; DB creation is create-if-missing and only
  ever makes a *new, empty* database.
- **`osk_preview` is persistent and shared across previews.** Residual trade-off:
  because all previews migrate the same DB, if two open PRs add different
  migrations, a preview may see another PR's applied migration that is not in its
  own classpath. Preview backends run the `prod` profile, so they inherit
  `spring.flyway.out-of-order=true`, which tolerates the *ordering* case; a
  "detected applied migration not resolved locally" case would fail that one
  preview's boot — **but only that preview**, never prod, and it self-heals as
  branches converge. If this becomes noticeable, upgrade to per-PR ephemeral DBs
  (above). Migrations are additive today, so this is rare.
- **New obligation — one IAM grant before re-enable.** The deploy SA must be able
  to create the database. See the re-enable runbook.
- **Follow-up:** `preview.yml` remains **DISABLED** for now (see below). This ADR
  does not re-enable it, because re-enabling safely requires the IAM grant plus a
  first live green run, neither of which can be proven from a code PR alone.

### Re-enable runbook (exact, ordered steps)

`preview.yml` is intentionally left **disabled** (`gh workflow disable "PR preview
environments"`). The prod-safety fix is fully baked into the file, but turning the
feature back on is a deliberate, human-gated action. To re-enable:

1. **Grant the deploy SA permission to create the preview DB** (one-time; the SA
   currently has only `roles/cloudsql.client`). Prefer a minimal custom role over
   broad admin so the CI SA can *create* but not *delete* databases:

   ```bash
   # Minimal custom role: create/get/list databases only (no delete).
   gcloud iam roles create oskPreviewDbProvisioner \
     --project=openskeleton-one \
     --title="OSK preview DB provisioner" \
     --permissions=cloudsql.databases.create,cloudsql.databases.get,cloudsql.databases.list \
     --stage=GA
   gcloud projects add-iam-policy-binding openskeleton-one \
     --member="serviceAccount:gh-deployer@openskeleton-one.iam.gserviceaccount.com" \
     --role="projects/openskeleton-one/roles/oskPreviewDbProvisioner"
   # (Quick alternative, broader: --role="roles/cloudsql.admin".)
   ```

   *No-IAM-change alternative:* instead of granting the SA, have the workflow
   create the DB with a `psql`/Cloud SQL Auth Proxy step run **as the `app` user**
   (which already has `CREATEDB` via `cloudsqlsuperuser`, so it needs no new IAM).
   That is more workflow surface; the custom-role grant above is the recommended
   path.

2. **(Optional) Pre-create the DB once** so the very first re-enabled preview does
   not depend on the grant having propagated — the workflow's ensure-step is
   idempotent and will simply find it:

   ```bash
   gcloud sql databases create osk_preview \
     --instance=osk-pg --project=openskeleton-one
   ```

3. **(Contingency) If a preview boot fails on privileges** in `osk_preview`
   (only if this instance was set up with non-default role restrictions), grant
   the app user once — connecting as an admin via the Cloud SQL Auth Proxy:
   `GRANT ALL ON DATABASE osk_preview TO app;` (and, on PG16, `GRANT ALL ON SCHEMA
   public TO app;`). Not expected to be needed given `app` is a `cloudsqlsuperuser`
   member.

4. **Re-enable the workflow** and confirm:

   ```bash
   gh workflow enable "PR preview environments"
   ```

5. **Prove it green on a throwaway PR.** Open a trivial PR, watch the `preview`
   job: the ensure-DB step should create/find `osk_preview`, and the deploy +
   startup probe should pass against it. Confirm **no** new rows appeared in the
   prod `osk` `flyway_schema_history` during the run (they must all be in
   `osk_preview`). Only after that observed green run should previews be
   considered restored.
