package io.openskeleton.backend.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.time.DateTimeException;
import java.time.ZoneId;

/**
 * Validator behind {@link ValidTimezone}: a value is valid when it is {@code null} (the
 * "not provided" sparse-PATCH case) or {@link ZoneId#of(String)} accepts it. Any other
 * value — an unknown region, a malformed id, or a blank string — is rejected.
 *
 * <p>{@link ZoneId#of(String)} throws {@link DateTimeException} (its subclasses
 * {@code ZoneRulesException}/{@code DateTimeParseException}) for both unknown and malformed
 * zones, so catching that one type covers every invalid case without a false pass.
 */
public class TimezoneValidator implements ConstraintValidator<ValidTimezone, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            // Absent field: nothing to change, so nothing to reject (sparse PATCH).
            return true;
        }
        try {
            ZoneId.of(value);
            return true;
        } catch (DateTimeException ex) {
            return false;
        }
    }
}
