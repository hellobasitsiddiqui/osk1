<!--
  GENESIS.md — the one-time bootstrap ("genesis") record for the OpenSkeleton
  GCP project: the manual, replayable setup that has to exist BEFORE any CI
  deploy can run. This is the doc a round-2 replay reads to recreate the cloud
  target from scratch. Terse and factual, one cross-ref per change. The concrete
  non-secret identifiers (project ids/numbers, SA email, WIF provider) live in
  `infra/gcp.env` — that file is the source of truth for the *values*; this doc
  records what was granted and *why* so the setup can be reproduced.
-->

# GENESIS — project bootstrap & replay setup

The genesis is everything that must exist before the keyless CI/CD deploy
(see [ADR-0002](../adr/0002-hosting-cloud-run-firebase.md)) can work: the GCP
project, the deploy service account and its roles, and the Workload Identity
trust. Values live in `infra/gcp.env`; this doc records what was set up and why.

> **Scope:** this doc records **§A only** (GCP project + deploy SA + WIF). The
> *rest* of the genesis a replay needs — runtime-SA IAM grants, Firebase auth +
> web config, the public-access org policy, and the GitHub repo settings — is
> consolidated, in run order, in the wave-−1 [`BRINGUP-RUNBOOK.md`](BRINGUP-RUNBOOK.md)
> (authored under OSK-103; validated in [`SEED-REPLAY-DRYRUN.md`](SEED-REPLAY-DRYRUN.md)).

## §A — GCP project genesis

### §A1 — Project identity

- Project `openskeleton-one` (number `476227744481`), region `europe-west2`,
  deliberately separate from any other project (OSK-36). Enabled APIs: run,
  sqladmin, firebasehosting, secretmanager, artifactregistry, firebase,
  identitytoolkit.

### §A2 — Deploy service account (gha-deployer) roles

The keyless deploy (OSK-42) impersonates the deploy SA (gha-deployer) —
concretely `gh-deployer@openskeleton-one.iam.gserviceaccount.com`
(`GCP_DEPLOY_SA` in `infra/gcp.env`) — via GitHub OIDC Workload Identity
Federation, so no service-account key is ever exported. It holds least-privilege
roles, one per thing CI actually deploys:

- `roles/run.admin` — CD backend deploy — rolls out Cloud Run revisions.
- `roles/artifactregistry.writer` — CD image push — pushes the backend container image.
- `roles/iam.serviceAccountUser` — lets the deploy SA act as the Cloud Run runtime SA.
- `roles/firebasehosting.admin` — CD web deploy — publishes the static build to Firebase Hosting.
- `roles/firebaserules.admin` — CD storage-rules deploy — publishes Firebase Storage rules (TM-191).
- `roles/secretmanager.secretAccessor` — reads deploy-time secrets from Secret Manager.
- `roles/cloudsql.client` — connects to Cloud SQL over the connector during deploy/migrations.

The Workload Identity provider trusts ONLY the repo `hellobasitsiddiqui/osk1`
(`GCP_WIF_PROVIDER` in `infra/gcp.env`) — a loose trust condition is a real
security hole (ADR-0002).
