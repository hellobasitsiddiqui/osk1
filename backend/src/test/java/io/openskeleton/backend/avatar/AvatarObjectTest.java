package io.openskeleton.backend.avatar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AvatarObject} — the PURE, server-side validation of an uploaded avatar
 * reference (OSK-83). No Firebase / I/O, so every ownership / content-type / URL-binding branch is
 * exercised directly.
 */
class AvatarObjectTest {

    private static final String UID = "uid-123";
    private static final String BUCKET = "openskeleton-one.firebasestorage.app";
    // A representative genuine Firebase download URL for users/uid-123/avatar/1.png.
    private static final String VALID_URL = "https://firebasestorage.googleapis.com/v0/b/" + BUCKET
            + "/o/users%2Fuid-123%2Favatar%2F1.png?alt=media&token=abc-123";

    @Nested
    class Validate {

        @Test
        void acceptsAnOwnedImageWithAMatchingDownloadUrl() {
            assertThatCode(() -> AvatarObject.validate(UID, "users/uid-123/avatar/1.png", VALID_URL, BUCKET))
                    .doesNotThrowAnyException();
        }

        @Test
        void rejectsAMissingOrBlankIdentity() {
            assertThatThrownBy(() -> AvatarObject.validate(null, "users/uid-123/avatar/1.png", VALID_URL, BUCKET))
                    .isInstanceOf(InvalidAvatarException.class);
            assertThatThrownBy(() -> AvatarObject.validate("  ", "users/uid-123/avatar/1.png", VALID_URL, BUCKET))
                    .isInstanceOf(InvalidAvatarException.class);
        }

        @Test
        void rejectsBlankObjectPathOrDownloadUrl() {
            assertThatThrownBy(() -> AvatarObject.validate(UID, "  ", VALID_URL, BUCKET))
                    .isInstanceOf(InvalidAvatarException.class);
            assertThatThrownBy(() -> AvatarObject.validate(UID, "users/uid-123/avatar/1.png", "  ", BUCKET))
                    .isInstanceOf(InvalidAvatarException.class);
        }

        @Test
        void rejectsAnObjectPathOutsideTheCallersOwnFolder() {
            // Another user's uid in the path — the core ownership guard.
            assertThatThrownBy(() -> AvatarObject.validate(
                            UID,
                            "users/someone-else/avatar/1.png",
                            "https://firebasestorage.googleapis.com/v0/b/" + BUCKET
                                    + "/o/users%2Fsomeone-else%2Favatar%2F1.png?alt=media&token=x",
                            BUCKET))
                    .isInstanceOf(InvalidAvatarException.class)
                    .hasMessageContaining("your own");
        }

        @Test
        void rejectsPathTraversalOutOfTheUserArea() {
            assertThatThrownBy(() ->
                            AvatarObject.validate(UID, "users/uid-123/../uid-999/avatar/1.png", VALID_URL, BUCKET))
                    .isInstanceOf(InvalidAvatarException.class);
            assertThatThrownBy(() -> AvatarObject.validate(UID, "users/uid-123\\avatar\\1.png", VALID_URL, BUCKET))
                    .isInstanceOf(InvalidAvatarException.class);
        }

        @Test
        void rejectsANonImageExtension() {
            assertThatThrownBy(() -> AvatarObject.validate(
                            UID,
                            "users/uid-123/avatar/malware.exe",
                            "https://firebasestorage.googleapis.com/v0/b/" + BUCKET
                                    + "/o/users%2Fuid-123%2Favatar%2Fmalware.exe?alt=media&token=x",
                            BUCKET))
                    .isInstanceOf(InvalidAvatarException.class)
                    .hasMessageContaining("PNG");
        }

        @Test
        void rejectsADownloadUrlThatDoesNotReferenceTheObject() {
            // Well-formed https + allowed host + bucket, but a DIFFERENT object than the path.
            String mismatched = "https://firebasestorage.googleapis.com/v0/b/" + BUCKET
                    + "/o/users%2Fuid-123%2Favatar%2FOTHER.png?alt=media&token=x";
            assertThatThrownBy(() -> AvatarObject.validate(UID, "users/uid-123/avatar/1.png", mismatched, BUCKET))
                    .isInstanceOf(InvalidAvatarException.class)
                    .hasMessageContaining("does not reference");
        }

        @Test
        void rejectsADownloadUrlOnAnUntrustedHost() {
            assertThatThrownBy(() -> AvatarObject.validate(
                            UID,
                            "users/uid-123/avatar/1.png",
                            "https://evil.example.com/users%2Fuid-123%2Favatar%2F1.png",
                            BUCKET))
                    .isInstanceOf(InvalidAvatarException.class);
        }

        @Test
        void rejectsADownloadUrlForTheWrongBucket() {
            String wrongBucket = "https://firebasestorage.googleapis.com/v0/b/other-bucket.app"
                    + "/o/users%2Fuid-123%2Favatar%2F1.png?alt=media&token=x";
            assertThatThrownBy(() -> AvatarObject.validate(UID, "users/uid-123/avatar/1.png", wrongBucket, BUCKET))
                    .isInstanceOf(InvalidAvatarException.class);
        }

        @Test
        void acceptsAnHttpUrlThatSpellsTheObjectPathUnencoded() {
            // storage.googleapis.com style URL with the raw (unencoded) path also binds correctly.
            String gcs = "https://storage.googleapis.com/" + BUCKET + "/users/uid-123/avatar/1.jpeg";
            assertThatCode(() -> AvatarObject.validate(UID, "users/uid-123/avatar/1.jpeg", gcs, BUCKET))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    class Extensions {

        @Test
        void acceptsEveryAllowedImageExtensionCaseInsensitively() {
            assertThat(AvatarObject.hasAllowedImageExtension("a/b/c.PNG")).isTrue();
            assertThat(AvatarObject.hasAllowedImageExtension("a.jpg")).isTrue();
            assertThat(AvatarObject.hasAllowedImageExtension("a.jpeg")).isTrue();
            assertThat(AvatarObject.hasAllowedImageExtension("a.webp")).isTrue();
            assertThat(AvatarObject.hasAllowedImageExtension("a.gif")).isTrue();
        }

        @Test
        void rejectsMissingOrDisallowedExtensions() {
            assertThat(AvatarObject.hasAllowedImageExtension("noextension")).isFalse();
            assertThat(AvatarObject.hasAllowedImageExtension("trailingdot.")).isFalse();
            assertThat(AvatarObject.hasAllowedImageExtension("a.svg")).isFalse();
            assertThat(AvatarObject.hasAllowedImageExtension("a.exe")).isFalse();
        }
    }

    @Nested
    class DownloadUrl {

        @Test
        void bucketCheckIsSkippedWhenNoBucketConfigured() {
            // A null/blank expected bucket means "don't enforce the bucket" — still host + object bound.
            assertThat(AvatarObject.isValidDownloadUrl(VALID_URL, null, "users/uid-123/avatar/1.png"))
                    .isTrue();
            assertThat(AvatarObject.isValidDownloadUrl(VALID_URL, "  ", "users/uid-123/avatar/1.png"))
                    .isTrue();
        }

        @Test
        void rejectsNonHttpsSchemes() {
            String http =
                    "http://firebasestorage.googleapis.com/v0/b/" + BUCKET + "/o/users%2Fuid-123%2Favatar%2F1.png";
            assertThat(AvatarObject.isValidDownloadUrl(http, BUCKET, "users/uid-123/avatar/1.png"))
                    .isFalse();
        }

        @Test
        void rejectsAGarbageUrl() {
            assertThat(AvatarObject.isValidDownloadUrl("not a url ::::", BUCKET, "users/uid-123/avatar/1.png"))
                    .isFalse();
        }
    }
}
