<!--
  BRINGUP-RUNBOOK.md — the wave-−1 bring-up runbook prescribed by LESSONS-LEARNED
  H20 and authored under OSK-103. This is the ONE ordered checklist a human runs
  ONCE, before saying "go", to provision/authorise every cloud/org/GitHub/settings
  prerequisite the fleet cannot self-serve — so the build never stalls on a human
  wall mid-run.

  This runbook CONSOLIDATES steps already documented elsewhere in the repo; each
  step cites its source of truth. It invents no infrastructure — where a command
  is not committed anywhere (Firebase web config, some console clicks) the step is
  marked HUMAN/console and left as a pointer, not a fabricated command.
-->

# Wave-−1 bring-up runbook (run ONCE before "go")

**Purpose.** Round 1 stalled repeatedly because every human-only cloud/org/GitHub
prerequisite was discovered *mid-run, one at a time* — auth, billing, IAM, org
policy, Firebase config, GitHub settings (LESSONS-LEARNED **H11/H13/H17/H19/H20**;
TICKET-TIMING friction #3). This runbook is the fix H20 prescribed: **do all of
them at t=−1, in order, before the fleet starts.** After this, the only in-run
human touch should be genuine judgement calls — not setup.

**Values source of truth:** [`infra/gcp.env`](../../infra/gcp.env) (non-secret
identifiers). **Legend:** 🧑 = human-only (console click / billing / secret /
settings toggle — the fleet cannot do it); ⚙️ = a `gcloud`/CLI command a human (or
a pre-authorised agent) runs. Every 🧑/⚙️ here targets **`openskeleton-one`
explicitly** — never another project.

Project `openskeleton-one` (number `476227744481`), region `europe-west2`,
runtime SA `476227744481-compute@developer.gserviceaccount.com`, deploy SA
`gh-deployer@openskeleton-one.iam.gserviceaccount.com`.

---

## 0 — Auth & billing (H11)

- [ ] 🧑 **Re-consent gcloud + ADC with the Firebase scope.** A stale token or an
      ADC missing the `firebase` scope silently walls the cloud track. This is the
      single most-common wave-0 stall (H11) — do it first.
      ```bash
      gcloud auth login
      gcloud auth application-default login \
        --scopes=openid,https://www.googleapis.com/auth/cloud-platform,https://www.googleapis.com/auth/firebase
      ```
- [ ] 🧑 **Billing account linked** to `openskeleton-one`. Console → Billing.
      (Round 1 assumed this was missing without checking — it wasn't; verify, don't assume.)

## 1 — GCP project genesis (GENESIS §A1, OSK-36)

- [ ] 🧑 **Create the project** `openskeleton-one` (number `476227744481`),
      region `europe-west2`, deliberately separate from any other project.
- [ ] ⚙️ **Enable the APIs** it needs:
      ```bash
      gcloud services enable \
        run.googleapis.com sqladmin.googleapis.com firebasehosting.googleapis.com \
        secretmanager.googleapis.com artifactregistry.googleapis.com \
        firebase.googleapis.com identitytoolkit.googleapis.com \
        --project=openskeleton-one
      ```
      Source: [`GENESIS.md §A1`](GENESIS.md), [`infra/gcp.env`](../../infra/gcp.env).

## 2 — Keyless deploy identity (GENESIS §A2, OSK-42)

- [ ] 🧑/⚙️ **Create the deploy SA** `gh-deployer@openskeleton-one.iam.gserviceaccount.com`
      and grant its **7 least-privilege roles** (one per thing CI deploys):
      `run.admin`, `artifactregistry.writer`, `iam.serviceAccountUser`,
      `firebasehosting.admin`, `firebaserules.admin`, `secretmanager.secretAccessor`,
      `cloudsql.client`.
- [ ] 🧑/⚙️ **Create the Workload Identity provider** trusting **ONLY** the repo
      `hellobasitsiddiqui/osk1` (a loose trust condition is a real security hole).
      Provider id: `projects/476227744481/locations/global/workloadIdentityPools/github/providers/github-provider`.
      Source: [`GENESIS.md §A2`](GENESIS.md), [`infra/README.md`](../../infra/README.md).
      > No service-account **key** is ever exported — CI federates via OIDC.

## 3 — Cloud SQL + the one runtime secret (OSK-20 / OSK-133)

- [ ] ⚙️ **Provision Postgres + DB + app user, and set the password AND the secret
      from ONE generated value** (TM-131 — they must never drift, or Flyway
      auth-fails on deploy). Run the block verbatim from
      [`infra/cloud-sql.md`](../../infra/cloud-sql.md):
      ```bash
      PROJECT=openskeleton-one; INST=osk-pg; SECRET=osk-db-app-password
      gcloud sql instances create $INST --database-version=POSTGRES_16 \
        --edition=ENTERPRISE --tier=db-f1-micro --region=europe-west2 \
        --storage-size=10 --storage-type=HDD --project=$PROJECT
      gcloud sql databases create osk --instance=$INST --project=$PROJECT
      PW=$(openssl rand -base64 24)
      gcloud sql users create app --instance=$INST --password="$PW" --project=$PROJECT
      gcloud secrets create $SECRET --replication-policy=automatic --project=$PROJECT
      printf '%s' "$PW" | gcloud secrets versions add $SECRET --data-file=- --project=$PROJECT
      unset PW   # never echo the password
      ```
- [ ] ⚙️ **Two runtime-SA IAM grants** that only fail at request time — apply BOTH
      (see [`docs/REPLAY-CHECKLIST.md`](../REPLAY-CHECKLIST.md), the checklist that
      exists to make a replay verify these before declaring bring-up done):
    - `roles/secretmanager.secretAccessor` on `osk-db-app-password` → the backend can read `DB_PASSWORD` (miss it → boot/Flyway auth failure).
    - `roles/iam.serviceAccountTokenCreator` on the runtime SA **itself** → sign Firebase custom tokens for the email-code verify path (miss it → **500 on the first email-code verify**, OSK-133 / TM-234).
      ```bash
      gcloud iam service-accounts add-iam-policy-binding \
        476227744481-compute@developer.gserviceaccount.com \
        --member="serviceAccount:476227744481-compute@developer.gserviceaccount.com" \
        --role="roles/iam.serviceAccountTokenCreator" --project=openskeleton-one
      ```

## 4 — Firebase auth + web config (OSK-92 / OSK-44 / OSK-74 / OSK-38) — closes report gap G2

Do this ONCE at bring-up, **not per-ticket**. The entire web login chain (auth guard,
admin console, profile/history pages, onboarding + terms/completion gates, account
badges) is built and code-complete but stays "dark" (renders the graceful *auth not
configured* state) until the web-app config below is wired. This was the single biggest
mid-run wall in round 1 (LESSONS **H21**) — pulling it to wave-−1 is the fix.

- [ ] 🧑 **Re-auth firebase-tools.** This is a token SEPARATE from the gcloud ADC in §1
      — being "logged in" is not enough; a stale token fails every API call with
      `Your credentials are no longer valid. Please run firebase login --reauth`. Run in
      a REAL terminal (interactive — the browser handoff can't happen in a headless/agent
      shell):
      ```
      npx -y firebase-tools login --reauth
      ```
- [ ] 🧑/🤖 **Register the web app + read its config** (idempotent — reuse the existing
      one if `apps:list` already shows a WEB app; agent can run these once you're re-authed):
      ```
      npx -y firebase-tools apps:list --project <PROJECT>
      npx -y firebase-tools apps:create WEB "OpenSkeleton Web" --project <PROJECT>   # only if none
      npx -y firebase-tools apps:sdkconfig WEB <APP_ID> --project <PROJECT>
      ```
      `apiKey` + `appId` are PUBLIC client identifiers (safe to commit — access is
      enforced by Firebase Auth + backend token verification, not by hiding them).
- [ ] 🤖 **Wire them into [`web/config.js`](../../web/config.js)** `firebase` block
      (`apiKey`, `appId`, `storageBucket`); `authDomain`/`projectId`/`messagingSenderId`
      are known project identifiers, already prefilled. `hosting-deploy.yml` rewrites only
      `apiBaseUrl`, so the firebase block ships **as committed** — `config.js` IS the
      committed home for this config (this resolves the old G2 "delivery gap"). Merge →
      `hosting-deploy.yml` publishes → login is live.
- [ ] 🧑 **Enable the Email/Password sign-in provider** — Firebase console →
      Authentication → Sign-in method → Email/Password (or the Identity Platform admin
      API: `PATCH .../v2/projects/<PROJECT>/config` with `signIn.email.enabled=true`).
      Add Google / other social providers later (OSK-131).

## 5 — Public-access org policy (OSK-51) — closes report gap G3

- [ ] 🧑 **Lift Domain-Restricted-Sharing** so `allUsers` can invoke the service.
      By default `constraints/iam.allowedPolicyMemberDomains` blocks public
      bindings, so the backend/web cannot be made publicly reachable. Set a
      project-scoped policy with `spec.rules[].allowAll: true`
      (`gcloud org-policies set-policy`, org id `103553953969`). Only needed when a
      surface must be public — `deploy.yml` ships `--no-allow-unauthenticated`
      (private) by design. Source: LESSONS-LEARNED **H20** / **OSK-51** (this is the
      only place it was recorded — see report G3).

## 6 — GitHub repo settings (H9 / H19 / OSK-17) — closes report gap G4

- [ ] 🧑 **Enable Dependency graph + Dependabot alerts** — Settings → Code security
      and analysis. Without it the `dependency-review` check is **red on every PR**
      for the whole round (H19/F7) — noise that masks a real dependency issue.
- [ ] 🧑 **Branch protection on `main`** — require the CI checks + PR review, forbid
      direct pushes (OSK-17). The fleet's "Done = merged to `main`" model assumes it.

## 7 — Classifier allow-rules for the fleet (H17)

- [ ] 🧑 Decide up front which high-severity verbs the fleet may self-serve and add
      the **precise** rules to `.claude/settings.json` **before "go"** — a broad
      `Bash(gcloud:*)` does NOT clear the classifier's high-severity gate (H17). The
      safe default is to KEEP these as human commands and paste them when needed:
    - `Bash(gcloud run services update-traffic:*)` — prod traffic routing.
    - `Bash(gcloud iam service-accounts add-iam-policy-binding:*)` — IAM grants (§3).
    - `Bash(gcloud run services add-iam-policy-binding:*)` — public `allUsers` invoker (§5).

---

## Done-check (verify before "go")

- [ ] `gcloud auth application-default print-access-token` succeeds and ADC has the `firebase` scope.
- [ ] `gcloud projects describe openskeleton-one` returns ACTIVE with billing linked.
- [ ] The runtime SA shows **both** bindings — `secretmanager.secretAccessor` (on the secret) and `iam.serviceAccountTokenCreator` (on itself); verify per [`infra/gcp/cloud-run.md`](../../infra/gcp/cloud-run.md).
- [ ] The deploy SA + WIF provider exist and the provider's trust condition names `hellobasitsiddiqui/osk1` only.
- [ ] Firebase Email/Password provider enabled; web-app config captured.
- [ ] GitHub: Dependency graph ON, branch protection on `main` ON.
- [ ] `.claude/settings.json` allow-rules in place (or the human is ready to paste the §7 commands).

With all boxes ticked, the fleet runs the `KICKOFF.md` loop unattended — the only
in-run HITL is genuine judgement, never setup.

---

*Authored under OSK-103 as the wave-−1 runbook LESSONS-LEARNED **H20** prescribed
but that was never written. It consolidates existing IaC/genesis docs — see
[`SEED-REPLAY-DRYRUN.md`](SEED-REPLAY-DRYRUN.md) for the dry-run validation behind
it. Durable version flows OUT to `openskeleton/docs/BRINGUP-LESSONS.md` + the
engine kickoff per KICKOFF's "lessons flow OUT" rule.*
