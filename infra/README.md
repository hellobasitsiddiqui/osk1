# infra

Infrastructure-as-code and deployment configuration — everything needed to run
the app in the cloud, kept out of the application directories.

**Scope:** GCP / Firebase project setup; Cloud Run (backend) and Firebase
Hosting (web); Cloud SQL Postgres; Artifact Registry for images; keyless deploy
auth (GitHub OIDC + service account); Secret Manager wiring; and the CI/CD glue
that ties it together.

**Status:** stub. Individual infra tickets (project creation, deploy auth,
Cloud SQL, Cloud Run deploy, secrets) fill this in. Minimal placeholder for now.
