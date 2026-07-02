# Dockerfile — packages the OpenSkeleton web front-end into an nginx image.
#
# Why single-stage: the current "build" is a no-op — the static assets
# (index.html) are committed as-is, so there is nothing to compile or bundle.
# A multi-stage build would only add complexity for zero benefit. When a real
# build tool (Vite/webpack/etc.) is introduced, add a first `builder` stage
# that produces a dist/ dir and COPY --from=builder that dir instead of the raw
# source. See web/README.md for the documented build step.

FROM nginx:alpine

# Replace nginx's stock server block with ours (SPA fallback + cache headers).
# We overwrite default.conf rather than add a new file so the default server
# block does not also bind port 80 and shadow our config.
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Copy the static site into nginx's web root. This is the "build output"; for
# now it is a straight copy of the committed HTML + runtime config. Explicitly
# listing files keeps the Dockerfile / nginx.conf / README out of the image.
#
# NOTE: the container serves the committed (loopback) config.js as-is, which is
# correct for local/dev. Production config injection happens only in the Firebase
# Hosting deploy path (.github/workflows/hosting-deploy.yml), not in this image.
COPY index.html /usr/share/nginx/html/index.html
COPY config.js /usr/share/nginx/html/config.js

# Document the port; nginx:alpine already runs nginx in the foreground.
EXPOSE 80

# Basic container-level health check hitting the /healthz endpoint from nginx.conf.
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -qO- http://localhost/healthz || exit 1
