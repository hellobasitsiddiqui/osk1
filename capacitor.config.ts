/**
 * Capacitor configuration for the OpenSkeleton mobile shell (OSK-146).
 *
 * WHAT this does: tells the Capacitor CLI how to turn the existing static web
 * app under `web/` into a native Android app. `npx cap sync android` reads this
 * file, copies the contents of `webDir` into the Android project's assets, and
 * the native WebView then loads that bundled copy of the web app on launch.
 *
 * KEY PRINCIPLE (mobile epic root): mobile is a *hybrid over the web codebase*,
 * NOT a native rewrite. `web/` (index/app/status/ops/help .html + config.js /
 * auth.js + the feature modules) IS the application. Capacitor only provides the
 * native shell so that push notifications, GPS, camera and biometrics can be
 * added later as Capacitor plugins (each its own ticket) while the web app keeps
 * shipping via Firebase Hosting on its existing instant-deploy cadence.
 *
 * OSK-158 adds the first native plugin on top of that scaffold: a branded
 * native splash screen (@capacitor/splash-screen) shown while the WebView boots
 * and the web app loads — see the `plugins.SplashScreen` block below. The
 * remaining plugin configuration (push/GPS/camera/biometric), a status-bar
 * theme, deep links, and release signing are all still deliberately deferred to
 * later MOBILE-epic tickets.
 */
import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  // Reverse-DNS application id. Becomes the Android `applicationId` (the Play
  // Store package name) and must stay stable for the life of the app, so it is
  // fixed here up front. Uses the project's own `dev.openskeleton.*` namespace.
  appId: 'dev.openskeleton.app',

  // Human-readable name shown under the launcher icon. Matches the web app's
  // <title> ("OpenSkeleton").
  appName: 'OpenSkeleton',

  // The web build to wrap. Points straight at the committed `web/` directory —
  // the same static assets Firebase Hosting serves — so the native shell loads
  // the exact web app that ships to the browser. `web/` has no build step (it is
  // hand-authored static HTML/JS), so there is no intermediate dist/ to point at.
  //
  // Note: `cap sync`/`cap copy` COPY this directory into
  // android/app/src/main/assets/public at build time. That copy is generated
  // output and is git-ignored (see .gitignore) — CI regenerates it via
  // `npx cap sync android` before every build, so the native project never
  // carries a stale duplicate of web/.
  webDir: 'web',

  // Bundled-assets mode (the default: no `server.url`). The shell serves the
  // copied `web/` assets locally from the WebView, which keeps the app working
  // offline and pins each APK to a known web build. The alternative — pointing
  // `server.url` at the live Firebase Hosting origin for always-fresh content —
  // is a deliberate future decision, not part of this scaffold.
  //
  // `androidScheme: 'https'` serves the local assets over an https:// origin
  // inside the WebView (Capacitor's recommended default) so that browser
  // security features which require a secure context — and cookies/storage used
  // by auth.js — behave the same as they do on the live https site.
  server: {
    androidScheme: 'https',
  },

  // OSK-158 — native splash screen (@capacitor/splash-screen).
  //
  // WHY: on a cold start the OS launches the native shell and spins up the
  // WebView *before* any of web/ can paint. Without a splash the user sees a
  // blank/white flash during that gap. This shows a branded OpenSkeleton splash
  // (solid dark canvas + centred "OS" monogram — the same brand mark web/
  // index.html renders) from the very first frame until the web app is ready.
  //
  // The image + colours are defined natively (android/app/src/main/res: the
  // per-density drawable/splash.png set, @color/splash_background and the launch
  // theme in styles.xml). This block is the cross-platform runtime behaviour.
  plugins: {
    SplashScreen: {
      // Safety-net hard cap. In the normal path the splash is dismissed earlier,
      // the instant the web app signals it is ready, by web/splash.js calling
      // SplashScreen.hide() (a "hide-on-ready" hook). launchAutoHide below is the
      // fallback so the splash can NEVER stick if that JS somehow does not run —
      // it auto-hides after this many ms regardless.
      launchShowDuration: 3000,
      launchAutoHide: true,

      // Match the OpenSkeleton dark palette (web/ index.html --bg) so the native
      // splash, the Android 12+ system splash window and the web app's own
      // background are all the same colour — no flash between the phases.
      backgroundColor: '#0d1117',

      // Preserve the monogram's aspect ratio across the many device sizes/aspect
      // ratios: scale to fill and crop the solid margins rather than stretching
      // (which would distort the centred logo). Any area the image does not cover
      // is painted with backgroundColor above, so the crop is seamless.
      androidScaleType: 'CENTER_CROP',

      // No platform spinner — the brand mark is the whole splash.
      showSpinner: false,

      // Cover the full screen (including the status-bar area) for an edge-to-edge
      // branded splash, but stay out of sticky-immersive mode.
      splashFullScreen: true,
      splashImmersive: false,
    },
  },
};

export default config;
