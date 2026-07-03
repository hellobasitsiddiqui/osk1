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
};
