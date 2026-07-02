package io.openskeleton.backend.auth;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resilience decorator around the {@link TokenVerifier} seam that gives the app's one
 * outbound dependency — Firebase Admin SDK token verification — graceful degradation
 * (OSK-65).
 *
 * <p><b>What it adds around the delegate (usually {@link FirebaseTokenVerifier}):</b>
 * <ul>
 *   <li><b>TimeLimiter</b> — each verify runs on a bounded executor and is capped by a
 *       hard timeout. A hung call is cancelled/interrupted and surfaced as a failure
 *       rather than blocking the servlet thread forever.</li>
 *   <li><b>Retry</b> — a <i>transient</i> fault is retried a bounded number of times
 *       with exponential backoff. Timeouts, an open breaker, and real token rejections
 *       are explicitly NOT retried (see {@code application.yml} ignore-exceptions).</li>
 *   <li><b>CircuitBreaker</b> — once failures cross a threshold the breaker opens and
 *       every call fast-fails with {@code CallNotPermittedException} for a cool-off
 *       window, so a sick dependency sheds load instead of piling up threads.</li>
 * </ul>
 *
 * <p><b>The one invariant that matters most:</b> an <i>invalid token</i> stays a
 * {@code 401}; only <i>infrastructure faults</i> degrade to {@code 503}. The delegate
 * signals a bad token with a checked {@link TokenVerificationException}; this class
 * wraps it in an {@link AuthFailureWrapper} purely so it can travel out of the async
 * worker, tells the breaker and retry to ignore that wrapper, and then unwraps it back
 * to the original exception. Every other failure (timeout, breaker-open, exhausted
 * retries) is rethrown as an {@link AuthDependencyUnavailableException}, which the
 * filter maps to a degraded {@code 503}. That separation is why bad tokens can never
 * open the breaker or be masked as a 503, and a real outage can never masquerade as a
 * 401.
 *
 * <p><b>Why programmatic composition (not {@code @CircuitBreaker}/{@code @Retry}
 * annotations):</b> the annotations rely on Spring AOP proxying, which does not apply
 * to self-invocation and forces a {@code CompletableFuture}-returning method signature
 * onto the seam. Composing the operators by hand keeps the {@link TokenVerifier}
 * contract unchanged, makes the ordering explicit, and lets the whole thing be
 * unit-tested with plain JUnit (no Spring context) — important because this class,
 * unlike the excluded {@code FirebaseTokenVerifier} adapter, IS under the coverage
 * gate.
 */
public class ResilientTokenVerifier implements TokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(ResilientTokenVerifier.class);

    /**
     * Concurrency ceiling for in-flight verify calls. Offloading to a small, bounded
     * pool is what makes the timeout enforceable (the servlet thread waits on a
     * {@link Future} it can cancel) AND caps how many threads a hung dependency can
     * consume before the breaker trips and starts shedding load.
     */
    private static final int EXECUTOR_POOL_SIZE = 16;

    /** Bounded backlog; once pool + queue are full, extra calls are rejected (load-shed → 503). */
    private static final int EXECUTOR_QUEUE_CAPACITY = 32;

    private final TokenVerifier delegate;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final TimeLimiter timeLimiter;
    private final ExecutorService executor;

    /**
     * Production constructor: wires the delegate + the three Resilience4j operators
     * (looked up from the auto-configured registries) and creates a private bounded
     * executor for the timed-out calls.
     */
    public ResilientTokenVerifier(
            TokenVerifier delegate, CircuitBreaker circuitBreaker, Retry retry, TimeLimiter timeLimiter) {
        this(delegate, circuitBreaker, retry, timeLimiter, defaultExecutor());
    }

    /**
     * Test/DI-friendly constructor allowing the executor to be supplied (e.g. a
     * deterministic pool, or one shared across tests).
     */
    ResilientTokenVerifier(
            TokenVerifier delegate,
            CircuitBreaker circuitBreaker,
            Retry retry,
            TimeLimiter timeLimiter,
            ExecutorService executor) {
        this.delegate = delegate;
        this.circuitBreaker = circuitBreaker;
        this.retry = retry;
        this.timeLimiter = timeLimiter;
        this.executor = executor;
    }

    /**
     * Verifies the token through the full resilience pipeline.
     *
     * <p>The operators are composed <b>Retry(CircuitBreaker(TimeLimiter(call)))</b> —
     * retry outermost — so each attempt goes through the breaker and gets its own
     * timeout, and the breaker records every attempt.
     *
     * @throws TokenVerificationException if the token is genuinely invalid (→ filter renders 401)
     * @throws AuthDependencyUnavailableException if the dependency is unavailable —
     *     timeout, open breaker, or exhausted retries (→ filter renders a degraded 503)
     */
    @Override
    public VerifiedToken verify(String idToken) throws TokenVerificationException {
        // Each (attempt) submits a fresh task to the bounded pool and returns a Future
        // the TimeLimiter can wait on and cancel(true) — cancel interrupts the worker,
        // which is why we use executor.submit (a plain FutureTask) rather than
        // CompletableFuture.supplyAsync (whose cancel does NOT interrupt the thread).
        Supplier<Future<VerifiedToken>> futureSupplier = () -> executor.submit(() -> invokeDelegate(idToken));

        Callable<VerifiedToken> timeLimited = TimeLimiter.decorateFutureSupplier(timeLimiter, futureSupplier);
        Callable<VerifiedToken> breakered = CircuitBreaker.decorateCallable(circuitBreaker, timeLimited);
        Callable<VerifiedToken> resilient = Retry.decorateCallable(retry, breakered);

        try {
            return resilient.call();
        } catch (AuthFailureWrapper wrapper) {
            // A real token rejection travelled out through the async boundary — restore
            // the original checked exception so the filter still returns 401.
            throw wrapper.unwrap();
        } catch (Exception e) {
            // Timeout, open breaker (CallNotPermittedException), or a transient fault
            // that survived every retry: the dependency could not give a trustworthy
            // answer in time. Degrade to a fail-fast 503 rather than hang the request.
            log.warn("Token verification degraded ({}): {}", e.getClass().getSimpleName(), e.getMessage());
            throw new AuthDependencyUnavailableException(
                    "Token verification dependency is unavailable (timeout, open breaker, or exhausted retries).", e);
        }
    }

    /**
     * Runs the real verification, translating the delegate's checked
     * {@link TokenVerificationException} into an unchecked {@link AuthFailureWrapper}
     * so it can propagate out of the executor's {@link Future}.
     */
    private VerifiedToken invokeDelegate(String idToken) {
        try {
            return delegate.verify(idToken);
        } catch (TokenVerificationException e) {
            throw new AuthFailureWrapper(e);
        }
    }

    /** Builds the private, bounded, daemon-threaded executor used to time-box verify calls. */
    private static ExecutorService defaultExecutor() {
        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                // Daemon so a stuck verify can never keep the JVM alive at shutdown.
                Thread t = new Thread(r, "token-verify-" + counter.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        return new ThreadPoolExecutor(
                EXECUTOR_POOL_SIZE,
                EXECUTOR_POOL_SIZE,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(EXECUTOR_QUEUE_CAPACITY),
                threadFactory,
                // Saturated pool + queue => reject, surfaced as a 503 (load-shedding),
                // never an unbounded thread/queue growth.
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Releases the executor. Spring auto-detects this as the bean destroy method, so
     * the pool is torn down with the application context; tests call it directly.
     */
    public void shutdown() {
        executor.shutdownNow();
    }
}
