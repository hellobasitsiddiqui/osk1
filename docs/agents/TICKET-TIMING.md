# Ticket Timing Ledger — Round 1 (osk1)

Per-ticket cycle time (claim → merged-to-`main`) + the blocker/friction that shaped it.
**Start** = claim/`In Progress`; **End** = merged + Done (from the Jira Done-comment timestamp, accurate).
Start times for the parallel waves are approximate (± a couple of min). All times 2026-07-02, BST.

> Purpose (per the human's ask): understand where time went + which blockers to design out next round.

## Cycle times

| Ticket | What | Start~ | End | ~Dur | Mode | Blocker / friction |
|---|---|---|---|---|---|---|
| OSK-14 | mono-repo structure | 18:04 | 18:12 | ~8m | solo | reclaim of interrupted session (HITL); `trunk`→`main` decision (HITL approval) |
| OSK-31 | Spring Boot `/health` skeleton | 18:15 | 18:27 | ~12m | solo | Boot 3.x vs Initializr-default 4.1 decision; TM-126 JSON-pin |
| OSK-43 | web nginx image | 18:50 | 18:59 | ~9m | **wave-2a ∥** | — (clean) |
| OSK-26 | ADRs + ARCHITECTURE + SECURITY | 18:50 | 18:59 | ~9m | **wave-2a ∥** | — (clean) |
| OSK-12 | CI PR workflow | 18:50 | 18:59 | ~9m+ | **wave-2a ∥** | **CI-only bug**: `help:describe` lint detection (caught by its own gate → fix cycle) |
| OSK-37 | multi-stage Dockerfile | 19:03 | 19:12 | ~10m | **wave-2b ∥** | — (host port clash worked around) |
| OSK-24 | CodeQL + dep-review + Dependabot | 19:03 | 19:13 | ~10m | **wave-2b ∥** | **human step**: dependency-review needs "Dependency graph" repo setting (F7/H9) |
| OSK-34 | gitleaks + secret-remediation | 19:03 | 19:13 | ~10m | **wave-2b ∥** | native secret-scanning already on (no-op) |
| OSK-27 | dev/test/prod profiles + validation | 19:30 | 19:39 | ~9m | solo (serial) | **bug caught in live check**: `${VAR}` binds literal → used `${VAR:}` fail-fast |
| OSK-39 | RFC-7807 errors + JSON-only (TM-72) | 19:39 | 19:44 | ~5m | solo (serial) | data-layer mappings deferred → OSK-32 (cross-ticket dep) |
| OSK-50 | full Actuator | 19:59 | 20:01 | ~7m | solo (serial) | info/metrics auth deferred → OSK-28 (no security layer yet) |
| OSK-57 | springdoc / Swagger UI | 20:04 | 20:04 | ~4m | solo (serial) | version resolve (springdoc 2.8.6) |
| OSK-18 | `/api/v1` versioning | 20:05 | 20:13 | ~8m | **wave-3 ∥** | — (clean) |
| OSK-23 | security-headers filter | 20:05 | 20:13 | ~8m | **wave-3 ∥** | avoided spring-security trap; **CSP-vs-Swagger** cross-ticket finding |
| OSK-21 | repo hygiene (LICENSE/CONTRIBUTING/…) | 20:05 | 20:13 | ~8m | **wave-3 ∥** | — (clean) |
| OSK-45 | structured JSON logging + trace IDs | 20:14 | 20:17 | ~4m | solo (serial) | — (Boot native structured logging) |
| OSK-55 | Micrometer → Cloud Monitoring | 20:18 | 20:21 | ~4m | solo (serial) | Cloud Monitoring visibility deferred → OSK-36/GCP |
| OSK-16 | JaCoCo coverage gate | 20:23 | 20:25 | ~4m | solo (serial) | **self-slip**: committed to `main` not a branch (self-caught, reset); required-check = OSK-17 |
| OSK-88 | OpenAPI drift check | 20:26 | 20:30 | ~4m | solo (serial) | was a **false wave-0 root** — waited on OSK-31/57/12 (kickoff finding) |

**Totals:** 19 tickets built + merged in ~2h26m wall-clock (18:04 → 20:30). 3 parallel workflow waves (9 tickets) + 10 solo. Merge gate = me (owner-authorized), so no human-merge stall.

## Where the time / friction actually went (for next round)

1. **The serial spine can't be parallelized** (OSK-14 → OSK-31). True ready-width was 1 until the skeleton merged — ~20m of unavoidable serial before any fan-out. *Improve:* nothing to fix; it's the DAG.
2. **`backend/pom.xml` is the tail bottleneck.** 7 backend tickets shared it → serial, ~4–12m each. *Improve:* pre-partition backend work so deps land in one "pom setup" ticket, freeing the rest to parallelize; or accept the serial tail (it's fast — ~4m each once patterns exist).
3. **Human/settings steps block "Done" on several tickets** (dependency graph, GCP project, branch protection, secrets). *Improve:* create the `human` tickets at planning (human-secret/settings split) so the agent never hits the wall — H9.
4. **Cross-ticket deps missing from the DAG** (OSK-39→32, OSK-50→28, OSK-55→36, OSK-88→31/57/12). Each cost a deferral + a finding, not rework — but a replay agent could stall. *Improve:* add the blocker links (my per-ticket findings list the exact ones).
5. **Bugs caught by verification, not shipped** (OSK-12 lint, OSK-27 `${VAR}`, OSK-16 main-slip). Live checks / the PR gate caught all three pre-merge → ~5–10m each. *Improve:* the fixes are folded into the tickets (replay-safe); the pattern (always assert the real postcondition) is L5.
6. **HITL interventions** (H1–H10): permissions pre-auth, reclaim, role, merge-auth, "don't stop", "use workflow". *Improve:* each is a one-line config/kickoff change for next round (see HITL section in LESSONS-LEARNED).

_Going forward: exact claim + merge timestamps recorded per ticket as they happen._
