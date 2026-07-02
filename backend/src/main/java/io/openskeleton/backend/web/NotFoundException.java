package io.openskeleton.backend.web;

/**
 * Thrown when a requested resource does not exist. Mapped to HTTP 404 by
 * {@link GlobalExceptionHandler}. Reusable across features so every "missing
 * thing" produces the same RFC 7807 response instead of an ad-hoc 500.
 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
