<!--
  ADR 0001 — records why the persistence layer is Cloud SQL for PostgreSQL.
  Follows the 0000-template.md structure (context / decision / consequences) so
  a maintainer can see the trade-off, not just the outcome.
-->

# 0001. Use Cloud SQL for PostgreSQL as the primary datastore

- **Status:** Accepted
- **Date:** 2026-07-02

## Context

The `backend` service needs a durable, transactional store for application
state. The skeleton already commits to a relational model — the backend README
describes Postgres accessed through Flyway-managed migrations — so the open
question is *how the database is run*, not *which engine*.

Forces in tension:

- We are a small team optimising for low operational overhead; we do not want to
  run, patch, back up, and monitor our own database servers.
- The rest of the platform is GCP-based (Cloud Run, Firebase Hosting, Artifact
  Registry, Secret Manager — see [ADR-0002](0002-hosting-cloud-run-firebase.md)),
  so a managed database in the same project keeps networking, IAM, and billing
  in one place.
- Deploys are keyless (GitHub OIDC → service account); any datastore must fit an
  IAM/Secret-Manager credential model with no long-lived keys in git.
- We want portability: a fork should be able to lift-and-shift to another Postgres
  host without rewriting the data layer.

Alternatives considered: self-managed Postgres on a VM (more control, far more
ops burden); Firestore / a NoSQL store (loses SQL, joins, and Flyway migrations,
and does not match the relational domain model); AlloyDB (more powerful/costly
than a round-1 skeleton needs).

## Decision

We will use **Cloud SQL for PostgreSQL** as the primary datastore, provisioned
via the `infra` IaC and connected from the Cloud Run backend over the Cloud SQL
connector. Schema is owned by **Flyway** migrations in the backend. Database
credentials live in **Secret Manager**, never in git.

Choosing managed Postgres keeps standard SQL and our Flyway workflow (so the data
layer stays portable) while GCP handles provisioning, patching, backups, and
failover. Keeping it in the same GCP project as Cloud Run means private
connectivity and one IAM/secrets surface.

## Consequences

- **Easier:** no database servers to operate; automated backups, point-in-time
  recovery, and minor-version patching come for free; connection auth flows
  through IAM + Secret Manager, matching the keyless deploy story.
- **Harder / obligations:** Cloud SQL is a GCP-managed product, so it is a soft
  vendor coupling — mitigated by the datastore itself being vanilla Postgres, so
  a migration off GCP means re-hosting, not re-writing. An always-on instance has
  a standing cost even at low traffic (a fork may pick the smallest tier or pause
  non-prod instances). Connection-pool sizing must respect Cloud SQL limits,
  especially as Cloud Run scales out. Migrations become a release-ordering
  concern (backend rollout must be compatible with the applied schema).
