# OSK Round 1 — Fleet Kickoff

Read this file, then act. This is the standing kickoff for a build agent starting in this repo.

## Context
- **This repo is the ROUND ARTIFACT:** `~/Projects/osk1` (github `hellobasitsiddiqui/osk1`) — round 1's fleet-built code lands HERE. The product spec + canonical template live in `hellobasitsiddiqui/openskeleton` (do not build there).
- **Jira:** project **OSK** on `https://10xai.atlassian.net` — the round-1 ticket set (open sprint: "OSK Sprint 1 - Foundation").
- **Engine:** the `agents-at-work` plugin, pinned baseline **v0.2.0** — fleet conventions, Definition of Done, and lessons (`docs/agentic-lessons.md`).
- **Jira access:** the engine's bundled MCP handles OAuth on first call. For anything the MCP can't do (attachments, Agile/sprint REST), machine-local creds live at `~/.config/teammarhaba/jira.env` — load with `set -a; . ~/.config/teammarhaba/jira.env; set +a` and **never echo the token**.

## Steps
1. **Install the engine:**
   ```
   /plugin marketplace add hellobasitsiddiqui/agents-at-work
   /plugin install agents-at-work
   ```
   Then read the plugin's `docs/agentic-lessons.md`, and follow its skills (`jira-task-claim`, `write-ticket`, `raise-pr`, `well-commented-code`, `sprint-finish`) + hooks (`never-merge`, `sprint-gate`, `anti-double-build`, `never-commit`).

2. **Find the next READY ticket** in OSK: a Task/Bug in the **open sprint**, unassigned, `labels != "human"`, whose every "is blocked by" blocker is **merged to `main`** (start at `wave-0`). Read it fully — description + the pinned **"Agent execution prompt"** comment.
   - Repo-bootstrap tickets: this repo already exists (README + this file) — **reconcile with existing state, don't recreate**.

3. **Claim it race-safely** (`jira-task-claim`): re-verify it's unclaimed with no open PR, assign yourself, move **To Do → In Progress**, work on a branch/worktree.

4. **Build it:** implementation + tests, well-commented code, all acceptance criteria. Show the commit message first, then commit + push, raise a PR (`raise-pr`), move the ticket **→ In Review**. **NEVER merge.**

5. **Report** the ticket key, the PR link, and the next ready ticket.

Decide with sensible defaults and surface trade-offs in the PR — **don't stop to ask.**

## Bugs feed the replay (mandatory)
When a bug surfaces during this run:
1. File it as a **Bug** issue type and fix it.
2. Then **fold the root-cause requirement back into the originating build ticket's acceptance criteria** — the ticket whose replay would otherwise reintroduce the mistake. Attribute the fix PR to that replay-owning build ticket, and label the Bug **`no-replay`**.
3. **A Done Bug still carrying `replay` is folding debt** — audit at every sprint close and fold + retire.

**Why:** round 2 (osk2) replays from THIS round's tickets — feeding every bug back is what makes each round strictly better.

## Lessons flow OUT of this repo (mandatory)
Durable, cross-project lessons do NOT stay here: fold them into **`openskeleton/docs/BRINGUP-LESSONS.md`** (bring-up/HITL lessons) or the engine's **`docs/agentic-lessons.md`** (fleet-orchestration lessons) via docs PRs. This repo keeps only round-local notes.

## Prompt log (mandatory)
Maintain **`docs/agents/PROMPT-LOG.md`** in THIS repo (create on first run):
- **Append every prompt/instruction you receive from the human**, verbatim, timestamped (`TZ='Europe/London' date`) and numbered, with a one-line note of what you did in response.
- Never paste secrets; record `<redacted>` instead.
- Commit the log with your normal work.
- **Why:** every human intervention is a candidate lesson/automation so the next round needs one prompt fewer.

## Progress reporting (mandatory)
- **Every ~15 minutes**, print a one-line status: the **current time** (always via `TZ='Europe/London' date` — never state a time without running it) + the ticket key, the step, and what's happening (e.g. `14:47 — OSK-7: PR #3 raised, watching CI`).
- Also report **at every state change**: claimed, build done, PR raised, CI green/red, ticket → In Review.
- While waiting on anything long (CI, installs), use a background watch so the wait still produces the 15-min heartbeat — never go silent for more than 15 minutes.

---
*To run the fleet: open a fresh Claude in this directory and say **"Read KICKOFF.md and go."***
