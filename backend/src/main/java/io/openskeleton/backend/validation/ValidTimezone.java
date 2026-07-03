package io.openskeleton.backend.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bean-validation constraint asserting a {@link String} is a valid IANA time-zone id
 * (i.e. one {@link java.time.ZoneId#of(String)} accepts, e.g. {@code Europe/London}) —
 * used on the {@code timezone} field of the {@code PATCH /api/v1/me} request (OSK-67).
 *
 * <p>Consistent with the rest of the profile's optional fields, {@code null} is treated as
 * VALID ("not provided" — the sparse-PATCH convention); only a non-null, non-parseable zone
 * is rejected, surfacing as a 400 {@code problem+json} via the global validation handler.
 */
@Documented
@Constraint(validatedBy = TimezoneValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidTimezone {
    String message() default "must be a valid IANA time zone (e.g. Europe/London)";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
