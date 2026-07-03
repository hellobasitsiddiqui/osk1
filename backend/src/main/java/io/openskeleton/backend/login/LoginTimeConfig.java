package io.openskeleton.backend.login;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the {@link Clock} that {@link EmailLoginCodeService} injects as its time source
 * (OSK-129), so the TTL/expiry logic can be driven deterministically from tests with a fixed
 * clock rather than the wall clock.
 *
 * <p><b>Merge-safe by design.</b> {@code Clock} is a natural shared dependency several features
 * might want, so this bean is declared {@link ConditionalOnMissingBean @ConditionalOnMissingBean}:
 * if any other configuration already contributes a {@code Clock}, this one backs off and the
 * existing bean is used. That means adding this cannot produce a duplicate-bean conflict at
 * merge time — whichever {@code Clock} is registered first wins and the rest are inert.
 */
@Configuration
class LoginTimeConfig {

    /** The system UTC clock — the sensible production default when nothing else provides one. */
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock clock() {
        return Clock.systemUTC();
    }
}
