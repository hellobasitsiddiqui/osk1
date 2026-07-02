<!--
  CONTRIBUTING.md — how to make changes to OpenSkeleton (osk1).

  Purpose: give any contributor (human or agent) the one place that documents
  our branching model, commit format, and PR review flow, so changes land
  consistently and traceably. Keep this in sync with the workflow the fleet and
  the repo settings actually enforce.
-->

# Contributing to OpenSkeleton

Thanks for contributing. This repo is **trunk-based**: `main` is the single
integration branch. Everything below keeps history clean and every change
traceable to a ticket.

## Workflow at a glance

1. Pick up an `OSK` ticket.
2. Branch off `main`.
3. Make the change (scoped to that ticket, and to one surface where possible).
4. Open a reviewed PR back into `main`.
5. A human/owner reviews and merges — **contributors and agents never merge.**

## Branching

- Always branch from the latest `origin/main`.
- Name branches `<type>/<OSK-key>-<short-slug>`, where `<type>` is one of:
  - `feature/` — new functionality (e.g. `feature/OSK-28-firebase-auth`)
  - `fix/` — a bug fix (e.g. `fix/OSK-42-null-health-body`)
  - `chore/` — housekeeping: docs, tooling, config (e.g. `chore/OSK-21-repo-hygiene`)
- One ticket per branch. Keep branches short-lived and rebase on `main` rather
  than letting them drift.

## Commits

- Format: `OSK-XXXX <imperative subject>` — e.g. `OSK-21 Add MIT LICENSE and repo hygiene docs`.
- Subject in the imperative mood ("Add", "Fix", "Remove"), no trailing period,
  ideally under ~72 characters. Add a body if the *why* is not obvious.
- Every commit references the ticket it belongs to.
- **No AI/assistant attribution** in commit messages or file contents.
- Never commit secrets or `.env` files — see [`.env.example`](.env.example) and
  the root [`.gitignore`](.gitignore).

## Pull requests

- Open the PR against `main` using the
  [pull request template](.github/PULL_REQUEST_TEMPLATE.md); fill in the summary,
  the linked `OSK` ticket, and how you verified the change.
- Make sure CI is green and the change is verified locally before requesting review.
- At least one review is required. The matching owner in
  [`.github/CODEOWNERS`](.github/CODEOWNERS) is requested automatically.
- Address review comments by pushing follow-up commits to the same branch.

## Merging

- **Do not merge your own PR, and agents never merge.** A human/owner performs
  the merge once the PR is approved and checks pass. This keeps a clear,
  human-in-the-loop gate on what lands on `main`.

## Reporting issues

Use the issue templates for [bugs](.github/ISSUE_TEMPLATE/bug_report.md) and
[feature requests](.github/ISSUE_TEMPLATE/feature_request.md).

See [`README.md`](README.md) for the repository layout and how to build and run
each surface.
