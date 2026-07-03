package io.openskeleton.backend.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Fast, Spring-free unit tests for the OSK-67 profile-field constraint validators. Each
 * validator ignores the {@code ConstraintValidatorContext} (it only returns true/false), so
 * we drive {@code isValid(value, null)} directly and cover all three branches — {@code null}
 * (the sparse-PATCH "not provided" pass), a valid value, and an invalid value.
 */
class ProfileFieldValidatorsTest {

    private final TimezoneValidator timezone = new TimezoneValidator();
    private final LocaleValidator locale = new LocaleValidator();

    @Test
    void timezoneNullIsValid() {
        assertThat(timezone.isValid(null, null)).isTrue();
    }

    @Test
    void timezoneValidIanaZoneIsValid() {
        assertThat(timezone.isValid("Europe/London", null)).isTrue();
        assertThat(timezone.isValid("America/New_York", null)).isTrue();
        assertThat(timezone.isValid("UTC", null)).isTrue();
    }

    @Test
    void timezoneUnknownOrMalformedIsInvalid() {
        assertThat(timezone.isValid("Mars/Phobos", null)).isFalse();
        assertThat(timezone.isValid("Not a zone!", null)).isFalse();
        assertThat(timezone.isValid("", null)).isFalse();
    }

    @Test
    void localeNullIsValid() {
        assertThat(locale.isValid(null, null)).isTrue();
    }

    @Test
    void localeWellFormedBcp47TagIsValid() {
        assertThat(locale.isValid("en-GB", null)).isTrue();
        assertThat(locale.isValid("fr", null)).isTrue();
        assertThat(locale.isValid("zh-Hant-TW", null)).isTrue();
    }

    @Test
    void localeMalformedOrBlankIsInvalid() {
        // Underscore is not a valid BCP-47 separator (should be a hyphen).
        assertThat(locale.isValid("en_GB", null)).isFalse();
        // A single-char primary subtag is ill-formed.
        assertThat(locale.isValid("e", null)).isFalse();
        // Blank is rejected explicitly (setLanguageTag("") would otherwise pass).
        assertThat(locale.isValid("", null)).isFalse();
        assertThat(locale.isValid("   ", null)).isFalse();
    }
}
