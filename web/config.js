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
};
