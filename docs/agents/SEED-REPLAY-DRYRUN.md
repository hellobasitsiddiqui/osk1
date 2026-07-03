<!--
  SEED-REPLAY-DRYRUN.md (OSK-103) — validation report for a from-scratch replay of
  the OSK seed kit. Answers: if a human/agent tried to bootstrap a FRESH repo from
  this kit today, exactly which steps would work, and where would they hit a wall?
  Produced by a DRY RUN — the local bootstrap path was executed in a /tmp scratch;
  every cloud/GitHub mutation was validated by shape + prerequisite only, never run.
-->

# OSK-103 — Seed-kit replay dry-run (validation report)

**What this is.** A dry-run validation that the OSK "seed kit" — the material a
human/agent uses to bootstrap a FRESH repo and run the fleet build — actually
bootstraps a fresh repo end-to-end, and a catalogue of every gap that would stall
a real replay.

**Method.** The **local** bootstrap path was actually executed in a scratch dir
(`/tmp/osk-seed-dryrun`); every **cloud/GitHub-mutating** step was validated by
command *shape* + *prerequisite* only — **nothing was created, deployed, or
pushed**. No GitHub repo, no GCP/Firebase resource, no `teammarhaba` access. The
local `gcloud` is pointed at project `teammarhaba`, which this ticket forbids
touching, so **no live `gcloud` was run at all** — the cloud steps below are
validated statically against the committed IaC docs.

Date: 2026-07-03. Repo state validated: `main` @ `c1fd063`.

---

## 1. What the kit contains (inventory)

The seed kit is spread across five groups of files:

| Group | Files | Role in a replay |
| --- | --- | --- |
| **Round kickoff** | [`KICKOFF.md`](../../KICKOFF.md) | The standing "read this then act" entrypoint — engine install, find/claim/build/PR loop, Jira wiring, mandatory logs. Commits `d8b2a16` ("Seed round-1 kickoff") + `e376c93` ("KICKOFF: Jira access note"). |
| **Cloud genesis record** | [`docs/agents/GENESIS.md`](GENESIS.md) | The one-time GCP setup a round-2 replay reads to recreate the cloud target: project identity + deploy SA + WIF trust. |
| **Replay IAM checklist** | [`docs/REPLAY-CHECKLIST.md`](../REPLAY-CHECKLIST.md) | The runtime-IAM grants that only fail at request time (DB secret accessor + Token-Creator self-grant). |
| **Per-resource IaC** | [`infra/gcp.env`](../../infra/gcp.env), [`infra/cloud-sql.md`](../../infra/cloud-sql.md), [`infra/gcp/cloud-run.md`](../../infra/gcp/cloud-run.md), [`infra/secrets-and-env.md`](../../infra/secrets-and-env.md), [`infra/README.md`](../../infra/README.md) | The concrete `gcloud` sequences + the non-secret identifier source-of-truth + the env/secret contract. |
| **Local dev bootstrap** | [`scripts/dev-setup.sh`](../../scripts/dev-setup.sh), [`Makefile`](../../Makefile), [`docker-compose.yml`](../../docker-compose.yml), [`docker-compose.auth.yml`](../../docker-compose.auth.yml), [`.env.example`](../../.env.example), [`README.md`](../../README.md) | One-command local stack (backend + web + Postgres). |
| **CD workflows** | [`.github/workflows/deploy.yml`](../../.github/workflows/deploy.yml) (backend→Cloud Run), [`hosting-deploy.yml`](../../.github/workflows/hosting-deploy.yml) (web→Firebase Hosting) | The automated delivery path that a replay's first merge triggers. |
| **Lessons trail** | [`docs/agents/LESSONS-LEARNED.md`](LESSONS-LEARNED.md), [`PROMPT-LOG.md`](PROMPT-LOG.md), [`TICKET-TIMING.md`](TICKET-TIMING.md) | Round-1 findings (F1–F8), HITL lessons (H1–H20), and cycle times — the raw material this report leans on. |

Note: there is **no repo-root `CLAUDE.md`** in the kit; the only `CLAUDE.md` on
the machine is the operator's global one (unrelated HMCTS/sscs content).

---

## 2. The prescribed bootstrap sequence (as the kit documents it)

Reconstructed from the files above, a from-scratch replay is meant to run in this
order. Steps are tagged **[H]** = human-only (billing / console click / settings
toggle / secret), **[A]** = agent-runnable, **[CD]** = happens automatically in CI.

**Wave −1 — human bring-up (before "go"):**
1. **[H]** `gcloud auth login` + `gcloud auth application-default login --scopes=…firebase` — re-consent with the Firebase scope (H11).
2. **[H]** GCP account + **billing** linked (H11, OSK-48/OSK-44).
3. **[H]** Create GCP project `openskeleton-one`, region `europe-west2`, enable APIs: run, sqladmin, firebasehosting, secretmanager, artifactregistry, firebase, identitytoolkit (GENESIS §A1, OSK-36).
4. **[H]** Create the **deploy SA** `gh-deployer@…` + its 7 least-privilege roles + the **Workload Identity** provider trusting only `hellobasitsiddiqui/osk1` (GENESIS §A2, OSK-42).
5. **[H]** Provision **Cloud SQL** (`osk-pg` + `osk` DB + `app` user), store the app password in Secret Manager (`osk-db-app-password`), grant the runtime SA `secretAccessor` on it AND `serviceAccountTokenCreator` on itself (`infra/cloud-sql.md`, REPLAY-CHECKLIST, OSK-20/OSK-133).
6. **[H]** Lift the **Domain-Restricted-Sharing org policy** so `allUsers` can invoke the service (OSK-51) — *documented only in H20 prose; see gap G3*.
7. **[H]** **Firebase**: enable the Email/Password auth provider + obtain the web-app config (apiKey/authDomain) the web login needs (OSK-44/OSK-74) — *documented only in H20 prose; see gap G2*.
8. **[H]** GitHub settings: enable **Dependency graph** + Dependabot alerts, add **branch protection** on `main` (H9/H19, OSK-17) — *lessons-prose only; see gap G4*.
9. **[H]** Add the `.claude/settings.json` allow-rules for the specific high-severity verbs the fleet may self-serve (H17).

**Wave 0+ — the fleet build loop (`KICKOFF.md`):**
10. **[A]** Install the engine (`/plugin marketplace add …; /plugin install agents-at-work`), read its lessons + skills.
11. **[A]** Find the next READY ticket in OSK Sprint 1 → claim → build on a branch/worktree → PR → **In Review**. Never merge.
12. **[CD]** On merge to `main`, `deploy.yml` ships the backend to Cloud Run and `hosting-deploy.yml` ships web to Firebase Hosting.

**Local dev (any time, no cloud):**
13. **[A]** `make setup` (writes a git-ignored `.env` via `scripts/dev-setup.sh`) → `make up` (compose: backend + web + Postgres).

---

## 3. What was actually validated (dry-run results)

### 3.1 Local bootstrap — EXECUTED, PASS
Ran in `/tmp/osk-seed-dryrun` against copies of the kit files:

- `scripts/dev-setup.sh` → wrote a correct dev `.env` (POSTGRES_*/DB_*/APP_* defaults), printed the next step. **Idempotent**: a second run left the file untouched ("`.env` already exists"). PASS.
- `docker compose --env-file .env config` → **interpolated cleanly, no errors** (parse-only; no image build, no `up`). PASS.
- Clean-clone realism check: `docker compose config` **without** a `.env` emits `variable is not set, defaulting to a blank string` for every `POSTGRES_*`/`DB_*` var — i.e. a raw `docker compose up` before `dev-setup.sh` would start Postgres with a blank user/db. This is **correctly guarded**: `make up` depends on `make setup`, and both the README "Run the whole stack" block and the compose-file header instruct running `dev-setup.sh` first. No gap — just confirming the ordering is load-bearing.
- Toolchain present locally: `docker`, `java 21`, `mvn`, `jq`, `node`. **`firebase` CLI is MISSING** (see gap G5).

### 3.2 CD workflows — SHAPE VALIDATED (not run)
- `deploy.yml` deploys by **immutable digest**, routes 100% traffic to the new revision **by name** with `--to-revisions=…=100` (NOT the historically-buggy `--to-latest`, L10 — that bug is fixed), and the verify step asserts the serving revision + image digest match (TM-131 stale-revision guard). Deploys `--no-allow-unauthenticated` (private; public exposure deferred to OSK-51). Shape correct.
- `.gitignore` blocks `.env`, `.env.*`, `*-credentials.json`, `service-account*.json`, `application_default_credentials.json`, `*.pem`, `*.p12` — the keyless/no-secrets rule is enforced by pattern. PASS.
- `secrets-and-env.md` AC-1 claim ("no GitHub Actions repo secrets required") holds: the only `${{ secrets.* }}` in any workflow is the auto-provided `GITHUB_TOKEN`. PASS.

### 3.3 Cloud IaC — SHAPE VALIDATED (not run; no live `gcloud`)
The `gcloud` sequences in `infra/cloud-sql.md` and `infra/gcp/cloud-run.md` are
well-formed and self-consistent with `infra/gcp.env` (project, instance
connection name, SA emails, secret name all agree). The TM-131 single-source
password step and the two easy-to-miss IAM grants (secret accessor + token
creator self-grant) are present and cross-linked from the REPLAY-CHECKLIST. No
shape defects found in the committed commands.

---

## 4. Gaps found (what would stall a real replay)

Ordered by impact. **G1 is the keystone gap** and is the one this ticket's
background points at.

### G1 — The wave-−1 "bring-up runbook" prescribed by H20 does NOT exist
[`LESSONS-LEARNED.md` H20](LESSONS-LEARNED.md) concludes that all the human-only
cloud/org/settings prerequisites should be consolidated into **one wave-−1
bring-up runbook the human runs once before "go."** That runbook was **never
authored** — H20 records the *intent* ("The fix (flows OUT → engine kickoff): a
single wave-−1 'bring-up' runbook") but no such file exists in the repo.

The closest artifacts are all **partial**:
- `docs/REPLAY-CHECKLIST.md` is titled "Replay / bring-up checklist" but its own
  body scopes it to **just two runtime IAM grants** ("this list pins the IAM
  grants that are easy to forget and only fail at runtime").
- `GENESIS.md` covers **only §A** (GCP project + deploy SA + WIF) — see G6.
- `KICKOFF.md` covers the **Jira/engine** loop but **nothing cloud**.

**Effect on a replay:** exactly the round-1 failure mode — the fleet discovers
each human wall *mid-run, one at a time* (auth, billing, IAM, org policy,
Firebase config, GitHub settings), the "death by a thousand walls" H20 was
written to prevent. There is no single ordered checklist a human can run at t=0.

**Fix (done in this PR):** authored [`docs/agents/BRINGUP-RUNBOOK.md`](BRINGUP-RUNBOOK.md)
— the missing wave-−1 runbook, consolidating the already-documented steps into
one ordered, copy-paste checklist with every human-only step flagged; and
cross-linked it from `GENESIS.md`, `REPLAY-CHECKLIST.md`, `KICKOFF.md`, and the
H20 entry so the loop is closed.

### G2 — Firebase **web-app config** (apiKey/authDomain) is documented nowhere
H20 lists "Firebase project + web-app config (apiKey/authDomain) needed before
any web login can be built (OSK-44/OSK-74)" as a bring-up input. But there is
**no Firebase web-SDK config anywhere in the repo** — `web/config.js` exposes
only `apiBaseUrl`/`buildSha`/`alert`; a grep for `apiKey`/`authDomain`/
`firebaseConfig` across `web/` returns nothing. So a replay that reaches the web
login ticket has **no documented source** for where the Firebase web config
comes from or how it reaches the built site (committed? injected by
`hosting-deploy.yml` like `apiBaseUrl` is?). **Fix:** captured as a step in the
new runbook (G1) marked "human — Firebase console → Project settings → Web app";
the *delivery mechanism* (commit vs deploy-time inject) still needs an owning
ticket — recommended below.

### G3 — The **Domain-Restricted-Sharing org policy** step (OSK-51) is in no IaC doc
Making the service publicly reachable requires lifting the
`constraints/iam.allowedPolicyMemberDomains` org policy (`allUsers` is blocked by
default). `deploy.yml` correctly deploys **private** and defers public exposure
to "OSK-51", but the **org-policy command itself lives only in H20 prose** inside
`LESSONS-LEARNED.md` — no `infra/` doc or checklist records it. A replay that
needs public access has no durable step. **Fix:** added to the new runbook (G1)
with the `gcloud org-policies set-policy` shape from H20; flagged human/IAM.

### G4 — GitHub **repo-settings** steps are lessons-prose, not a checklist
Dependency-graph + Dependabot alerts (H9/H19) and branch protection on `main`
(OSK-17) are human/settings toggles that blocked round-1 (dependency-review ran
red on *every* PR for the whole round). They're described only inside
`LESSONS-LEARNED.md` and mentioned in `TICKET-TIMING.md` friction #3 — there is
no wave-−1 GitHub-settings checklist. **Fix:** added to the new runbook (G1).

### G5 — `firebase` CLI is an **undocumented prerequisite**
`hosting-deploy.yml` uses `firebase-tools`, and its header gives local rollback
one-liners (`firebase hosting:rollback …`) — but the README "Prerequisites"
lists only **JDK 21 + Docker**. The `firebase` CLI is absent from the local
toolchain (confirmed: `firebase` not on PATH). CD installs it in Actions so the
*pipeline* is fine, but a human validating/rolling back a web deploy locally hits
a missing-binary wall. **Fix:** noted as a prereq in the new runbook (G1);
low-severity so not added to README in this PR to avoid scope creep.

### G6 — `GENESIS.md` is incomplete for its own stated purpose
GENESIS says it is "the doc a round-2 replay reads to recreate the cloud target
from scratch," but it only contains **§A** (project identity + deploy SA + WIF).
It has **no §B** for: the *runtime* SA IAM (lives only in `cloud-sql.md`/
`cloud-run.md`), Firebase **Auth provider** enablement (Email/Password — only a
parenthetical "pending" in `gcp.env`), the org policy (G3), the Firebase web
config (G2), or the GitHub settings (G4). As a standalone genesis record it
under-delivers. **Fix (this PR):** added a "see the bring-up runbook for the rest
of genesis" pointer to GENESIS rather than duplicating; the runbook is now the
single ordered source.

### G7 — `.env.example` dev DB values are internally inconsistent (minor)
`.env.example` sets `DB_USERNAME=osk1` and `DB_URL=…/osk1`, but its own inline
comment says *"Database username. Prod default: app"*, `dev-setup.sh` +
`docker-compose.yml` use `osk`, and `secrets-and-env.md` documents the prod user
as `app`. The value `osk1` (the repo name) is used as a DB user/db **nowhere
else** and matches neither the dev nor prod contract. It's a placeholder-only
file (never `cp`-d — TM-127), so this is cosmetic, but the stray `osk1` is
confusing. **Fix (this PR):** aligned the placeholder to match its own comments —
`DB_USERNAME=app` (prod default) and `DB_URL=…/osk` (dev default).

---

## 5. Human-only bootstrap steps (a replay cannot self-serve these)

Distilled for the kickoff checklist — none are agent-runnable:

1. `gcloud auth login` + ADC re-consent **with the `firebase` scope** (H11).
2. GCP **billing** account linked (H11).
3. **Create the GCP project** + enable the 7 APIs (OSK-36) — an agent cannot create a project.
4. **Firebase console**: enable Email/Password auth + read the **web-app config** (apiKey/authDomain) (G2/OSK-44).
5. Lift the **Domain-Restricted-Sharing org policy** for `allUsers` (G3/OSK-51) — an IAM/org-policy change.
6. GitHub **Settings**: Dependency graph + Dependabot alerts + branch protection on `main` (G4/OSK-17).
7. Any **prod traffic-routing / IAM-escalation / public-exposure** verb the fleet is *not* pre-granted stays a human command (H17) — the classifier gates these even with a broad `gcloud:*` allowlist.
8. The **one runtime secret** (`osk-db-app-password`) is set from a generated value at Cloud SQL provisioning (never committed) — a human/console step (`infra/cloud-sql.md`).

---

## 6. Recommended follow-up tickets (beyond this PR's trivial fixes)

- **Own the Firebase web-config delivery** (G2): decide commit-vs-deploy-time-inject and document it, mirroring how `hosting-deploy.yml` rewrites `apiBaseUrl`. (New ticket; touches `web/config.js` + `hosting-deploy.yml` — out of scope for a docs PR.)
- **Promote the org-policy step to `infra/`** (G3): a short `infra/org-policy.md` with the tested `gcloud org-policies set-policy` command, referenced by the runbook. (New ticket; needs a live apply to verify.)
- **README prereqs** (G5): add `firebase` CLI (+ `gcloud`) to the "Prerequisites" list. (Trivial; deferred to keep this PR docs-agents-scoped.)
- **Fold G1/G2/G3/G4 back into the seed** per KICKOFF's "bugs feed the replay" rule: the durable runbook should flow OUT to `openskeleton/docs/BRINGUP-LESSONS.md` + the engine kickoff so round-2's seed ships it (this repo keeps only the round-local copy authored here).

---

*Produced by OSK-103 (seed-replay dry-run). The local bootstrap was executed in a
/tmp scratch and passed; all cloud/GitHub steps were shape-validated only — no
resources were created and `teammarhaba` was never touched.*
