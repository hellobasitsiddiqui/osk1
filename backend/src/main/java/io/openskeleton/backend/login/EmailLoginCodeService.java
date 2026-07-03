package io.openskeleton.backend.login;

import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;
import io.openskeleton.backend.notification.EmailSender;
import io.openskeleton.backend.user.UserService;
import io.openskeleton.backend.web.ratelimit.TokenBucket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * The passwordless email-code login flow (OSK-129) — the DEFAULT sign-in method.
 *
 * <p>Two operations, mapped to the two public endpoints in {@link EmailCodeController}:
 *
 * <ol>
 *   <li><b>{@link #start(String)}</b> — generate a short numeric code, store it HASHED with a
 *       TTL + reset attempt counter (via {@link EmailLoginCodeStore}), and dispatch it to the
 *       address through the mockable {@link EmailSender} (a logging no-op until the Workspace
 *       SMTP sender lands — HUMAN step OSK-139). Rate-limited per email. Deliberately reveals
 *       nothing about whether the address is known (no user enumeration): it always issues +
 *       "sends" and the endpoint returns {@code 202}.</li>
 *   <li><b>{@link #verify(String, String)}</b> — validate the code (constant-time, respecting
 *       TTL, max-attempts and single-use), then resolve the caller's Firebase identity by
 *       email (reusing an existing Firebase user or creating one, marked email-verified since
 *       receiving the code proves inbox control), reconcile the persisted account via OSK-163
 *       identity reconciliation ({@code UserService.provisionFromToken}), and mint a Firebase
 *       <b>custom token</b> for that uid. The web client completes sign-in with
 *       {@code signInWithCustomToken}.</li>
 * </ol>
 *
 * <p><b>Graceful degradation.</b> {@code start} needs no Firebase at all, so it keeps working
 * even with no Application Default Credentials. {@code verify} must mint a token, so it
 * resolves the Admin client lazily via {@link ObjectProvider} and degrades to a
 * {@link EmailLoginUnavailableException} (503) — checked BEFORE the code is consumed, so an
 * infrastructure outage never burns the caller's valid code.
 *
 * <p><b>Why {@code verify} is NOT {@code @Transactional}.</b> It calls
 * {@code UserService.provisionFromToken}, whose race-recovery relies on each repository call
 * being its own transaction (OSK-76/OSK-163). The atomic code read/consume is therefore
 * isolated in {@link EmailLoginCodeStore} (its own {@code @Transactional} bean) which commits
 * before provisioning + token mint run.
 */
@Service
public class EmailLoginCodeService {

    private static final Logger log = LoggerFactory.getLogger(EmailLoginCodeService.class);

    private final EmailLoginCodeStore store;
    private final EmailLoginRateLimiter rateLimiter;
    private final EmailLoginProperties properties;
    private final EmailSender emailSender;
    private final ObjectProvider<FirebaseAuth> firebaseAuthProvider;
    private final UserService userService;
    private final Clock clock;

    /**
     * @param store the transactional code persistence seam (issue + consume)
     * @param rateLimiter per-email token-bucket throttle (reuses the OSK-63 bucket)
     * @param properties env-bound settings (code length, TTL, max attempts)
     * @param emailSender the general mockable email dispatch seam (OSK-89; logging stub until
     *     SMTP OSK-139)
     * @param firebaseAuthProvider lazy handle to the Admin client — {@code null} with no ADC
     *     (see {@code FirebaseAdminConfig}); resolved per verify so an absent client degrades
     *     to 503 only when verify is actually called
     * @param userService reused for OSK-163 identity reconciliation / JIT provisioning
     * @param clock the time source (production {@code Clock.systemUTC()}; a fixed clock in tests)
     */
    public EmailLoginCodeService(
            EmailLoginCodeStore store,
            EmailLoginRateLimiter rateLimiter,
            EmailLoginProperties properties,
            EmailSender emailSender,
            ObjectProvider<FirebaseAuth> firebaseAuthProvider,
            UserService userService,
            Clock clock) {
        this.store = store;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.emailSender = emailSender;
        this.firebaseAuthProvider = firebaseAuthProvider;
        this.userService = userService;
        this.clock = clock;
    }

    /**
     * Issue a fresh code for {@code rawEmail} and dispatch it. Rate-limited per email.
     *
     * @param rawEmail the caller-supplied email (already bean-validated as an email by the
     *     controller; normalised here defensively)
     * @throws EmailLoginRateLimitedException if the per-email start budget is exceeded (429)
     */
    public void start(String rawEmail) {
        String email = normalize(rawEmail);
        enforceRateLimit("start:" + email);

        String code = EmailLoginCodeHasher.generateNumericCode(properties.getCodeLength());
        String codeHash = EmailLoginCodeHasher.hash(email, code);
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.getTtl());

        store.issue(email, codeHash, expiresAt);
        dispatch(email, code);
        log.info("Issued email login code for {} (expires in {}).", mask(email), properties.getTtl());
    }

    /**
     * Verify a submitted code and, on success, mint a Firebase custom token for the caller's
     * identity.
     *
     * @param rawEmail the caller-supplied email
     * @param code the submitted numeric code
     * @return a freshly minted Firebase custom token the client exchanges via
     *     {@code signInWithCustomToken}
     * @throws EmailLoginRateLimitedException if the per-email verify budget is exceeded (429)
     * @throws EmailLoginUnavailableException if the Admin client is missing or a Firebase call
     *     fails (503)
     * @throws InvalidLoginCodeException if the code is wrong/expired/exhausted/reused (401)
     */
    public String verify(String rawEmail, String code) {
        String email = normalize(rawEmail);
        enforceRateLimit("verify:" + email);

        // Resolve the Admin client up front: if it is absent we degrade to 503 WITHOUT spending
        // the caller's (possibly valid) code — an outage must not force them to request a new one.
        FirebaseAuth auth = firebaseAuthProvider.getIfAvailable();
        if (auth == null) {
            throw new EmailLoginUnavailableException(
                    "Firebase Admin client is not configured; cannot mint a custom token.");
        }

        // Validate + spend the code (atomic, transactional). Any failure is a generic 401.
        String submittedHash = EmailLoginCodeHasher.hash(email, code);
        Optional<InvalidLoginCodeException.Reason> failure =
                store.consume(email, submittedHash, clock.instant(), properties.getMaxAttempts());
        if (failure.isPresent()) {
            log.debug("Rejected email login code for {} ({}).", mask(email), failure.get());
            throw new InvalidLoginCodeException(failure.get());
        }

        // Code accepted: resolve/create the Firebase identity, reconcile the persisted account
        // (OSK-163), then mint the custom token. Provisioning runs OUTSIDE any transaction (see
        // the class note) so its own race-recovery works.
        String uid = resolveOrCreateUid(auth, email);
        userService.provisionFromToken(uid, email);
        String token = mintCustomToken(auth, uid);
        log.info("Minted email-code custom token for uid '{}' ({}).", uid, mask(email));
        return token;
    }

    // ---------------------------------------------------------------------------------
    // Rate limiting
    // ---------------------------------------------------------------------------------

    /** Consume one per-email token for {@code key}, or raise a 429 carrying an honest Retry-After. */
    private void enforceRateLimit(String key) {
        TokenBucket.Decision decision = rateLimiter.tryAcquire(key);
        if (!decision.allowed()) {
            throw new EmailLoginRateLimitedException(decision.retryAfterSeconds());
        }
    }

    // ---------------------------------------------------------------------------------
    // Firebase identity resolution + token mint
    // ---------------------------------------------------------------------------------

    /**
     * Resolve the Firebase uid for {@code email}: reuse the existing Firebase user, or create
     * one (marked email-verified — receiving the code proves inbox control). A create that
     * races a concurrent one (EMAIL_ALREADY_EXISTS) recovers by re-reading; any other Firebase
     * failure degrades to 503.
     */
    private String resolveOrCreateUid(FirebaseAuth auth, String email) {
        try {
            return auth.getUserByEmail(email).getUid();
        } catch (FirebaseAuthException e) {
            if (e.getAuthErrorCode() == AuthErrorCode.USER_NOT_FOUND) {
                return createFirebaseUser(auth, email);
            }
            throw new EmailLoginUnavailableException("Failed to resolve the Firebase identity for the email.", e);
        }
    }

    /** Create a new Firebase user for {@code email}; recover from a create race by re-reading. */
    private String createFirebaseUser(FirebaseAuth auth, String email) {
        try {
            UserRecord created = auth.createUser(
                    new UserRecord.CreateRequest().setEmail(email).setEmailVerified(true));
            return created.getUid();
        } catch (FirebaseAuthException e) {
            if (e.getAuthErrorCode() == AuthErrorCode.EMAIL_ALREADY_EXISTS) {
                // A concurrent verify created the user between our get and create: re-read it.
                try {
                    return auth.getUserByEmail(email).getUid();
                } catch (FirebaseAuthException reread) {
                    throw new EmailLoginUnavailableException(
                            "Failed to resolve the Firebase identity after a create race.", reread);
                }
            }
            throw new EmailLoginUnavailableException("Failed to create a Firebase identity for the email.", e);
        }
    }

    /** Mint a Firebase custom token for {@code uid}; a Firebase failure degrades to 503. */
    private String mintCustomToken(FirebaseAuth auth, String uid) {
        try {
            return auth.createCustomToken(uid);
        } catch (FirebaseAuthException e) {
            throw new EmailLoginUnavailableException("Failed to mint a Firebase custom token.", e);
        }
    }

    // ---------------------------------------------------------------------------------
    // Dispatch + small helpers
    // ---------------------------------------------------------------------------------

    /** Build and send the code email through the mockable sender (logging stub until OSK-139). */
    private void dispatch(String email, String code) {
        long minutes =
                Math.max(1, Duration.ofMillis(properties.getTtl().toMillis()).toMinutes());
        String subject = "Your sign-in code";
        String body = "Your OpenSkeleton sign-in code is " + code + ". It expires in " + minutes
                + " minute(s). If you did not request this, you can ignore this email.";
        emailSender.send(email, subject, body);
    }

    /** Trim + lower-case an email so storage, lookup and hashing are all identity-stable. */
    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Mask an email for logging (PII): keep the first char of the local part + the whole
     * domain, hide the rest (e.g. {@code alice@example.com} -> {@code a***@example.com}).
     */
    private static String mask(String email) {
        if (email == null || email.isBlank()) {
            return "<none>";
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
