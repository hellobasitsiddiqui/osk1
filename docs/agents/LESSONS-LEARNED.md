# Round 1 — Findings & Lessons Learned (osk1)

Round-local notes from fleet runs in this repo. Per `KICKOFF.md`, **durable cross-project lessons flow OUT** to the engine's `docs/agentic-lessons.md` (fleet orchestration) or `openskeleton/docs/BRINGUP-LESSONS.md` (bring-up/HITL) via docs PRs — this file keeps the round-local record and flags which items should be folded out.

Author: `claude-opus-4.8` (osk1 autonomous fleet / orchestrator). First entry: 2026-07-02.

---

## Ticket-level findings (also posted as comments on the tickets)

### F1 — OSK-88 is a false wave-0 root (replay ordering defect) → posted on OSK-88
`OSK-88` (OpenAPI spec drift check) is labelled `wave-0` and its AC says *"Blocked by: none (springdoc already produces the spec in non-prod)"*. That was true in the TM origin (TM-135) where a backend already existed, but it is **false in a from-scratch replay**: this fresh repo has no `backend/`, no springdoc, and no CI workflow, so there is nothing to generate `openapi.json` from and nothing to attach a drift check to.
- **Impact:** a find-ready query surfaces OSK-88 as ready; an agent that claims it either fails or bootstraps a backend and collides with OSK-31/OSK-57.
- **Fix:** link OSK-88 *is blocked by* **OSK-31** (skeleton), **OSK-57** (springdoc), **OSK-12** (PR workflow); change AC "Blocked by" from `none` → those three; drop `wave-0` (it's ~wave-3).
- **Status:** finding comment posted; links NOT mutated from here (Blocks direction inverts easily + no delete-link API — planner should add via UI with a read-back).

### F2 — OSK-203 is rootless *by design*, not early-ready work → posted on OSK-203
`OSK-203` (automated foundation smoke) has no blocker links, so a naive find-ready query lists it as a wave-0 root. That's intentional — it's the sprint-close automated-test gate, created at planning to be visible from day 1. It can only run once the whole foundation is built + deployed.
- **Fix (optional):** either link it *is blocked by* the deploy tickets (OSK-47, OSK-13), **or** make the find-ready filter exclude `automated-test`/`e2e` gate tickets. Leaving it rootless is fine if the filter excludes gates.

---

## Round-local / setup findings

### F3 — Engine version mismatch: KICKOFF pins v0.2.0, marketplace ships v0.1.0
`KICKOFF.md` says *"the `agents-at-work` plugin, pinned baseline **v0.2.0**"*, but `hellobasitsiddiqui/agents-at-work` only offered **v0.1.0**, which is what installed. The v0.1.0 skills/docs/hooks were used for this run.
- **Fix:** either tag/publish v0.2.0 in the marketplace, or correct the KICKOFF pin to the version that actually exists so the run isn't operating off a phantom baseline.

### F4 — Repo default branch is `trunk`, but OSK-14 AC + engine convention say `main`
This repo was seeded with default branch `trunk` (`origin/HEAD → trunk`), but OSK-14's AC requires *"The default branch is `main`"* and the engine's own lesson says *"`main` is the integration branch"* — and the fleet's "Done = merged to **main**" language assumes `main`.
- **Decision (as owner):** reconcile to `main` while building OSK-14 (rename `trunk` → `main`, retarget default), OR keep `trunk` and amend the convention. Recorded here; resolution noted in the OSK-14 PR.

---

## Durable lessons to fold OUT → engine `docs/agentic-lessons.md`

### L1 — A rootless replay ticket that assumes prior-round artifacts silently breaks a from-scratch replay
Give replay tickets **explicit blockers** even when the origin round didn't need them (the origin already had the artifact, so it looked rootless). Distinguish a *true* root (nothing it needs pre-exists) from an *origin-artifact* root (it only looked rootless because a prior round built its prerequisites). See F1.

### L2 — Compute the *true* ready-width (excluding false-roots) before sizing the fleet
At wave-0 here the nominal roots were OSK-14, OSK-88, OSK-203 — but only **OSK-14 is genuinely buildable**; OSK-88 and OSK-203 are false-roots (F1, F2). So the real ready-width is **1**, not 3. Launching a second agent at wave-0 gives it no collision-free work. **The mono-repo bootstrap ticket is an exclusive wave-0 lock:** it creates the entire repo skeleton (all top-level dirs, root README, `.gitignore`), so *anything* built in parallel collides at the merge gate. Sequence everything behind it; don't co-schedule.

### L3 — Two agents in the *same working directory* collide; the resume/relaunch flow can spawn a duplicate
A `/resume` that reports "still running as a background agent" plus a fresh "go" in the same dir yields two agents sharing one working tree and one Jira board. The Jira status-lock handles board-level races, but a shared working tree does not — use per-ticket **git worktrees**, and confirm whether a prior agent is live before launching another (or explicitly reclaim its interrupted ticket).

### L4 — (operational) Jira MCP search blows the token cap on wide sprints
`searchJiraIssuesUsingJql` with `description`/`comment`/`issuelinks` across a ~40-ticket sprint returns 150k–390k chars and is rejected. Request **minimal fields**, let it spill to the results file, and `jq` it (e.g. compute roots via `select(no inward "is blocked by")`). Readiness can't be done in JQL — filter linked-issue status client-side.

---

_Living document — append a dated finding/lesson whenever the fleet teaches one; fold the durable ones out via docs PRs._
