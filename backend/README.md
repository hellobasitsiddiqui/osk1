# backend

Server-side application: the HTTP API and domain logic.

**Stack:** Java + Spring Boot (Maven). Exposes a versioned REST API under
`/api/v1`, actuator probes (`/health`, `/info`, `/metrics`), and talks to
Postgres through Flyway-managed migrations. Containerised for Cloud Run.

**Status:** stub. The walking skeleton, Dockerfile, error model, auth seam and
observability all arrive in their own foundation tickets — this directory starts
minimal on purpose so those tickets can fill it without colliding here.

## OpenAPI spec (drift-checked)

The canonical API spec is committed at `backend/openapi.json` and guarded by
`ApiSpecDriftTest` (runs in `./mvnw verify` / CI). **If you change the REST API,
regenerate and commit the spec or CI fails:**

```
./mvnw test -Dtest=ApiSpecDriftTest -Dopenapi.generate=true
git add openapi.json
```
