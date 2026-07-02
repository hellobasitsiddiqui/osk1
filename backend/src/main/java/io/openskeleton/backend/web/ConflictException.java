package io.openskeleton.backend.web;

/**
 * Thrown when a request conflicts with current state (e.g. a uniqueness clash).
 * Mapped to HTTP 409 by {@link GlobalExceptionHandler}. A persistence-layer
 * DataIntegrityViolationException mapping is added once the data layer lands
 * (OSK-32); until then this custom type carries the 409 contract.
 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
