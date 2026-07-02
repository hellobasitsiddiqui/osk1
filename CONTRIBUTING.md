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

## Code style

Java is auto-formatted by **Spotless** (palantir-java-format). The same rule runs
locally, in the pre-commit hook, and in CI, so they can never disagree.

- Format everything: `cd backend && ./mvnw spotless:apply`
- CI/`./mvnw verify` runs `spotless:check` and fails on any unformatted file.
- Install the pre-commit hooks (recommended): `pip install pre-commit && pre-commit install`.
  They run Spotless + whitespace hygiene on staged files and block a bad commit.

## Supply-chain: SHA-pinned Actions & SBOM

We harden the CI/CD supply chain in two ways (OSK-40): every third-party GitHub
Action is pinned to an immutable commit SHA, and every backend build emits a
CycloneDX Software Bill of Materials (SBOM).

### Why Actions are pinned to a SHA (not a tag)

A tag like `@v4` is a *moving* pointer: whoever controls the action's repo can
re-point it at new code at any time. If a maintainer's account or the tag is
compromised, `@v4` can silently start running attacker code inside our CI — with
our repo checkout and (on `main`) our GCP credentials in scope. A full 40-char
commit SHA is immutable: it always resolves to the exact tree we reviewed, so a
re-pointed tag can't change what runs. Every `uses:` in `.github/workflows/*.yml`
therefore references a SHA, with a trailing `# vX.Y.Z` comment recording the
human-readable version the SHA corresponds to:

```yaml
uses: actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683 # v4.2.2
```

(Local `uses: ./…` actions and `docker://` refs are exempt — they aren't fetched
from a mutable upstream tag.)

### How to update a pinned Action

1. Find the SHA the new tag points to (core GitHub API — cheap, no `gh search`):
   ```bash
   gh api repos/<owner>/<repo>/commits/<new-tag> --jq .sha
   # e.g. gh api repos/actions/checkout/commits/v4.2.3 --jq .sha
   ```
2. Replace the old 40-char SHA with the new one in every workflow that uses it.
3. Update the trailing `# vX.Y.Z` comment to the new version so it stays honest.
4. Verify nothing slipped back to a moving tag — this must print nothing:
   ```bash
   grep -rnE "uses: [^.].*@[^ ]" .github/workflows/ | grep -vE "@[0-9a-f]{40}"
   ```

### The SBOM: what's in my build?

The backend build produces a CycloneDX SBOM via the
[`cyclonedx-maven-plugin`](https://github.com/CycloneDX/cyclonedx-maven-plugin)
declared in `backend/pom.xml`. Its `makeAggregateBom` goal is bound to the
`package` phase, so any `./mvnw package` or `./mvnw verify` writes:

- `backend/target/bom.json` — CycloneDX **JSON** (the machine-readable one)
- `backend/target/bom.xml` — the same BOM in CycloneDX XML

CI uploads these on every run as the **`sbom-cyclonedx`** build artifact (see the
"Upload CycloneDX SBOM" step in `.github/workflows/ci.yml`) — download it from
the Actions run summary.

The SBOM is the authoritative "what's actually in this build?" list: every
resolved dependency with its group, artifact, version and license. Use it for
**CVE triage** — when an advisory drops for some library, grep the JSON for the
package/version instead of guessing from the POM:

```bash
# Is a given dependency (and which version) in the build?
jq -r '.components[] | "\(.group):\(.name)@\(.version)"' backend/target/bom.json | sort
jq -r '.components[] | select(.name=="jackson-databind") | .version' backend/target/bom.json
```

Because it's the *resolved* graph, it includes transitive dependencies the POM
never names directly — which is exactly where supply-chain CVEs tend to hide.
