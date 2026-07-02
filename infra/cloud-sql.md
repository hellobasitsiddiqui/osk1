# Cloud SQL provisioning (OSK-20)

Documented `gcloud` IaC for the production Postgres. Region `europe-west2`, project
`openskeleton-one`. The backend connects via the **socket-factory connector** (no
public IP); Cloud Run mounts the instance socket and injects the password from
Secret Manager.

```bash
PROJECT=openskeleton-one; INST=osk-pg; SECRET=osk-db-app-password

# Instance (small, single-zone) + database
gcloud sql instances create $INST --database-version=POSTGRES_16 \
  --edition=ENTERPRISE --tier=db-f1-micro --region=europe-west2 \
  --storage-size=10 --storage-type=HDD --project=$PROJECT
gcloud sql databases create osk --instance=$INST --project=$PROJECT

# TM-131 — app-user password and the secret are set from ONE generated value in the
# same step, so they can never drift (drift => Flyway auth failure => stale-code deploy).
PW=$(openssl rand -base64 24)
gcloud sql users create app --instance=$INST --password="$PW" --project=$PROJECT
gcloud secrets create $SECRET --replication-policy=automatic --project=$PROJECT
printf '%s' "$PW" | gcloud secrets versions add $SECRET --data-file=- --project=$PROJECT
unset PW

# Cloud Run runtime SA reads the secret
gcloud secrets add-iam-policy-binding $SECRET \
  --member="serviceAccount:476227744481-compute@developer.gserviceaccount.com" \
  --role=roles/secretmanager.secretAccessor --project=$PROJECT

# OSK-133 — the SAME runtime SA must also be able to sign Firebase *custom tokens*: the
# email-code verify path mints one via FirebaseAuth.createCustomToken. On Cloud Run the
# Admin SDK uses ADC (no private key), so it signs via the IAM Credentials signBlob API —
# which requires the runtime SA to hold Token Creator ON ITSELF (a self-binding). Without
# it, custom-token signing is denied => 500 on the first email-code verify. (Firebase
# custom-token signing for the email-code auth path; origin TM-234/TM-272.)
gcloud iam service-accounts add-iam-policy-binding \
  476227744481-compute@developer.gserviceaccount.com \
  --member="serviceAccount:476227744481-compute@developer.gserviceaccount.com" \
  --role="roles/iam.serviceAccountTokenCreator" --project=$PROJECT
```

**Connection (prod)**: `application-prod.yml` uses
`jdbc:postgresql:///${DB_NAME}?cloudSqlInstance=${INSTANCE_CONNECTION_NAME}&socketFactory=com.google.cloud.sql.postgres.SocketFactory`;
the Cloud Run deploy (OSK-47) adds `--add-cloudsql-instances=$INSTANCE_CONNECTION_NAME`
and `--set-secrets=DB_PASSWORD=$SECRET:latest`.
