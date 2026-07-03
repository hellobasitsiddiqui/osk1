<!--
  ADR 0004 — records the recommended design for a configurable, per-field profile
  edit policy on PATCH /api/v1/me (the OSK-135 spike). Follows 0000-template.md
  (context / decision / consequences), with the Decision expanded into the five
  areas the spike ticket asks it to cover plus a proposed ticket breakdown.

  This is a SPIKE outcome: no production code ships with it. Status is Proposed —
  it records the recommended direction and moves to Accepted when the
  implementation epic below is scheduled.

  Number note: 0003 is the highest existing ADR, so this spike takes 0004; a
  sibling spike (OSK-142) is reserving 0005, so we intentionally skip it to avoid
  a collision on the ADR timeline — mirroring how the Flyway migrations reserve
  version numbers for sibling tickets (see V4__create_audit_events.sql).
-->

# 0004. Configurable per-field profile edit policy

- **Status:** Proposed (spike — recommendation pending the implementation epic)
- **Date:** 2026-07-03

## Context

`PATCH /api/v1/me` (`MeController.updateMe`) lets an authenticated caller change
their own profile. Today the only mutable field is `displayName`:
`MeController.UpdateProfileRequest` carries just that field, and
`UserService.updateProfile(firebaseUid, displayName)` loads the caller's row,
calls `user.setDisplayName(...)`, and saves — unconditionally. Identity and
authorization fields (`firebaseUid`, `email`, `role`, `enabled`) are deliberately
never touched by this path (see the `updateProfile` and `MeController` Javadoc).

As the profile grows (username, handle, display name, later a self-service email
change), a flat "any mutable field is freely editable" rule is too blunt. Product
needs different rules per field, and it needs to tune them without a code change:

- Some fields must never change through our API (e.g. `email`, which is owned by
  the Firebase token — see below).
- Some are freely editable (`displayName` today).
- Some should be editable but **cadence-limited** — e.g. "you may change your
  username at most once every 30 days" — to curb abuse and impersonation churn,
  with the cadence tunable by operators, not hard-coded.

Forces in tension:

- **Tunable, not coded.** Cadences are a product/policy decision that will change;
  they must live in config, matching how every other tunable in this backend is
  handled — `RateLimitProperties` (`app.rate-limit.*`) and `AppProperties`
  (`app.*`) are `@ConfigurationProperties`, env-overridable, validated at startup.
- **One enforcement seam.** `UserService` is described throughout as the single
  seam other flows build on rather than manipulating fields ad hoc; a policy check
  scattered across controllers would drift.
- **Consistent rejection contract.** The API already returns RFC 7807
  `application/problem+json` for every error via `GlobalExceptionHandler`
  (404/409/400/500, including a 409 for optimistic-lock conflicts). A new
  rejection must slot into that model, not invent a bespoke error shape.
- **We already record "who changed what, when".** `audit_events` (OSK-80) is an
  append-only log whose `metadata` JSONB column is explicitly intended for "which
  fields changed", keyed by `actor_firebase_uid` (which equals
  `users.firebase_uid`) with an `Instant createdAt`. A cadence window is exactly
  "time since this field last changed", which that log can already answer.

This ADR fixes the *shape* of the solution so the implementation tickets are
mechanical. It builds nothing.

## Decision

We will introduce a **configurable per-field edit policy** enforced in the
`UserService.updateProfile` seam, with the cadence window derived from the
existing `audit_events` log, and rejections surfaced through the existing RFC 7807
handler. The five areas below are the recommendation; alternatives are called out
inline.

### 1. Per-field policy model (config-sourced)

Each profile field carries one of three policies:

- `IMMUTABLE` — cannot be changed through our API.
- `EDITABLE` — freely changeable (today's `displayName` behaviour).
- `RATE_LIMITED(window)` — changeable at most once per `window` (a `Duration`).

Source it from config under a new `user.field-policy.*` namespace, bound by a new
`UserFieldPolicyProperties` `@ConfigurationProperties` class — the exact pattern
`RateLimitProperties` already uses (validated, env-overridable, `Duration` fields
parsed the way `refill-period: 1m` already is; `Duration` supports `d`, so `30d`
works). Sketch:

```yaml
user:
  field-policy:
    display-name:
      mode: EDITABLE
    email:
      mode: IMMUTABLE
    # illustrative future field with a tunable cadence:
    username:
      mode: RATE_LIMITED
      window: 30d           # operator-tunable, no code change
```

A tiny value type holds `{ mode, window }` (window required only for
`RATE_LIMITED`, asserted by Bean Validation at startup so a misconfigured policy
fails fast — matching `AppProperties`). Fields absent from config fall back to a
safe default of `EDITABLE` (or `IMMUTABLE` if we prefer deny-by-default — a
one-line decision for the implementation ticket). Cadences are therefore tunable
purely by config/env, with no logic change — the AC.

### 2. Where enforcement lives, and the rejection contract

**Enforce inside `UserService.updateProfile`**, not in the controller.
`updateProfile` is already `@Transactional` and does a load-mutate-save under the
`@Version` optimistic lock, so the "read the current persisted value, decide,
then write" happens atomically in one transaction. The controller
(`MeController.updateMe`) stays thin: provision, then delegate. As more mutable
fields arrive, generalise `updateProfile` to take a small changes DTO/map and run
each proposed change through a `ProfileFieldPolicyEnforcer` before applying it.

Enforcement rules:

- **No-op is always allowed.** If the incoming value equals the current persisted
  value, never reject — this keeps idempotent PATCHes from tripping the limiter.
- **`IMMUTABLE` + a real change → reject.**
- **`RATE_LIMITED` + a real change → check the window** (§3); reject if too soon,
  otherwise apply.

**Rejection contract (RFC 7807, via `GlobalExceptionHandler`).** Add a new
`FieldEditPolicyException` and a matching `@ExceptionHandler` that returns a
`ProblemDetail`, exactly as the existing handlers do (the handler already attaches
extension members — see the `errors` list on the 400 path). Recommended mapping:

- **`IMMUTABLE` violation → `422 Unprocessable Content`.** The request is
  well-formed but the field can never be changed here; no retry helps, so no
  `retryAfter`.
- **`RATE_LIMITED` violation → `409 Conflict`**, consistent with the existing 409
  ("your copy conflicts with current state — reload and retry") semantics used for
  optimistic-lock failures. Attach extension members `retryAfter` (seconds) and
  `nextAllowedAt` (ISO-8601 instant) via `ProblemDetail.setProperty(...)`, and
  optionally the standard `Retry-After` header. Body sketch:

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "Field 'username' can be changed at most once every 30 days.",
  "retryAfter": 1512000,
  "nextAllowedAt": "2026-08-02T05:42:00Z"
}
```

### 3. Tracking last-changed-per-field (recommendation)

Two options for the "when did this field last change" the cadence window needs:

- **Option A — columns on `users`.** Add `<field>_changed_at TIMESTAMPTZ`
  columns via a new Flyway migration. Cheapest read (the row is already loaded in
  `updateProfile`), trivial window math. Downsides: one column per rate-limited
  field (schema churn as fields grow) and it duplicates what the audit log already
  records.
- **Option B — derive from `audit_events`.** The log already exists to answer
  this. Record a structured `PROFILE_UPDATED` event (the `AuditAction` value
  already exists) with `metadata` naming the changed field, then the window check
  is `MAX(created_at) WHERE actor_firebase_uid = :uid AND action =
  'PROFILE_UPDATED' AND metadata->>'field' = :field`. No schema change, single
  source of truth, full change history for free.

**Recommendation: Option B.** The `audit_events` `metadata` column was designed
precisely for "which fields changed", its `actor_firebase_uid` already equals the
`firebaseUid` `updateProfile` holds, and it avoids per-field column sprawl.

Two honest caveats, both cheap to handle:

1. **The audit write on this path is not wired yet.** `AuditService.record(...)`
   exists but its Javadoc notes the call sites land with OSK-76/OSK-71; today
   `updateProfile` does **not** write an audit row. So Option B requires first
   making the profile-update path emit a structured `PROFILE_UPDATED` event (one
   per changed field). This is a small, independently-useful piece of work that
   also closes that documented gap.
2. **It adds a read to the write path.** One indexed `MAX(created_at)` query per
   rate-limited change. The existing indexes are `created_at DESC` and
   `actor_firebase_uid`; if this ever profiles as hot, add a composite index or
   fall back to **Option A** as a denormalisation — a targeted follow-up, not a
   day-one need. Start with B; denormalise only if measured.

### 4. Email special case

`users.email` is copied from the verified Firebase token at provisioning time
(`provisionFromToken(uid, email)` sets it only on first insert) and
`updateProfile` deliberately never touches it — email is owned by the Firebase
token, not our DB. It is already de-facto immutable via our API
(`UpdateProfileRequest` has no email field).

**Recommendation: keep email `IMMUTABLE` via our API**, and make that explicit by
declaring `email: { mode: IMMUTABLE }` in `user.field-policy.*` so that if a
future request body ever tries to set it, the enforcer rejects it with a 422
rather than relying on the field simply being absent. A genuine email change must
go through a **Firebase re-verification flow**: update the Firebase user's email,
re-verify it, and let the new token's email propagate into our row. **Building
that flow is out of scope for this spike** (and its implementation ticket) — this
ADR only records that email changes do *not* belong on `PATCH /me`.

Related gap worth a follow-up (not this spike): `provisionFromToken` sets `email`
only on the first insert and never refreshes it on the steady-state path, so a
stored email can drift from Firebase if it changes there. An email re-sync from
the verified token is a separate ticket, noted here so it is not lost.

## Consequences

- **Easier:** field cadences become an operator-tunable config value, matching the
  established `@ConfigurationProperties` convention; enforcement is one seam
  (`updateProfile`) other flows already route through; rejections reuse the
  existing RFC 7807 handler so clients get a uniform, machine-readable error with
  `retryAfter` / `nextAllowedAt`; last-changed tracking reuses `audit_events`
  rather than adding schema, and the same work finally wires the profile path into
  the audit log.
- **Harder / obligations:** Option B puts one indexed read on the write path
  (mitigated by an index, or by falling back to per-field columns if profiled);
  the audit-derived cadence depends on the `PROFILE_UPDATED` write actually
  happening and on audit retention, so those become correctness dependencies of
  the limiter; the policy config is now a security-relevant surface (a fat-fingered
  `email: EDITABLE` would open a field that should stay closed), so the validated
  binding and a conservative default matter; and email remains only changeable via
  a Firebase re-verification flow that this work explicitly does not build.

## Proposed implementation ticket breakdown

Sequenced; dependencies noted. Each is independently reviewable.

1. **Policy model + config binding.** Add `UserFieldPolicyProperties`
   (`user.field-policy.*`) with the `{ mode, window }` value type, Bean Validation
   (window required for `RATE_LIMITED`), a default policy, and `application.yml`
   defaults. Mirrors `RateLimitProperties`. Tests: binding + validation
   (fail-fast on a `RATE_LIMITED` with no window). *(No behaviour change yet.)*
2. **Rejection contract.** Add `FieldEditPolicyException` and a
   `GlobalExceptionHandler` mapping — 422 for `IMMUTABLE`, 409 for `RATE_LIMITED`
   with `retryAfter` / `nextAllowedAt` extension members (and `Retry-After`
   header). Update the OpenAPI/`ApiSpecDrift` expectations. *(Depends on 1.)*
3. **Audit the profile path.** Make `UserService.updateProfile` record a
   structured `PROFILE_UPDATED` audit event per changed field (`metadata` =
   `{ field, ... }`) via `AuditService`. Closes the OSK-80 "call sites land later"
   gap for this path and is the prerequisite for the cadence read. *(Independent
   of 1–2; blocks 4.)*
4. **Enforcement.** Add `ProfileFieldPolicyEnforcer` (or inline it) and call it at
   the top of `updateProfile`: same-value no-op passes; `IMMUTABLE` rejects;
   `RATE_LIMITED` derives last-change from `audit_events`
   (`MAX(created_at)` by actor+action+field), compares to the window, and either
   rejects with `nextAllowedAt` or applies. Add the repository query and, if
   needed, a composite index. Tests: allow / immutable-reject / rate-limit-reject
   / same-value no-op / window boundary. *(Depends on 1, 2, 3.)*
5. **Make email explicit.** Declare `email: IMMUTABLE` in config and assert that
   any attempt to set it via the API returns 422. *(Depends on 1, 2.)*
6. **(Conditional) Denormalisation fallback.** Only if 4 profiles as a write-path
   bottleneck: add `<field>_changed_at` columns (or a small
   `user_field_changes` table) via a Flyway migration and switch the enforcer to
   read them. *(Depends on 4; may never be needed.)*
7. **(Out of scope here, tracked separately) Firebase email change/re-sync.** The
   re-verification flow for changing email, plus refreshing `users.email` from the
   verified token on the steady-state path. Its own epic — named so it is not
   lost, explicitly *not* built by 1–6.

Docs: fold the field-policy convention into `ARCHITECTURE.md` alongside the
existing pagination / rate-limit conventions, and link this ADR, when ticket 4
lands.
