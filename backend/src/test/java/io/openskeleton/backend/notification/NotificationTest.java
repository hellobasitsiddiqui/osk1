package io.openskeleton.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.openskeleton.backend.push.PushData;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit test for the {@link Notification} payload record (OSK-89 / OSK-166): the
 * always-required-field guards, the {@code of}/{@code withRoute} convenience factories, the
 * deep-link {@code route} contract (validation + placement under {@link PushData#ROUTE}), and
 * the {@link Notification#route()} accessor.
 */
class NotificationTest {

    @Test
    void ofBuildsAPayloadWithNoDataMap() {
        Notification n = Notification.of("Subject", "Body");

        assertThat(n.subject()).isEqualTo("Subject");
        assertThat(n.body()).isEqualTo("Body");
        assertThat(n.data()).isNull();
    }

    @Test
    void withRoutePlacesTheRouteUnderTheRouteDataKey() {
        Notification n = Notification.withRoute("Subject", "Body", "/messages/7");

        // The route lands under the documented contract key, ready to ride the FCM data map.
        assertThat(n.data()).containsExactly(Map.entry(PushData.ROUTE, "/messages/7"));
        assertThat(PushData.ROUTE).isEqualTo("route");
    }

    @Test
    void routeAccessorReturnsTheRouteWhenSet() {
        Notification n = Notification.withRoute("Subject", "Body", "/messages/7");

        assertThat(n.route()).isEqualTo("/messages/7");
    }

    @Test
    void routeAccessorIsNullWhenNoDataMap() {
        // A plain (email/no-deep-link) notification carries no route.
        assertThat(Notification.of("Subject", "Body").route()).isNull();
    }

    @Test
    void routeAccessorIsNullWhenDataHasNoRouteKey() {
        Notification n = new Notification("Subject", "Body", Map.of("badge", "1"));

        assertThat(n.route()).isNull();
    }

    @Test
    void withRouteRejectsNullOrBlankRoute() {
        // A malformed route must never reach the FCM data map — reject at construction.
        assertThatThrownBy(() -> Notification.withRoute("Subject", "Body", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Notification.withRoute("Subject", "Body", "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankOrNullSubjectIsRejected() {
        assertThatThrownBy(() -> new Notification(null, "Body", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Notification("  ", "Body", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullBodyIsRejected() {
        assertThatThrownBy(() -> new Notification("Subject", null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyBodyIsAllowed() {
        // Empty body is a valid (if terse) message; only null is rejected.
        assertThat(Notification.of("Subject", "").body()).isEmpty();
    }
}
