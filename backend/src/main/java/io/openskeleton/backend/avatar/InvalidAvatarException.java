package io.openskeleton.backend.avatar;

/**
 * The caller supplied an avatar reference that failed server-side validation (OSK-83) — e.g. an
 * object path outside their own {@code users/{uid}/} area, a non-image extension, or a download URL
 * that does not reference the uploaded object. Mapped to a {@code 400 problem+json} by
 * {@code MeController}'s local exception handler (a client error, not a server fault).
 */
public class InvalidAvatarException extends RuntimeException {

    public InvalidAvatarException(String message) {
        super(message);
    }
}
