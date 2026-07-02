# infra

Infrastructure-as-code and deployment configuration — everything needed to run
the app in the cloud, kept out of the application directories.

**Scope:** GCP / Firebase project setup; Cloud Run (backend) and Firebase
Hosting (web); Cloud SQL Postgres; Artifact Registry for images; keyless deploy
auth (GitHub OIDC + service account); Secret Manager wiring; and the CI/CD glue
that ties it together.

**Status:** stub. Individual infra tickets (project creation, deploy auth,
Cloud SQL, Cloud Run deploy, secrets) fill this in. Minimal placeholder for now.

**Secrets & env contract:** how the backend receives its config and its one secret
(`DB_PASSWORD` via Secret Manager), why CI needs **no repository secrets** (keyless
WIF/OIDC), and the required-var → source table — see
[`secrets-and-env.md`](secrets-and-env.md) (OSK-15).

## Deploy service account (gha-deployer) roles

The keyless CI deploy (OSK-42) impersonates the deploy SA (`GCP_DEPLOY_SA` in
`gcp.env`) via GitHub OIDC — no exported key. It holds least-privilege roles, one
per thing CI deploys: `roles/run.admin` (backend revisions),
`roles/artifactregistry.writer` (image push), `roles/iam.serviceAccountUser` (act
as the Cloud Run runtime SA), `roles/firebasehosting.admin` (web build),
`roles/firebaserules.admin` (CD storage-rules deploy — publishes Firebase Storage
rules, TM-191), `roles/secretmanager.secretAccessor` (deploy-time secrets), and
`roles/cloudsql.client` (Cloud SQL over the connector). Full record + rationale:
[GENESIS §A2](../docs/agents/GENESIS.md).
