package io.openskeleton.backend.user;

import io.openskeleton.backend.auth.FirebaseAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Stamps {@code last_active_at} on every authenticated request (OSK-73), by delegating to the
 * throttled {@link LastActiveService}.
 *
 * <p><b>Why an interceptor (not the auth filter).</b> The stamp must happen for <i>all</i>
 * authenticated {@code /api/v1/**} traffic, not just {@code /me}. An MVC {@link HandlerInterceptor}
 * runs after {@link FirebaseAuthenticationFilter} has verified the token and published the caller's
 * uid as a request attribute, so we can read that verified identity here without re-verifying — and
 * without modifying the auth filter or its wiring ({@code FirebaseAuthConfig}), keeping this change
 * additive and conflict-free with parallel auth work.
 *
 * <p><b>Ordering / visibility.</b> {@code preHandle} runs before the controller, so for an already
 * provisioned caller the (committed, throttled) update lands before {@code MeController} reads the
 * user back — meaning {@code GET /me} reflects the current activity. The interceptor is registered
 * only for the protected API prefix and, defensively, only stamps when the auth attribute is present
 * (an unauthenticated request is short-circuited by the filter and never reaches here); the
 * null-handling and throttling live in {@link LastActiveService#touch(String)}.
 */
public class LastActiveInterceptor implements HandlerInterceptor {

    private final LastActiveService lastActiveService;

    public LastActiveInterceptor(LastActiveService lastActiveService) {
        this.lastActiveService = lastActiveService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // The auth filter publishes the verified uid as a String (or nothing, for a request it
        // already rejected — which never reaches here). The cast is safe: only the filter writes this
        // attribute, and always a String. touch(...) no-ops on a null uid and self-throttles, so this
        // stays branch-free.
        lastActiveService.touch((String) request.getAttribute(FirebaseAuthenticationFilter.UID_ATTRIBUTE));
        return true;
    }
}
