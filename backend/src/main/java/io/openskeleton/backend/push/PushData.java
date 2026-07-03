package io.openskeleton.backend.push;

/**
 * The contract for the FCM message {@code data} payload — the small string/string map that
 * rides <i>alongside</i> the visible notification (title/body) and is handed to the client
 * app when a push arrives or is tapped (OSK-166).
 *
 * <p><b>Why a dedicated constants home.</b> The {@code data} map is a wire contract shared
 * with the mobile client (the OSK-156 tap-handler reads these keys to decide where to
 * navigate). Both the producer side ({@link io.openskeleton.backend.notification.Notification})
 * and the delivery side ({@link PushNotificationService}) need to agree on the exact key
 * strings, so they live here once — as documented constants — rather than as magic string
 * literals duplicated across packages that could silently drift apart.
 *
 * <p><b>These values are a client-facing contract: do not rename them.</b> The string values
 * (e.g. {@code "route"}) are what the mobile app matches on. Changing a value here without a
 * coordinated client change would break deep-linking, so the value is fixed even if the Java
 * constant name changes.
 *
 * <p>Pure constants holder — never instantiated (private constructor).
 */
public final class PushData {

    /**
     * The {@code data} key carrying an in-app deep-link route (OSK-166). When present, the
     * value is an app-relative route (e.g. {@code "/messages/42"}) that the mobile
     * tap-handler (OSK-156) navigates to when the user taps the notification, so a tap lands
     * on the correct screen rather than the app's default home.
     *
     * <p>This backend never interprets the route — it only forwards it verbatim in the FCM
     * {@code data} map. It is included only when a route is actually set, and omitted cleanly
     * (no empty/blank {@code route} key) otherwise.
     */
    public static final String ROUTE = "route";

    /** Not instantiable — this is a constants holder, not a component. */
    private PushData() {
        throw new AssertionError("PushData is a constants holder and must not be instantiated");
    }
}
