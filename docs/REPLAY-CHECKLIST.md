# Replay / bring-up checklist (cloud IAM)

A fresh project or a from-scratch **replay** stands the cloud up from the IaC in
[`infra/`](../infra/). Most of it is captured in the per-resource docs; this list
pins the **IAM grants that are easy to forget and only fail at runtime** — each one
has bitten prod, so a rebuild must apply them or it 500s on the first request that
exercises the path.

For the full gcloud sequences see [`infra/cloud-sql.md`](../infra/cloud-sql.md) and
[`infra/gcp/cloud-run.md`](../infra/gcp/cloud-run.md). Project `openskeleton-one`,
runtime SA `476227744481-compute@developer.gserviceaccount.com`.

## Runtime service-account grants (apply on every rebuild)

- [ ] **DB secret accessor** — runtime SA gets `roles/secretmanager.secretAccessor`
      on the DB password secret (`osk-db-app-password`), so the backend can read
      `DB_PASSWORD`. Miss it → boot/Flyway auth failure.
      (`infra/cloud-sql.md`)
- [ ] **Token Creator self-grant** — runtime SA gets
      `roles/iam.serviceAccountTokenCreator` **on itself**, so the email-code verify
      path can sign Firebase custom tokens (`FirebaseAuth.createCustomToken` →
      IAM Credentials `signBlob`, no private key on Cloud Run). **Miss it → 500 on the
      first email-code verify** (OSK-133, origin TM-234/TM-272):

      ```bash
      gcloud iam service-accounts add-iam-policy-binding \
        476227744481-compute@developer.gserviceaccount.com \
        --member="serviceAccount:476227744481-compute@developer.gserviceaccount.com" \
        --role="roles/iam.serviceAccountTokenCreator" --project=openskeleton-one
      ```

Both grants land in the same IaC step in `infra/cloud-sql.md`; this checklist only
exists so a replay agent verifies they were actually applied before declaring bring-up
done.
