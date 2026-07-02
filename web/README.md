# web

Browser front-end: the single-page web application users interact with.

**Stack:** a static build (bundled HTML/CSS/JS) served by nginx in a container
and deployed to Firebase Hosting. Talks to the `backend` API over `/api/v1`.

**Status:** stub. Build tooling, the nginx Dockerfile and the app code land in
later tickets. Kept minimal for now.
