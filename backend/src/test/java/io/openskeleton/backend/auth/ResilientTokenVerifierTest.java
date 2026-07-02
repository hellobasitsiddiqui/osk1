package io.openskeleton.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.openskeleton.backend.auth.TokenVerifier.VerifiedToken;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the OSK-65 resilience decorator, exercised WITHOUT a Spring context so
 * every branch (success, 401-preserving token rejection, retry, timeout, exhausted
 * retries, open-breaker fast-fail) is asserted deterministically and fast. This class
 * is under the coverage gate (unlike the excluded {@code FirebaseTokenVerifier}), so
 * these tests carry its line/branch coverage.
 *
 * <p>The Resilience4j operators are built by hand with tight, test-sized configs
 * (millisecond timeouts, tiny windows) so timing-sensitive behaviour is both quick and
 * stable.
 */
class ResilientTokenVerifierTest {

    private static final String TOKEN = "raw-token";
    private static final VerifiedToken IDENTITY = new VerifiedToken("uid-1", "user@example.com");

    /** Every verifier built here is tracked so its executor is torn down after the test. */
    private final List<ResilientTokenVerifier> created = new ArrayList<>();

    private final AtomicInteger nameSeq = new AtomicInteger();

    @AfterEach
    void tearDown() {
        created.forEach(ResilientTokenVerifier::shutdown);
    }

    // --- Happy path ------------------------------------------------------------

    @Test
    void returnsVerifiedIdentity_whenDelegateSucceeds() throws Exception {
        TokenVerifier delegate = mock(TokenVerifier.class);
        when(delegate.verify(TOKEN)).thenReturn(IDENTITY);

        ResilientTokenVerifier resilient = newVerifier(delegate, retry(3), closedBreaker(), timeout(300));

        assertThat(resilient.verify(TOKEN)).isEqualTo(IDENTITY);
        verify(delegate, times(1)).verify(TOKEN);
    }

    // --- 401 invariant: a bad token stays a 401 and never trips resilience ------

    @Test
    void preservesTokenVerificationException_withoutRetryingOrCountingAgainstBreaker() throws Exception {
        TokenVerifier delegate = mock(TokenVerifier.class);
        when(delegate.verify(any())).thenThrow(new TokenVerificationException("expired"));
        CircuitBreaker breaker = closedBreaker();

        ResilientTokenVerifier resilient = newVerifier(delegate, retry(3), breaker, timeout(300));

        // The original checked exception is restored — the filter still renders 401.
        assertThatThrownBy(() -> resilient.verify(TOKEN))
                .isInstanceOf(TokenVerificationException.class)
                .isNotInstanceOf(AuthDependencyUnavailableException.class);

        // Called exactly once (a deterministic rejection is NOT retried) and the breaker
        // recorded zero failures (a bad token must never open it).
        verify(delegate, times(1)).verify(TOKEN);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // --- Retry with backoff on transient faults --------------------------------

    @Test
    void retriesTransientFailure_thenSucceeds() throws Exception {
        TokenVerifier delegate = mock(TokenVerifier.class);
        when(delegate.verify(TOKEN))
                .thenThrow(new IllegalStateException("transient blip"))
                .thenReturn(IDENTITY);

        ResilientTokenVerifier resilient = newVerifier(delegate, retry(3), closedBreaker(), timeout(300));

        assertThat(resilient.verify(TOKEN)).isEqualTo(IDENTITY);
        verify(delegate, times(2)).verify(TOKEN); // 1 failed attempt + 1 successful retry
    }

    @Test
    void exhaustsBoundedRetries_thenDegradesTo503Signal() throws Exception {
        TokenVerifier delegate = mock(TokenVerifier.class);
        when(delegate.verify(any())).thenThrow(new IllegalStateException("still down"));

        ResilientTokenVerifier resilient = newVerifier(delegate, retry(3), closedBreaker(), timeout(300));

        assertThatThrownBy(() -> resilient.verify(TOKEN)).isInstanceOf(AuthDependencyUnavailableException.class);
        verify(delegate, times(3)).verify(TOKEN); // attempts are BOUNDED at maxAttempts, not infinite
    }

    // --- TimeLimiter: a hung verify returns promptly, not a hang ----------------

    @Test
    void timesOutPromptly_whenDelegateHangs() throws Exception {
        TokenVerifier delegate = mock(TokenVerifier.class);
        when(delegate.verify(any())).thenAnswer(inv -> {
            Thread.sleep(2_000); // far longer than the 200ms timeout below
            return IDENTITY;
        });

        ResilientTokenVerifier resilient = newVerifier(delegate, retry(3), closedBreaker(), timeout(200));

        long start = System.nanoTime();
        assertThatThrownBy(() -> resilient.verify(TOKEN)).isInstanceOf(AuthDependencyUnavailableException.class);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        // Returned on the timeout, NOWHERE near the 2s the delegate would have slept.
        assertThat(elapsedMs).isLessThan(1_500);
    }

    // --- CircuitBreaker: opens on repeated failure, then fails fast -------------

    @Test
    void opensBreakerOnRepeatedFailure_thenFailsFastWithoutCallingDelegate() throws Exception {
        TokenVerifier delegate = mock(TokenVerifier.class);
        when(delegate.verify(any())).thenThrow(new IllegalStateException("down"));

        // A 2-call window that opens at a 50% failure rate, with retry disabled so each
        // verify() is exactly one breaker call — makes the counting deterministic.
        CircuitBreaker breaker = CircuitBreaker.of(
                "cb-" + nameSeq.incrementAndGet(),
                CircuitBreakerConfig.custom()
                        .slidingWindowType(SlidingWindowType.COUNT_BASED)
                        .slidingWindowSize(2)
                        .minimumNumberOfCalls(2)
                        .failureRateThreshold(50f)
                        .waitDurationInOpenState(Duration.ofSeconds(10))
                        .ignoreExceptions(AuthFailureWrapper.class)
                        .build());
        ResilientTokenVerifier resilient = newVerifier(delegate, retry(1), breaker, timeout(300));

        // Two real failures trip the breaker...
        assertThatThrownBy(() -> resilient.verify(TOKEN)).isInstanceOf(AuthDependencyUnavailableException.class);
        assertThatThrownBy(() -> resilient.verify(TOKEN)).isInstanceOf(AuthDependencyUnavailableException.class);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // ...the third call is short-circuited: still a 503 signal, but the delegate is
        // never invoked (fail-fast, no thread piled up on the sick dependency).
        assertThatThrownBy(() -> resilient.verify(TOKEN))
                .isInstanceOf(AuthDependencyUnavailableException.class)
                .hasCauseInstanceOf(CallNotPermittedException.class);
        verify(delegate, times(2)).verify(TOKEN);
    }

    // --- Constructor variants --------------------------------------------------

    @Test
    void publicConstructorUsesItsOwnBoundedExecutor() throws Exception {
        TokenVerifier delegate = mock(TokenVerifier.class);
        when(delegate.verify(TOKEN)).thenReturn(IDENTITY);

        // Public ctor => default bounded executor path (defaultExecutor()).
        ResilientTokenVerifier resilient = new ResilientTokenVerifier(
                delegate,
                closedBreaker(),
                Retry.of("r-" + nameSeq.incrementAndGet(), retry(1)),
                TimeLimiter.of("t-" + nameSeq.incrementAndGet(), timeout(300)));
        created.add(resilient);

        assertThat(resilient.verify(TOKEN)).isEqualTo(IDENTITY);
    }

    @Test
    void injectedExecutorConstructorIsUsed() throws Exception {
        TokenVerifier delegate = mock(TokenVerifier.class);
        when(delegate.verify(TOKEN)).thenReturn(IDENTITY);
        ExecutorService injected = Executors.newFixedThreadPool(2);
        try {
            ResilientTokenVerifier resilient = new ResilientTokenVerifier(
                    delegate,
                    closedBreaker(),
                    Retry.of("r-" + nameSeq.incrementAndGet(), retry(1)),
                    TimeLimiter.of("t-" + nameSeq.incrementAndGet(), timeout(300)),
                    injected);

            assertThat(resilient.verify(TOKEN)).isEqualTo(IDENTITY);
        } finally {
            injected.shutdownNow();
        }
    }

    // --- Helpers ---------------------------------------------------------------

    /** Builds a verifier via the public constructor (its own bounded executor) and tracks it for teardown. */
    private ResilientTokenVerifier newVerifier(
            TokenVerifier delegate, RetryConfig retryConfig, CircuitBreaker breaker, TimeLimiterConfig tlConfig) {
        int n = nameSeq.incrementAndGet();
        ResilientTokenVerifier v = new ResilientTokenVerifier(
                delegate, breaker, Retry.of("retry-" + n, retryConfig), TimeLimiter.of("tl-" + n, tlConfig));
        created.add(v);
        return v;
    }

    /** Retry config mirroring production's ignore-list; {@code maxAttempts} of 1 disables retry. */
    private static RetryConfig retry(int maxAttempts) {
        return RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .waitDuration(Duration.ofMillis(10))
                .ignoreExceptions(AuthFailureWrapper.class, TimeoutException.class, CallNotPermittedException.class)
                .build();
    }

    /** A breaker with a huge minimum-call floor so it never opens during a test that doesn't want it to. */
    private CircuitBreaker closedBreaker() {
        return CircuitBreaker.of(
                "cb-" + nameSeq.incrementAndGet(),
                CircuitBreakerConfig.custom()
                        .slidingWindowType(SlidingWindowType.COUNT_BASED)
                        .slidingWindowSize(100)
                        .minimumNumberOfCalls(100)
                        .ignoreExceptions(AuthFailureWrapper.class)
                        .build());
    }

    private static TimeLimiterConfig timeout(long millis) {
        return TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofMillis(millis))
                .cancelRunningFuture(true)
                .build();
    }
}
