<!--
  ADR 0002 — records why the backend runs on Cloud Run and the web app on
  Firebase Hosting, and why deploy auth is keyless (GitHub OIDC).
  Follows 0000-template.md (context / decision / consequences).
-->

# 0002. Host the backend on Cloud Run and the web app on Firebase Hosting

- **Status:** Accepted
- **Date:** 2026-07-02

## Context

The skeleton has two deployable web surfaces with very different shapes:

- `backend` — a stateful, containerised Spring Boot API that must scale with
  request load and hold database connections.
- `web` — a static single-page build (HTML/CSS/JS) that must be served fast and
  cheaply from the edge, and is also the payload wrapped by the mobile shells.

We need a hosting model for each, plus a deploy authentication story. Forces:

- Low ops overhead and scale-to-zero economics for a round-1 skeleton.
- The backend is already containerised (Cloud Run–targeted per its README), so
  the runtime should consume a container image, not a language buildpack lock-in.
- Static assets want a CDN, cheap hosting, and atomic rollouts — not a container.
- **No long-lived credentials in git.** The root README states the project is
  keyless (GitHub OIDC), and `.gitignore` blocks service-account keys.

Alternatives considered: GKE (far more operational surface than a skeleton
needs); App Engine (less container-native and more opinionated than Cloud Run);
serving the web build from the same container/nginx behind Cloud Run in
production (loses the CDN and atomic-rollout wins of Firebase Hosting — nginx is
retained for local/container parity, not as the production edge); a
long-lived exported service-account JSON key for CI (violates the keyless rule).

## Decision

We will host the **backend on Cloud Run** (deployed as a container image pulled
from Artifact Registry) and the **web build on Firebase Hosting** (static assets
on Google's CDN with atomic deploys and instant rollback). CI/CD authenticates to
GCP via **GitHub OIDC Workload Identity Federation** — GitHub Actions exchanges a
short-lived OIDC token for a scoped GCP access token and impersonates a deploy
service account, so **no service-account key is ever stored**.

This pairs each surface with the platform primitive built for its shape
(container runtime vs. static CDN) while keeping everything in one GCP project
and honouring the no-secrets-in-git constraint.

## Consequences

- **Easier:** backend scales to zero and back automatically (pay per request);
  the web app gets CDN performance and one-command atomic rollback; deploy
  credentials are short-lived and self-expiring, so there is no key to leak,
  rotate, or accidentally commit.
- **Harder / obligations:** Cloud Run's scale-to-zero introduces cold starts (a
  JVM app may need a min-instance floor for latency-sensitive paths). Splitting
  the two surfaces means two deploy targets to wire in CI. The OIDC trust policy
  (Workload Identity Pool, provider, and the `repo:…` attribute condition that
  scopes which branches/repos may impersonate the deploy SA) must be configured
  precisely in `infra` — a loose condition is a real security hole. This is a GCP
  coupling, accepted in exchange for the managed-platform and keyless benefits.
