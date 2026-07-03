package io.openskeleton.backend.user;

/**
 * The set of account-lifecycle transitions a {@code PATCH /api/v1/me/lifecycle} may apply to
 * the caller's own {@link User} (OSK-69): mark onboarding complete, accept a terms version,
 * mark age-verified. It is the <b>domain-level</b> update carrier consumed by
 * {@link UserService#updateLifecycle(String, LifecycleUpdate)}, decoupled from the API request
 * DTO so the service layer never depends on the {@code api} package (keeping the dependency
 * direction acyclic: {@code api → user → audit}, exactly as {@link ProfileUpdate} does).
 *
 * <p><b>Sparse (partial) semantics.</b> A {@code null} component means "the caller did not ask
 * to change this" and is left untouched; a non-null component is the requested new value. So a
 * request that only marks onboarding complete never disturbs the terms or age-verified state.
 *
 * <p><b>Terms acceptance is special.</b> {@code termsAcceptedVersion} carries only the version
 * the caller accepted; the accompanying acceptance <i>timestamp</i> is stamped server-side by
 * the service (never client-supplied), so it is deliberately not a component here. The version
 * has been validated non-blank at the API boundary by the time it reaches this type.
 *
 * @param onboardingCompleted the new onboarding-complete flag, or {@code null} to leave unchanged
 * @param termsAcceptedVersion the terms version being accepted, or {@code null} to leave unchanged
 * @param ageVerified the new age-verified flag, or {@code null} to leave unchanged
 */
public record LifecycleUpdate(Boolean onboardingCompleted, String termsAcceptedVersion, Boolean ageVerified) {}
