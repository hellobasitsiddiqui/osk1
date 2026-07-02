# Prompt Log — osk1 (OSK Round 1)

Verbatim record of every human prompt/instruction received, per KICKOFF.md.
Timestamps via `TZ='Europe/London' date`. Secrets recorded as `<redacted>`.

---

## Session 1 — 2026-07-02

**1.** `2026-07-02 17:44 BST` — *(session config: `/model` → Opus 4.8 (1M context); `/effort` → max)* — "shortcut for uninterrupted run"
> Read KICKOFF.md + repo state, began setup batch; user interrupted before completion.

**2.** `2026-07-02 17:45 BST` — "wait"
> Stopped all tool use immediately, stood by.

**3.** `2026-07-02 17:46 BST` — "how to make this session run with all permissions without me allowing every single thing"
> Explained permission options: Shift+Tab (auto-accept edits), relaunch with `--dangerously-skip-permissions` (full bypass, recommended for uninterrupted run), and scoped allowlist via `/permissions` / `/fewer-permission-prompts` / `.claude/settings.json`.

**4.** `2026-07-02 17:46 BST` — "clear"
> Acknowledged; confirmed ready to start on the go signal.

**5.** `2026-07-02 17:47 BST` — "Read KICKOFF.md and go"
> Began KICKOFF execution: established time, checked engine/creds state, cloned `agents-at-work` for its conventions, created this prompt log. Proceeding to find + claim the next READY ticket in OSK Sprint 1.

## Session 2 — 2026-07-02 (reclaiming orchestrator/owner session)

> Note: Session 1 above was interrupted. This session resumed in the same working tree, installed the engine, mapped the OSK sprint DAG, and found the only genuinely-ready root (OSK-14) still claimed by the interrupted session. See `LESSONS-LEARNED.md` H-entries for the HITL lessons behind each prompt below.

**6.** `2026-07-02 18:01 BST` — "put all your findings on the tickets as comments and in lesson learned md"
> Posted the OSK-88 (replay mis-order) and OSK-203 (rootless-by-design) findings as ticket comments, and created `docs/agents/LESSONS-LEARNED.md`. (Also authorized the previously-blocked Jira write — HITL lesson H2.)

**7.** `2026-07-02 18:02 BST` — "you can reclaim osk14 the session was interrupted"
> Reclaimed OSK-14 (claim comment), reviewed the interrupted session's staged work, AC-verified it. HITL lesson H3 (auto-detect stale claims).

**8.** `2026-07-02 18:02 BST` — "you are the main orchestrator and the owner"
> Took the sole orchestrator/owner role — enables reclaiming, branch rename, driving the whole board. HITL lesson H4 (name the role at run start).

**9.** `2026-07-02 18:05 BST` — (AskUserQuestion answer) "Commit + rename to main"
> Committed OSK-14, reconciled default branch `trunk` → `main`, pushed, opened PR #1, kept ticket In Progress with a `PR:` comment (no In-Review status on this board — F6). HITL lesson H5 (seed repo default should be `main`).

**10.** `2026-07-02 18:12 BST` — "make sure you keep adding all hitl as lessons so we not repeat them"
> Added the "HITL interventions → lessons" section (H1–H6) to `LESSONS-LEARNED.md` as a standing directive; logged this instruction. HITL lesson H6.
