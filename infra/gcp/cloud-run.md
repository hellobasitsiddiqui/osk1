# Cloud Run backend — runtime service account & IAM (OSK-133)

The backend runs on Cloud Run (see [ADR-0002](../../docs/adr/0002-hosting-cloud-run-firebase.md)).
It executes as a **runtime service account** (distinct from the keyless *deploy* SA
`gh-deployer@…` that CI impersonates via GitHub OIDC — see [`gcp.env`](../gcp.env)).

- **Project:** `openskeleton-one`
- **Runtime SA (Cloud Run backend):** `476227744481-compute@developer.gserviceaccount.com`
  (the project's default compute SA — the identity the running service uses via ADC)

## IAM the runtime SA needs

The runtime SA holds no private key — on Cloud Run it authenticates via **ADC**. So
anything that would normally sign with a key instead calls a Google API, and each such
call needs an explicit grant. Two are required for the app to work on a fresh project:

| Role | On | Why |
| --- | --- | --- |
| `roles/secretmanager.secretAccessor` | the DB password secret (`osk-db-app-password`) | read the Postgres app-user password injected as `DB_PASSWORD` (see [`cloud-sql.md`](../cloud-sql.md)) |
| `roles/iam.serviceAccountTokenCreator` | **itself** (self-binding) | sign Firebase **custom tokens** for the email-code auth path |

### Token Creator self-grant (the one this doc exists for)

The email-code **verify** path mints a Firebase custom token
(`FirebaseAuth.createCustomToken`). With no private key on Cloud Run, the Admin SDK
signs it via the IAM Credentials **`signBlob`** API, which requires the runtime SA to
hold **`roles/iam.serviceAccountTokenCreator` on itself**. Missing this binding →
signing is denied → **500 on the first email-code verify** (found in prod, TM-234/TM-272;
same family as the DB secret-accessor grant).

Baked into the IaC sequence alongside the secret-accessor grant in
[`cloud-sql.md`](../cloud-sql.md); the concrete command for this project:

```bash
gcloud iam service-accounts add-iam-policy-binding \
  476227744481-compute@developer.gserviceaccount.com \
  --member="serviceAccount:476227744481-compute@developer.gserviceaccount.com" \
  --role="roles/iam.serviceAccountTokenCreator" --project=openskeleton-one
```

Verify:

```bash
gcloud iam service-accounts get-iam-policy \
  476227744481-compute@developer.gserviceaccount.com --project=openskeleton-one \
  --format='table(bindings.role, bindings.members)'
# expect a binding: roles/iam.serviceAccountTokenCreator -> serviceAccount:476227744481-compute@developer.gserviceaccount.com
```

> Replay note: a fresh project/rebuild MUST apply this grant, or the first email-code
> verify 500s. It is on the [replay checklist](../../docs/REPLAY-CHECKLIST.md).
