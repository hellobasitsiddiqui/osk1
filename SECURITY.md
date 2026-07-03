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

Three automated layers guard this:

1. **A local Gitleaks pre-commit hook** (`.pre-commit-config.yaml`, OSK-56) —
   scans the staged diff on `git commit` and **blocks the commit** if it finds a
   credential, so a real secret is stopped *before it ever leaves the machine*.
   Enable it once per clone with `pre-commit install` (see the bring-up runbook;
   git worktrees share one `.git/hooks`, so a single install covers them all).
2. **GitHub secret scanning + push protection** (enabled on the repo) — push
   protection rejects a recognised secret at push time, and secret scanning
   alerts on anything that reaches `main`.
3. **A blocking Gitleaks CI backstop** on every pull request
   (`.github/workflows/gitleaks.yml`, OSK-56) — catches generic/high-entropy
   patterns the native scanner may miss and now **fails the PR** on a finding,
   for anyone who has not installed the local hook.

All three read the same `.gitleaks.toml` where relevant: it uses the default
ruleset plus one tightly-scoped allowlist for the **public** Firebase Web client
config in `web/config.js` (a client identifier, not a secret — access is enforced
by Firebase Auth + backend token verification). The allowlist is narrow (path +
exact literal), so it cannot mask a real, different secret.

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

## HTTP security response headers (OSK-23)

The backend sets a baseline set of security headers on **every** HTTP response,
via a plain servlet filter (`io.openskeleton.backend.web.SecurityHeadersFilter`).
We deliberately do **not** use Spring Security for this: it would switch on
default authentication across the whole app and break every existing
unauthenticated endpoint. Response-header hardening needs no auth framework.

| Header | Value | Notes |
|--------|-------|-------|
| `X-Content-Type-Options` | `nosniff` | Stops browsers MIME-sniffing a response away from its declared type. |
| `X-Frame-Options` | `DENY` | Legacy click-jacking defence; refuses to be framed anywhere. |
| `Referrer-Policy` | `no-referrer` | Never leak this API's URLs in an outbound `Referer`. |
| `Content-Security-Policy` | `default-src 'none'; frame-ancestors 'none'; base-uri 'none'` | Strict, API-appropriate policy — see below. |
| `Strict-Transport-Security` | `max-age=31536000; includeSubDomains` | **Only** on secure requests — see below. |

### Why the CSP is safe to be this strict

This backend is a **JSON-only API**. It serves no HTML, loads no scripts,
styles, images, or fonts, and is never meant to be embedded in a page. The
browser-facing web UI is a **separate nginx app** with its own, more permissive
CSP. Because nothing in *this* service's responses references any content
source, a "deny everything" policy (`default-src 'none'`) costs us nothing and
removes an entire class of injection/framing risk. `frame-ancestors 'none'` is
the modern equivalent of `X-Frame-Options: DENY` (both are sent for coverage of
older browsers), and `base-uri 'none'` blocks `<base>`-tag URL rebasing.

If a future endpoint ever needs to serve HTML or a browsable surface, relax the
policy for that path specifically rather than weakening this global default.

### Why HSTS is conditional

`Strict-Transport-Security` tells a browser to only ever contact this host over
HTTPS. Emitting it over plain HTTP is meaningless and, worse, could wrongly pin
a local/dev host to HTTPS. The filter therefore sends HSTS **only** when the
request actually arrived over TLS — detected either directly
(`request.isSecure()`) or, in the Cloud Run deployment where TLS is terminated
by an upstream proxy, via the `X-Forwarded-Proto: https` request header. Plain
HTTP (local dev) never receives HSTS.
