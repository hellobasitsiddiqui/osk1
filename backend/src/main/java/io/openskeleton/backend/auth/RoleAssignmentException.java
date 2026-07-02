package io.openskeleton.backend.auth;

/**
 * Thrown by {@link RoleService} when writing a caller's role custom claim to Firebase
 * fails at the Admin SDK level (OSK-79).
 *
 * <p><b>Why an unchecked wrapper around the SDK's checked exception:</b> the Admin SDK
 * signals failures with a checked {@code FirebaseAuthException}. Letting that type leak
 * out of the {@link RoleService} seam would couple every future caller (the OSK-71 admin
 * endpoint) to the Firebase SDK and force {@code throws} plumbing through the web layer.
 * Wrapping it in a small, unchecked, domain exception keeps the seam's contract clean
 * ("set this role, or fail loudly") while preserving the original cause for logs — the
 * same pattern the auth path already uses for {@code TokenVerificationException} and
 * {@code AuthDependencyUnavailableException}.
 */
public class RoleAssignmentException extends RuntimeException {

    /**
     * @param message a short description of which role assignment failed (safe to log)
     * @param cause   the underlying Admin SDK failure, kept for server-side diagnosis
     */
    public RoleAssignmentException(String message, Throwable cause) {
        super(message, cause);
    }
}
