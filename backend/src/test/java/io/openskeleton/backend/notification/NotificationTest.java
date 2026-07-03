package io.openskeleton.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit test for the {@link Notification} payload record (OSK-89): the always-required-field
 * guards and the {@code of}/{@code withRoute} convenience factories.
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

        assertThat(n.data()).containsExactly(Map.entry("route", "/messages/7"));
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
