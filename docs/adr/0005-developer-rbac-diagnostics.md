<!--
  ADR 0005 — spike output for OSK-142. Records the recommended shape for a
  developer-only RBAC role plus an in-app developer diagnostics panel, grounded
  in the auth/versioning/actuator seams that already exist in the backend.

  This is a SPIKE deliverable: no code lands with this ADR. It captures the
  investigation's conclusion and a follow-up ticket breakdown so the actual work
  (role + claim, the dev endpoint, the web panel) can be picked up with the
  design already decided. Follows 0000-template.md (context / decision /
  consequences); Status stays Proposed until the implementation tickets are
  scheduled and the team ratifies the direction.
-->

# 0005. Developer RBAC role + in-app developer diagnostics panel

- **Status:** Proposed (spike — recommendation pending the OSK-142 follow-up tickets)
- **Date:** 2026-07-03

## Context

We want a first-class **developer** experience for inspecting a running deployment:
a small in-app panel that shows what is actually deployed and how it is behaving
(build/commit, active profile, config toggles, a recent trace id, dependency
health) — without SSHing into anything or handing developers admin rights. Two
questions fall out of that and force a decision now, before anyone builds it:

1. **Who may see it?** The panel exposes operational internals, so it must be
   restricted to developers specifically — not every signed-in `USER`, and not
   conflated with `ADMIN` (an admin manages *users/data*; a developer inspects
   *the system*). That means a new authorization role.
2. **Where does the data come from, and what is safe to expose?** Much of the
   raw material already exists behind seams built by earlier tickets, so we must
   decide what to reuse versus build, and draw the line on what is safe to show.

The relevant forces are set by what is already in the tree:

- **Roles already exist, minimally.** `User.Role` (`backend/.../user/User.java`,
  OSK-60) is an `EnumType.STRING` enum with exactly `USER` and `ADMIN`, persisted
  by name in a `TEXT` column, and explicitly documented as "adding values later
  is backwards-safe".
- **A role custom claim + mapper already exist.** `RoleClaimMapper`
  (`backend/.../auth/RoleClaimMapper.java`, OSK-79) reads the Firebase `role`
  custom claim and maps it to a Spring `GrantedAuthority`. It iterates
  `Role.values()` case-insensitively and **fails safe to `USER`** for any absent,
  blank, or unrecognised claim. `RoleService` (OSK-79) is the write side — it
  stamps `{"role": "<NAME>"}` onto a Firebase user via the Admin SDK, sharing the
  `ROLE_CLAIM` constant and `enum name()` with the mapper so the two can never
  drift.
- **Authentication is a plain filter, and authorities are already published.**
  `FirebaseAuthenticationFilter` (`backend/.../auth/FirebaseAuthenticationFilter.java`,
  OSK-28) is an `OncePerRequestFilter` — deliberately *not* `spring-boot-starter-security`
  — that guards only `/api/v1/**` (default-deny for the API: a new API controller
  is authenticated by omission), and on success publishes the caller's authorities
  as the `AUTHORITIES_ATTRIBUTE` request attribute for "later authorization logic
  (OSK-71) to read". So the authorization *direction* is already set: gate on the
  published `ROLE_*` authority.
- **A build/version stamp already exists.** `VersionController` (`/version`,
  OSK-100) returns `{sha, buildTime, revision}` (git commit, jar build time, Cloud
  Run revision), public and unauthenticated, sourced from `BuildProperties` + the
  `GIT_SHA`/`K_REVISION` env — nothing hardcoded.
- **Actuator is already on, narrowly.** `application.yml` (OSK-50) exposes only
  `health,info,metrics`; `health` is public with `show-details: when-authorized`,
  while `info` + `metrics` are documented as "meant to require auth". `info`
  carries app name + environment. Prod (`application-prod.yml`) disables Swagger
  and turns on Stackdriver metrics export; Micrometer Tracing puts `trace.id`/
  `span.id` in the MDC (OSK-45).
- **The web is static HTML with an established pattern.** `web/index.html`
  already fetches `/version` at runtime from `config.js`'s `apiBaseUrl` and
  degrades gracefully; `web/ops.html` (OSK-90) is an *ungated links* page that is
  explicit about **not leaking secrets/project ids/region/internal hostnames**.
  `MeController` (`GET /api/v1/me`) already returns the caller's persisted `role`.

## Decision

We will implement developer diagnostics as **(a) a new `DEVELOPER` value on the
existing `User.Role` enum, carried by the existing Firebase `role` custom claim,
and (b) a bespoke, developer-gated `GET /api/v1/dev/info` endpoint that
*composes* existing signals**, surfaced by a web panel that renders only for a
developer. We deliberately reuse the role/claim/version/actuator machinery rather
than inventing parallel mechanisms, and we enforce access **server-side**.

### 1. RBAC half — the `DEVELOPER` role

- **Add `DEVELOPER` to `User.Role`** alongside `USER`/`ADMIN`. Because the enum
  is persisted `EnumType.STRING` into a `TEXT` column, **no DB migration is
  needed** — the new name simply becomes a legal stored value.
- **The claim path needs no change.** `RoleClaimMapper.roleFrom(...)` already
  iterates `Role.values()`, so a `role: "DEVELOPER"` claim maps to
  `ROLE_DEVELOPER` automatically, and `RoleService.setRole(uid, Role.DEVELOPER)`
  already knows how to write it. The only additions are tests asserting the new
  branch (and that an unknown claim still falls back to `USER`).
- **Assignment** is out-of-band and privileged: a developer is minted by calling
  `RoleService.setRole(uid, DEVELOPER)` (today a seam; wired to an admin path in
  OSK-71/OSK-72). The change takes effect on the user's *next* ID token refresh —
  standard custom-claim behaviour. This keeps developer-granting an
  administrative act, not a self-service one.
- **Authorization** rides the direction OSK-71 already set: the auth filter
  publishes `ROLE_DEVELOPER` in `AUTHORITIES_ATTRIBUTE`, and the dev endpoint
  asserts that authority is present, returning **403** otherwise (401 for an
  unauthenticated caller is already handled by the filter). **Default-deny is
  preserved** two ways: the endpoint lives under `/api/v1/**` so it is
  authenticated by default, and a caller without the developer authority is
  denied. Recommended enforcement: a small explicit authority check consistent
  with the current no-Spring-Security posture; adopting `@PreAuthorize(
  "hasRole('DEVELOPER')")` is a clean alternative *only if* the team chooses to
  pull in `spring-boot-starter-security` (a dependency the filter's javadoc
  explains was intentionally avoided) — that trade is called out in the endpoint
  ticket, not pre-decided here.

### 2. Surfacing half — the dev endpoint + web panel

- **`GET /api/v1/dev/info`, gated to `DEVELOPER`**, returns a runtime snapshot
  assembled from signals that already exist:
  - **build / version / commit** — reuse `VersionController`'s resolution
    (`BuildProperties` + `GIT_SHA`/`K_REVISION`); do not duplicate it.
  - **active profile** — `spring.profiles.active` + `app.environment-name`.
  - **feature flags / config toggles** — the resolved, *non-secret* `app.*`
    switches (e.g. `app.rate-limit.enabled`, pagination caps). There is no
    feature-flag system yet, so this starts as the safe config snapshot and grows
    if/when one lands.
  - **recent trace id** — the current request's `trace.id` from the Micrometer
    Tracing MDC (already emitted per log line in prod), so a developer can pivot
    from the panel straight to the logs.
  - **dependency health** — reuse Actuator's health indicators (DB, etc.) rather
    than re-probing.
- **The web panel renders only when `me.role == "DEVELOPER"`.** The web already
  calls `GET /api/v1/me` (which returns `role`) and already fetches `/version`
  the same way; the panel is the same pattern — fetch `/me`, and *only if*
  developer, fetch and render `/api/v1/dev/info`, degrading gracefully on any
  failure. This is a distinct thing from `web/ops.html` (an ungated static
  *links* page): the panel is a data-driven, gated view. The client check is a
  **convenience to hide UI**, never the security boundary.

### 3. Reuse-vs-bespoke: Actuator vs a dedicated endpoint

We evaluated exposing `/actuator/info` + `/actuator/metrics` directly to the
panel instead of building `/api/v1/dev/info`:

- **Reuse Actuator for the *data*, not as the *public contract*.** Actuator is
  the right source for health and metrics, but pointing a developer UI straight
  at `/actuator/metrics` couples the panel to Actuator's shape and, more
  importantly, to Actuator's *auth model*, which is separate from the Firebase
  bearer-token model the rest of `/api/v1` uses (`info`/`metrics` are only
  "meant to require auth" and are not on the `/api/v1/**` protected prefix). A
  bespoke `/api/v1/dev/info` inherits the existing token auth + the new
  `DEVELOPER` gate for free, returns exactly the curated snapshot (no more), and
  gives us one stable contract to version.
- **What is safe to expose:** build/commit/revision, active profile, non-secret
  config toggles, the request's own trace id, coarse dependency health (`UP`/
  `DOWN`). **Never**: secrets, connection strings, `env`/full property dumps,
  project ids, region, internal hostnames, tokens, or component-health internals
  to a non-developer — the same line `ops.html` already draws.
- **Prod-vs-dev gating:** the *endpoint* is gated by role in every environment
  (a developer in prod is a legitimate viewer). Independently, the raw Actuator
  surface stays locked down in prod exactly as today (Swagger already off; only
  `health,info,metrics` exposed; details `when-authorized`) — the panel does not
  widen it. If a field is judged too sensitive for prod, it is omitted by
  profile server-side, not hidden client-side.

### 4. Security stance

- **Authorization is enforced server-side, always.** The `DEVELOPER` authority
  check on `/api/v1/dev/info` is the boundary; the web `role`-check only decides
  whether to *draw* the panel. A `USER` who calls the endpoint directly gets
  **403**, whether or not any UI is shown.
- **No secret or internal detail ever reaches a non-developer** — and the
  snapshot is curated so that even a developer sees operational facts, not
  credentials. This preserves the least-privilege / default-deny posture the auth
  layer and `RoleClaimMapper`'s fail-safe-to-`USER` default already establish.

## Consequences

- **Easier:** the role model extends by one enum value with **no migration and no
  mapper change** (the claim pipeline was built to absorb it); the dev endpoint is
  *composition* of `/version`, config, MDC, and Actuator health rather than new
  data collection; the web panel reuses the existing `/me` + `/version` fetch
  pattern; authorization reuses the already-published `AUTHORITIES_ATTRIBUTE`, so
  there is one authorization direction, not two.
- **Harder / obligations:** the dev snapshot is a **new information-disclosure
  surface** and must be curated conservatively and reviewed field-by-field — the
  temptation to "just proxy Actuator" has to be resisted. Granting `DEVELOPER`
  needs an administrative path and an audit story (OSK-71/OSK-72). The
  authorization-enforcement mechanism (explicit authority guard vs adopting
  Spring Security method-security) is a real sub-decision deferred to the
  endpoint ticket. `DEVELOPER` is a *third* role, so any code that branches on
  role (web gating, future admin UI) must handle three values, not two, and keep
  treating unknown/absent as `USER`.

### Proposed implementation ticket breakdown

1. **RBAC: add the `DEVELOPER` role + claim.** Add `DEVELOPER` to `User.Role`;
   add `RoleClaimMapper` tests for `role: "DEVELOPER"` → `ROLE_DEVELOPER` and the
   unchanged fail-safe-to-`USER` default; add a `RoleService` test for granting
   it. No DB migration (enum stored as `TEXT`). Confirm `MeController` renders the
   new role name. *(No web/endpoint work here.)*
2. **Backend: `GET /api/v1/dev/info`, gated to `DEVELOPER`.** Build the composed
   snapshot (reuse `VersionController` resolution; active profile; safe `app.*`
   toggles; MDC `trace.id`; Actuator health), enforce the `DEVELOPER` authority
   server-side (403 for non-developers, 401 unauth via the filter), and decide +
   record the enforcement mechanism (explicit guard vs `@PreAuthorize`). Include
   prod-vs-dev field gating and an explicit no-secrets test. *Depends on #1.*
3. **Web: the developer diagnostics panel.** Fetch `/api/v1/me`; only when
   `role == "DEVELOPER"`, fetch and render `/api/v1/dev/info` in a panel
   consistent with `index.html`/`ops.html` (runtime `apiBaseUrl`, self-contained,
   graceful degradation, no secret leakage). Client gate is UI-only; the server
   gate from #2 is the real boundary. *Depends on #2.*
