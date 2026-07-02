# android

Android application shell: a thin native host that wraps the `web` build in a
WebView (a Capacitor-style hybrid), exposing native capabilities — push
notifications, GPS, camera, biometrics — to the web app through the `webview`
bridge.

Keeping mobile as a hybrid over the web codebase (rather than a native rewrite)
preserves the instant web-deploy cadence and avoids a permanent dual-codebase
tax.

**Status:** stub. The Android project is scaffolded in a later mobile epic; this
is a minimal placeholder for now.
