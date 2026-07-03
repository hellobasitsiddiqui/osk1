package io.openskeleton.backend.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bean-validation constraint asserting a {@link String} is a well-formed BCP-47 language
 * tag (e.g. {@code en-GB}) — used on the {@code locale} field of the {@code PATCH /api/v1/me}
 * request (OSK-67).
 *
 * <p>As with {@link ValidTimezone}, {@code null} is treated as VALID ("not provided" — the
 * sparse-PATCH convention). A non-null value is checked for well-formedness only (the tag's
 * syntax), NOT whether the language/region is one the JDK recognises, so uncommon-but-valid
 * tags pass while genuinely malformed input (e.g. {@code en_GB} with an underscore) is
 * rejected as a 400 {@code problem+json}.
 */
@Documented
@Constraint(validatedBy = LocaleValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidLocale {
    String message() default "must be a well-formed BCP-47 language tag (e.g. en-GB)";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
