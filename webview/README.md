# webview

Shared WebView bridge and hybrid glue between the `web` app and the native
mobile shells (`android`, plus an iOS shell in a later epic).

This is where the cross-engine bridge contract lives — the "I am running inside
a WebView" signal and the native-capability shims — so the **same** web build
runs unmodified inside Android's `WebView` and iOS's `WKWebView`, which surface
native objects differently (a JS interface object vs. `webkit.messageHandlers`).

**Status:** stub. The bridge is defined once the mobile shells exist; this
placeholder reserves the directory contract downstream paths depend on.
