<!--
  ADR 0003 — records why user authentication is delegated to Firebase
  Authentication, with the backend verifying Firebase-issued ID tokens.
  Follows 0000-template.md (context / decision / consequences).
-->

# 0003. Use Firebase Authentication for user identity

- **Status:** Accepted
- **Date:** 2026-07-02

## Context

The skeleton needs an end-user identity system: sign-up/sign-in for the `web`
app (which also runs inside the `android` WebView shell) and a way for the
`backend` API to authenticate the caller behind each `/api/v1` request. The
backend README notes the auth seam is intentionally left as a stub for a
dedicated ticket, so this ADR fixes the *provider and integration shape*.

Forces in tension:

- Rolling our own credential storage, password hashing, session management, email
  verification, and social-login plumbing is high-risk, security-sensitive work
  that a skeleton should not reinvent.
- The platform is already GCP/Firebase-based — the web app deploys to Firebase
  Hosting (see [ADR-0002](0002-hosting-cloud-run-firebase.md)) — so a Firebase-
  native auth provider minimises new moving parts and SDKs.
- The same web build must authenticate identically whether it runs in a desktop
  browser or inside the mobile WebView shell, so auth must be a client-side JS
  flow that yields a bearer token the backend can verify statelessly.
- The backend is stateless and scales to zero on Cloud Run, so per-request auth
  should not depend on server-side session state.

Alternatives considered: hand-rolled username/password + JWT (maximum control,
maximum liability and maintenance); a self-hosted identity server such as
Keycloak (more ops burden and another service to run); a third-party IdP such as
Auth0/Okta (capable, but adds a vendor outside the existing GCP footprint and
extra cost for skeleton-scale needs).

## Decision

We will use **Firebase Authentication** as the identity provider. The `web`
client performs sign-in via the Firebase SDK and obtains a short-lived
**Firebase ID token (a signed JWT)**; every API call sends it as a
`Authorization: Bearer <token>` header. The **backend verifies the token** with
the Firebase Admin SDK (validating signature, issuer, audience, and expiry)
against Firebase's public keys — no server-side session store, and it works the
same in-browser and inside the WebView shell.

Delegating identity to Firebase gives us managed password/social/email flows and
a battle-tested token format for free, while stateless verification fits Cloud
Run's scale-to-zero model.

## Consequences

- **Easier:** no bespoke credential storage or session infrastructure; multiple
  sign-in methods (email/password, Google, etc.) are configuration, not code;
  token verification is stateless and horizontally scalable; identity lives in
  the same GCP project as the rest of the platform.
- **Harder / obligations:** identity is coupled to Firebase (a fork wanting a
  different IdP re-implements the client sign-in and the backend verifier —
  contained to the auth seam, but real). The backend must correctly validate
  every token claim (issuer, audience, expiry) and cache Firebase's public keys;
  a lax verifier is a critical vulnerability. Authorization (roles/permissions)
  is out of scope here — Firebase supplies *authentication*; the backend still
  owns *what an authenticated user may do*, e.g. via custom claims or its own
  model.
