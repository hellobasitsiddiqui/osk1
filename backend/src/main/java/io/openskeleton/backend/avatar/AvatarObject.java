package io.openskeleton.backend.avatar;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * PURE, server-side validation of an uploaded avatar object (OSK-83).
 *
 * <p><b>Why this exists.</b> The avatar bytes are uploaded <i>directly</i> by the browser to
 * Cloud Storage under the caller's own {@code users/{uid}/…} prefix (the Firebase Storage Web SDK,
 * authorised by the caller's ID token against the committed {@code storage.rules}). The backend is
 * then handed the resulting {@code objectPath} + {@code downloadUrl} so it can set the user's
 * Firebase {@code photoURL}. A client can lie about either value, so before the backend trusts them
 * to mint a {@code photoURL} it re-validates them here — defence-in-depth <i>on top of</i> the
 * storage rules, not instead of them:
 * <ul>
 *   <li><b>ownership</b> — the object path must sit under the CALLER's own {@code users/{uid}/}
 *       prefix (a client can never point the backend at another user's object), and must not try to
 *       traverse out of it ({@code ..} / backslashes rejected);</li>
 *   <li><b>content type</b> — the path must end in an allowed image extension (png/jpg/jpeg/webp/gif),
 *       so a non-image object can't become an avatar. The hard content-type + size ceiling is ALSO
 *       enforced at write time by {@code storage.rules} (tightened by this ticket); this is the
 *       server-side echo of that gate;</li>
 *   <li><b>URL binding</b> — the download URL must be an {@code https} Firebase-Storage URL that
 *       references OUR bucket AND the very object path just validated, so the client can't validate a
 *       benign path but hand us an arbitrary {@code photoURL} to store.</li>
 * </ul>
 *
 * <p>Everything here is a pure static function of its arguments (no Firebase, no I/O, no globals), so
 * it is exhaustively unit-testable without live credentials — mirroring the {@code account} package's
 * split of pure projection ({@code FirebaseAccountState}) from the live lookup service.
 */
public final class AvatarObject {

    private AvatarObject() {}

    /**
     * Allowed avatar file extensions (lower-case, no dot). {@code jpg} and {@code jpeg} are both
     * accepted because browsers name JPEG files either way while sending {@code image/jpeg}. Kept in
     * sync with the client-side allow-list in {@code web/profile.js} and the {@code contentType}
     * matcher in {@code storage.rules}.
     */
    static final Set<String> ALLOWED_EXTENSIONS = Set.of("png", "jpg", "jpeg", "webp", "gif");

    /**
     * Hosts a genuine Firebase Storage download URL is served from. {@code firebasestorage.googleapis.com}
     * is the tokenised {@code getDownloadURL()} host; {@code storage.googleapis.com} is the GCS host used
     * for public/OAuth object URLs. Anything else is rejected so a client can't smuggle in an arbitrary URL.
     */
    private static final Set<String> ALLOWED_HOSTS = Set.of("firebasestorage.googleapis.com", "storage.googleapis.com");

    /** Hard ceiling mirrored by {@code storage.rules} + the client-side check: 5 MiB. */
    static final long MAX_BYTES = 5L * 1024 * 1024;

    /**
     * Validate an avatar object reference, throwing {@link InvalidAvatarException} (→ 400) on any
     * violation. Returns normally only when the path is the caller's own image object AND the download
     * URL is a Firebase-Storage URL that references that same object in our bucket.
     *
     * @param uid the verified caller uid (from the auth filter — never a request body)
     * @param objectPath the storage object path the client uploaded to (e.g. {@code users/<uid>/avatar/…})
     * @param downloadUrl the tokenised download URL the client obtained from {@code getDownloadURL()}
     * @param bucket the expected storage bucket (config-driven), or {@code null}/blank to skip the bucket check
     * @throws InvalidAvatarException if any of the identity/ownership/type/URL invariants fail
     */
    public static void validate(String uid, String objectPath, String downloadUrl, String bucket) {
        if (uid == null || uid.isBlank()) {
            throw new InvalidAvatarException("Missing caller identity.");
        }
        if (objectPath == null || objectPath.isBlank()) {
            throw new InvalidAvatarException("Missing avatar object path.");
        }
        if (downloadUrl == null || downloadUrl.isBlank()) {
            throw new InvalidAvatarException("Missing avatar download URL.");
        }

        String normalized = objectPath.strip();
        // Reject any traversal/escape attempt outright before the prefix check, so a crafted
        // "users/<uid>/../<other>/x.png" can never be massaged into another user's area.
        if (normalized.contains("..") || normalized.contains("\\")) {
            throw new InvalidAvatarException("Invalid avatar object path.");
        }
        String prefix = "users/" + uid + "/";
        if (!normalized.startsWith(prefix)) {
            throw new InvalidAvatarException("Avatar must be stored under your own user folder.");
        }
        if (!hasAllowedImageExtension(normalized)) {
            throw new InvalidAvatarException("Avatar must be a PNG, JPEG, WebP or GIF image.");
        }
        if (!isValidDownloadUrl(downloadUrl, bucket, normalized)) {
            throw new InvalidAvatarException("Avatar download URL does not reference the uploaded object.");
        }
    }

    /** True iff {@code path} ends with one of {@link #ALLOWED_EXTENSIONS} (case-insensitive). */
    static boolean hasAllowedImageExtension(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) {
            return false;
        }
        String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ALLOWED_EXTENSIONS.contains(ext);
    }

    /**
     * True iff {@code downloadUrl} is a well-formed {@code https} URL on an allowed Firebase-Storage
     * host that references BOTH the expected {@code bucket} (when supplied) AND the given
     * {@code objectPath} (either raw or with {@code /} percent-encoded as {@code %2F}, which is how
     * Firebase encodes the object in the tokenised download URL). Binding the URL to the already-
     * validated object path is what stops a client validating a benign path but handing us an
     * unrelated {@code photoURL}.
     */
    static boolean isValidDownloadUrl(String downloadUrl, String bucket, String objectPath) {
        String raw = downloadUrl.strip();
        URI uri;
        try {
            uri = URI.create(raw);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        String host = uri.getHost();
        if (host == null || !ALLOWED_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
            return false;
        }
        if (bucket != null && !bucket.isBlank() && !raw.contains(bucket)) {
            return false;
        }
        String encoded = objectPath.replace("/", "%2F");
        return raw.contains(objectPath) || raw.contains(encoded);
    }
}
