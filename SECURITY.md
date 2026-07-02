<!--
  SECURITY.md — the coordinated vulnerability-disclosure policy for OpenSkeleton
  (osk1). GitHub surfaces this file in the repo's "Security" tab and links to it
  from the "Report a vulnerability" prompt, so it is the front door for anyone
  who finds a security issue. Keep the contact and process here current.
-->

# Security Policy

OpenSkeleton (`osk1`) is a full-stack application skeleton. We take security
seriously — both of this codebase and of the forks that inherit it — and we
welcome coordinated disclosure of vulnerabilities.

## Supported versions

`osk1` is round-1 fleet-built code and follows a rolling model: only the current
`main` branch is supported. Fixes land on `main`; there are no back-ported
maintenance branches. If you fork this skeleton, security ownership of the fork
is yours from that point on.

| Version | Supported |
| --- | --- |
| `main` (latest) | Yes |
| Any tag / older commit | No — please reproduce against `main` first |

## Reporting a vulnerability

**Please do not open a public GitHub issue, pull request, or discussion for a
security problem** — that would disclose it to everyone before a fix exists.

Instead, use **either** private channel:

1. **GitHub Security Advisories (preferred).** On the repository, go to the
   **Security** tab → **Report a vulnerability** (GitHub Private Vulnerability
   Reporting). This opens a private advisory visible only to the maintainers and
   you, and keeps the whole exchange in one place.
2. **Email:** send the report to **basit@10xai.co.uk** with a subject beginning
   `[SECURITY] osk1`.

Please include enough for us to reproduce and triage:

- affected surface (`backend`, `web`, `webview`, `android`, `infra`) and the
  commit / branch you tested against;
- a clear description of the issue and its impact (what an attacker gains);
- step-by-step reproduction, proof-of-concept, or a failing request/payload;
- any suggested remediation, if you have one.

If you need to send sensitive material, say so in your first message and we will
arrange an encrypted channel — **do not** attach secrets, tokens, or live
credentials to a plaintext email.

## Our commitment / what to expect

- **Acknowledgement** within **3 business days** of your report.
- An initial **triage and severity assessment** within **10 business days**,
  after which we will keep you updated on remediation progress.
- We practice **coordinated disclosure**: we will agree a disclosure timeline
  with you, aiming to ship a fix before any public detail is published, and we
  will **credit you** in the advisory unless you prefer to remain anonymous.
- We will let you know when the issue is resolved and the fix has landed on
  `main`.

## Scope

In scope: anything in this repository — the backend API, the web build, the
webview bridge, the Android shell, and the infrastructure-as-code under `infra/`
(including deploy/auth configuration).

Out of scope: vulnerabilities in third-party dependencies or platforms (GCP,
Firebase, GitHub, etc.) — please report those to the respective vendor, though we
appreciate a heads-up if a fork of this skeleton is affected. Findings that
require already-compromised credentials, physical access, or social engineering
are generally out of scope.

## Safe harbour

We will not pursue or support legal action against researchers who act in good
faith under this policy: who make a genuine effort to avoid privacy violations,
data destruction, and service disruption; who only interact with accounts or
data they own or have explicit permission to test; and who give us reasonable
time to remediate before any public disclosure.

## No secrets in this repo

By design, `osk1` contains **no live secrets** — deploys are keyless (GitHub
OIDC), and `.gitignore` blocks credential material (service-account keys, ADC
JSON, `*.pem`, `*.p12`, env files). If you ever find a committed secret, treat it
as a vulnerability and report it privately using the process above so it can be
rotated and purged.

Two automated layers guard this: **GitHub secret scanning + push protection**
(enabled on the repo — push protection rejects a recognised secret before it can
be committed, and secret scanning alerts on anything that reaches `main`), plus a
**Gitleaks CI backstop** on every pull request (`.github/workflows/gitleaks.yml`)
that catches generic/high-entropy patterns the native scanner may miss. The
Gitleaks job is advisory (non-blocking); push protection is the hard gate.

### Handling a flagged or leaked secret

If a secret is flagged (by push protection, a secret-scanning alert, the
Gitleaks CI job, or a human) or is otherwise known to have been committed,
**assume it is compromised** and act in this order — speed matters more than
tidiness:

1. **Revoke / rotate first.** Immediately invalidate the credential at its
   source (delete or roll the API key, token, service-account key, or password
   in the provider's console). A secret that is still live is the actual risk;
   until it is revoked, removing it from git history achieves nothing. Issue a
   fresh credential and update the consuming systems.
2. **Then remove it from history.** Once the secret is dead, purge it from the
   repository so it is not re-leaked or copied by forks — rewrite history with
   `git filter-repo` (or the BFG Repo-Cleaner), force-push the cleaned branch,
   and ask collaborators to re-clone. For a secret-scanning alert, mark it
   resolved once the credential is rotated and purged.
3. **Notify.** Tell the maintainer (**basit@10xai.co.uk**) and anyone who
   depends on the affected credential. If the leak is in a fork of this
   skeleton, give the fork owner a heads-up. Do **not** open a public issue or
   PR describing the live secret — follow the private reporting process above.

Rotation is mandatory even if the secret was only exposed briefly: once a value
has been pushed to a public repo it must be considered permanently public.
