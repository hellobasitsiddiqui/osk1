package io.openskeleton.backend.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.IllformedLocaleException;
import java.util.Locale;
import java.util.Locale.Builder;

/**
 * Validator behind {@link ValidLocale}: a value is valid when it is {@code null} (the
 * "not provided" sparse-PATCH case) or a well-formed BCP-47 language tag. Well-formedness
 * is checked with {@link Builder#setLanguageTag(String)}, which throws
 * {@link IllformedLocaleException} on a syntactically invalid tag (e.g. the underscore in
 * {@code en_GB}, which should be {@code en-GB}).
 *
 * <p>A blank string is rejected explicitly: {@code setLanguageTag("")} does NOT throw (an
 * empty tag denotes the "undetermined" locale), but an empty/whitespace value is not a
 * meaningful locale to store, so we treat it as invalid rather than silently accepting it.
 */
public class LocaleValidator implements ConstraintValidator<ValidLocale, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            // Absent field: nothing to change, so nothing to reject (sparse PATCH).
            return true;
        }
        if (value.isBlank()) {
            // setLanguageTag("") would pass (the "undetermined" tag); reject it explicitly.
            return false;
        }
        try {
            new Locale.Builder().setLanguageTag(value);
            return true;
        } catch (IllformedLocaleException ex) {
            return false;
        }
    }
}
