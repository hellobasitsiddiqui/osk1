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

### F5 — `trunk` is a protected branch; can't be deleted after the rename
After reconciling the default to `main`, `git push origin --delete trunk` was rejected (`protected branch hook declined`). The default flipped to `main` fine (AC met), but the stale `trunk` branch remains. Cleaning it up + moving branch protection onto `main` is **OSK-17's** (Branch strategy + protection) scope — not touched here to avoid stepping on that ticket.

### F6 — Board workflow has no "In Review" status
The OSK workflow is **To Do → In Progress → Done** only. The jira-task-claim / DoD protocol says "transition → In Review" after a PR, which is impossible here.
- **Handling used:** keep the ticket **In Progress** and post a `PR: <url>` comment as the review-ready signal; move to **Done** only when a human merges.
- **Fix:** either add an "In Review" status to the OSK workflow, or update the engine protocol/DoD to define the review-ready signal for a 3-state board (In Progress + `PR:` comment) so agents don't try an impossible transition.

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

---

## HITL interventions → lessons (standing directive: capture EVERY human-in-the-loop so we don't repeat it)

Every time a human had to step in this round, the goal is to make that intervention unnecessary next round — by pre-config, automation, or folding the fix into a ticket/seed. Verbatim prompts live in `PROMPT-LOG.md`; the *why + how-to-avoid* live here.

### H1 — Human had to sort out run permissions ("shortcut for uninterrupted run"; the prior session asked how to run with all permissions) — 2026-07-02
The run stalled on per-action permission prompts. **Avoid next round:** pre-configure the run's permissions before "go" — a `.claude/settings.json` allowlist (git, `gh`, the Jira MCP writes) or an explicit bypass mode — so the fleet doesn't pause on every tool call. Decide solo-orchestrator vs one-of-fleet up front (see H4).

### H2 — Jira write blocked by the auto-mode classifier; human had to authorize ("put all your findings on the tickets as comments…") — 2026-07-02 18:01
Posting a `[finding]` comment to a ticket the agent didn't create was denied as an unauthorized external-system write, stalling the **mandatory** bug/finding feedback loop. **Avoid next round:** pre-authorize Jira comment + transition writes for the fleet run (permission rule for `mcp__*atlassian*` writes), since KICKOFF makes finding-feedback and status transitions mandatory — they must not require a human tap each time.

### H3 — Human had to flag the interrupted session ("you can reclaim osk14 the session was interrupted") — 2026-07-02 18:02
OSK-14 sat In Progress with staged-but-uncommitted work in a shared working tree; the orchestrator waited instead of reclaiming. **Avoid next round:** auto-detect a **stale claim** — status In Progress + no pushed branch/PR + claim timestamp older than a threshold ⇒ reclaimable — and reclaim without a human prompt. And don't run two agents in one working tree (see L3); use per-ticket worktrees.

### H4 — Human had to assign the role ("you are the main orchestrator and the owner") — 2026-07-02 18:02
Ambiguity about whether this session was one-of-fleet or the sole driver caused it to play safe and idle. **Avoid next round:** the KICKOFF/run-config should name the run's role explicitly (solo orchestrator+owner vs. one worker of N) so the agent knows whether it may reclaim, rename branches, and drive the whole board.

### H5 — Owner had to approve the commit + `trunk`→`main` rename — 2026-07-02 18:05
The seed repo's default branch (`trunk`) contradicted OSK-14's AC and the engine convention (`main`), forcing an owner decision + a mid-run branch rename. **Avoid next round (fold into the seed):** seed the round repo with default branch **`main`** from the start (or make OSK-14's AC say `trunk`) so the two agree and no rename/decision is needed. This is the "fold the root cause back so a replay can't reintroduce it" rule applied to repo seeding.

### H6 — Standing directive: "keep adding all HITL as lessons so we not repeat them" — 2026-07-02 18:12
This section is now mandatory: append a dated H-entry for **every** human intervention (prompt, approval, correction, unblock) with its avoid-next-round fix. Durable ones (H1/H2 permissions, H3 stale-claim reclaim, H4 role, H5 seed-branch) flow OUT to the engine `docs/agents/agentic-lessons.md`.

---

### L5 — Detect an optional Maven plugin by the pom, never `mvn help:describe -Dplugin=<GAV>`
A CI lint step used `mvnw help:describe -Dplugin=com.diffplug.spotless:spotless-maven-plugin` to decide whether Spotless was configured. That command **resolves the plugin descriptor from Maven Central and exits 0 even when the plugin isn't in the project**, so the "is it present?" guard was always true → it ran `spotless:check` → "No plugin found for prefix 'spotless'" → red CI. Detect optional plugins with `grep <artifactId> pom.xml`. (OSK-12; caught by the PR gate on its own first run — local `mvn verify` never ran the guarded branch. Reinforces "don't trust local green — the CI lane exercises a different path.")

### L6 — Parallelize across surfaces, sequence within a surface (proven on wave-2a)
The dynamic Workflow built OSK-43 (`web/`), OSK-12 (`.github/`), OSK-26 (`docs/`) concurrently in **isolated git worktrees** → 3 PRs in ~3.7 min, zero merge-gate conflicts (disjoint file trees). The backend-code set (OSK-18/23/27/39/49/50/57) all touch `backend/pom.xml` + config, so it must be **sequenced**, not fanned out. Rule: fan-out width = number of *disjoint-file-tree* ready tickets, not the raw ready count. Keep Jira transitions + merges with the orchestrator; give worktree build-agents only build→verify→PR.

---

## Round-1 progress ledger (Sprint 1 — Foundation)
Done (merged to `main`): **OSK-14** (mono-repo) · **OSK-31** (Spring Boot `/health` skeleton) · **OSK-43** (web nginx image) · **OSK-12** (CI PR workflow) · **OSK-26** (ADRs + ARCHITECTURE + SECURITY). Findings on OSK-88 (false-root) + OSK-203 (rootless-by-design). Next: sequence the `backend/` set behind CI; fan out remaining disjoint `.github/` + root tickets.

---

_Living document — append a dated finding/lesson whenever the fleet teaches one; fold the durable ones out via docs PRs. **Every HITL intervention gets an H-entry above (H6).**_
