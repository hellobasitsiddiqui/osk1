// config.js — runtime configuration for the OpenSkeleton web front-end.
//
// WHAT: exposes a single browser global, `window.__APP_CONFIG__`, that the web
//       app reads at runtime to find the backend API. Keeping this in a
//       *separate* file (rather than baking it into index.html or a JS bundle)
//       is deliberate: it lets CD inject the real backend URL at DEPLOY time
//       without rebuilding the app. The Firebase Hosting deploy job
//       (.github/workflows/hosting-deploy.yml) resolves the live Cloud Run URL
//       via `gcloud run services describe` and rewrites `apiBaseUrl` below just
//       before it ships to the live channel.
//
// WHY the committed value is loopback: this is the LOCAL DEV default so that
//     `docker run` / local nginx (and a future local dev server) work against a
//     backend running on the same host with zero configuration. This value MUST
//     NEVER reach the live Firebase channel — a 127.0.0.1 `apiBaseUrl` in
//     production makes the browser treat every API call as a Private Network
//     Access request (scary permission prompt) and leaves `/api/v1/me` dead.
//
// The deploy pipeline therefore does two things (see ticket OSK-13 / TM-128):
//   1. Overwrites `apiBaseUrl` with the real backend URL (or, until OSK-47
//      deploys the backend, neutralises it to same-origin so nothing loopback
//      ships), and
//   2. FAILS the deploy — fail-closed — if a localhost/127.0.0.1 value is still
//      present in what would ship. A dev default reaching live is treated as a
//      pipeline failure, not a user-facing bug.
window.__APP_CONFIG__ = {
  // Base URL of the backend API, no trailing slash. Overwritten at deploy time.
  // LOCAL DEV DEFAULT ONLY — never shipped to production (see header comment).
  apiBaseUrl: "http://127.0.0.1:8080",

  // OSK-100: short git SHA of the web build, rendered unobtrusively by index.html.
  // Committed placeholder is "dev"; the Firebase Hosting deploy
  // (.github/workflows/hosting-deploy.yml) rewrites this with the short
  // ${GITHUB_SHA} at deploy time via the SAME node-rewrite seam as apiBaseUrl —
  // never hardcoded, so a fresh replay project shows the correct value.
  buildSha: "dev",

  // OSK-78: the CURRENT terms-of-service / privacy-policy version the app requires a
  // signed-in user to have accepted. web/terms.js compares this to the user's
  // `termsAcceptedVersion` (from GET /api/v1/me, an OSK-69 lifecycle field) and, when
  // they DIFFER (including null = never accepted), shows a blocking acceptance gate on
  // the protected /app page. Accepting PATCHes /api/v1/me/lifecycle
  // ({ termsAcceptedVersion: <this> }); the server stamps `termsAcceptedAt`.
  //
  // OPERATOR CONTRACT: bump this string whenever the Terms/Privacy text materially
  // changes — every user is then re-gated on their next visit until they re-accept. Any
  // opaque, monotonic label works; an ISO date is the convention. Leave it "" to make the
  // gate INERT (nothing to accept, never blocks), which keeps terms.js safe to ship even
  // before real terms/privacy copy exists.
  termsVersion: "2026-07-01",

  // OSK-151: site-wide alert banner (web MVP, config-driven). Every web page reads
  // this at runtime and renders a dismissible, full-width banner across the top
  // WHEN `active` is true AND — if a time window is set — the current time is
  // within [startsAt, endsAt]. Default INACTIVE, so nothing shows anywhere until an
  // operator fills this in. Editing `message` re-shows the banner even for users who
  // dismissed a previous one (dismissal is keyed by a hash of the message text).
  //
  //   active   — master on/off switch. false = no banner on any page.
  //   severity — "info" | "warning" | "critical"; drives the banner's accent colour
  //              (info = brand accent, warning = amber, critical = red). Anything
  //              else falls back to "info".
  //   message  — the text shown to users. Rendered with textContent only, so it is
  //              XSS-safe (any HTML in here is shown as literal text, never parsed).
  //   startsAt — ISO 8601 instant the banner should start showing, or null for
  //              "no start bound" (show immediately once active).
  //   endsAt   — ISO 8601 instant the banner should stop showing, or null for
  //              "no end bound" (show until dismissed / active flipped off).
  //
  // FOLLOW-UP (see the PR body): the alert is a committed config value today. A later
  // ticket sources it from the backend/admin — e.g. GET /api/v1/alerts or an
  // admin-set value — so ops can publish/clear an advisory without a redeploy.
  alert: {
    active: false,
    severity: "info",
    message: "",
    startsAt: null,
    endsAt: null,
  },

  // OSK-105: onboarding product tour (web/tour.js). OPTIONAL — the tour ships with
  // safe defaults and works with this block absent, so it exists only to give an
  // operator a runtime kill-switch without a code change. Every web page reads it via
  // window.__APP_CONFIG__.tour.
  //
  //   enabled           — master on/off for the whole tour AND its "?" launcher on
  //                       every page. false = no tour, no launcher anywhere.
  //   autoStartFirstRun — whether a brand-new visitor is shown the tour automatically
  //                       ONCE (guarded by a localStorage flag so it never nags again
  //                       after they dismiss it). Set false to make the tour purely
  //                       launch-on-demand from the "?" affordance.
  tour: {
    enabled: true,
    autoStartFirstRun: true,
  },

  // OSK-136: first-login profile-completion gate (web/completion-gate.js). OPTIONAL — the
  // gate ships with safe defaults and works with this block absent, so it exists only to
  // give an operator runtime control without a code change. Read via
  // window.__APP_CONFIG__.completionGate on the protected /app page.
  //
  //   enabled        — master on/off for the whole gate. false = never blocks anyone.
  //   autoStart      — whether the gate auto-mounts on first login when the profile is
  //                    incomplete. Set false to opt out of auto-mount and drive the gate
  //                    programmatically via window.OSKCompletionGate (e.g. to let an
  //                    orchestrator sequence it after the OSK-78 terms gate) — the module
  //                    stays loaded and idempotent either way.
  //   requiredFields — which profile fields must be present before entering the app.
  //                    Only known fields (firstName, city, age) are honoured; anything
  //                    else is ignored. Defaults to name + location + age when omitted.
  completionGate: {
    enabled: true,
    autoStart: true,
    requiredFields: ["firstName", "city", "age"],
  },

  // OSK-74: Firebase Web App config for the web auth guard (web/auth.js). auth.js
  // reads this at runtime to initialise the Firebase JS SDK and gate the protected
  // /app page (sign-in with email/password or Google, then call /api/v1/me).
  //
  // WAVE-(-1) pattern (see docs/agents/BRINGUP-RUNBOOK.md): `apiKey` and `appId` are the
  // per-Web-App identifiers minted when a human registers a Firebase WEB app (ticket
  // OSK-92; needs a firebase-scoped login/reauth, ticket OSK-38). For a FRESH replay they
  // start EMPTY and auth.js treats an empty apiKey as "auth not configured" — it never
  // crashes, it just renders a clear notice and keeps the protected content hidden until
  // the values are filled in. Here they are WIRED (below) from the registered
  // "OpenSkeleton Web" app — do this ONCE at bring-up, not per-ticket.
  //
  // Note: the Firebase web apiKey is NOT a classic secret — it is a public client
  // identifier safe to ship in static assets (access is enforced by Firebase Auth +
  // backend token verification, not by hiding this string). It is still left blank
  // here only because the value does not exist until the Web App is registered.
  //
  // The other three values are the KNOWN, non-secret project identifiers for the
  // `openskeleton-one` Firebase project, so they are prefilled:
  //   authDomain        — <projectId>.firebaseapp.com, the auth/OAuth redirect host.
  //   projectId         — the GCP/Firebase project id.
  //   messagingSenderId — the project number (public, used by the SDK).
  firebase: {
    // OSK-92: wired from the registered "OpenSkeleton Web" Firebase Web App
    // (firebase apps:sdkconfig). apiKey + appId are PUBLIC client identifiers, safe
    // to commit; access is enforced by Firebase Auth + backend token verification.
    apiKey: "AIzaSyA8_Ac25T4EHsdhkXRZ6_bQlZcBprF1HKc",
    authDomain: "openskeleton-one.firebaseapp.com",
    projectId: "openskeleton-one",
    storageBucket: "openskeleton-one.firebasestorage.app",
    messagingSenderId: "476227744481",
    appId: "1:476227744481:web:41667628f03355f898fda0",
  },
};
