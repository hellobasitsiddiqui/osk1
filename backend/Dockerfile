# ---------------------------------------------------------------------------
# Multi-stage Dockerfile for the OpenSkeleton Spring Boot backend (Java 21).
#
# WHY multi-stage: the JDK + Maven toolchain needed to *build* the fat jar is
# heavy and is a security/attack-surface liability at runtime. Stage 1 ("build")
# does all the compiling; stage 2 ("runtime") is a slim JRE that carries ONLY
# the finished jar — no Maven, no JDK, no source. Smaller image, faster pulls,
# less to patch.
#
# WHY the committed Maven wrapper (./mvnw): we build with the exact Maven version
# the repo pins (.mvn/wrapper/maven-wrapper.properties) rather than whatever a
# base image happens to ship — reproducible builds, same tool locally and in CI.
#
# Tests are intentionally skipped here (-DskipTests): CI runs the test suite as a
# separate gate; re-running it inside the image build would only slow deploys.
# ---------------------------------------------------------------------------

# ===========================================================================
# Stage 1 — build: compile and package the application into a runnable jar.
# eclipse-temurin 21 JDK on Ubuntu "jammy" gives us a full JDK 21 toolchain.
# ===========================================================================
FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /workspace

# --- Dependency-resolve layer (fast rebuilds) --------------------------------
# Copy ONLY the wrapper + pom first. These change far less often than source, so
# Docker can cache the (slow) dependency download below and reuse it on every
# rebuild where only src/ changed. Copying src/ too early would bust that cache
# on every code change and re-download the internet each time.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# Warm the local Maven repo with all project + plugin dependencies. If this layer
# is cached, the package step below runs fully offline and in seconds.
# (go-offline resolves compile/runtime deps and build plugins ahead of time.)
RUN ./mvnw -B -ntp -q dependency:go-offline

# --- Build layer -------------------------------------------------------------
# Now bring in the actual source and build the fat jar. Only this layer (and
# down) rebuilds when application code changes.
COPY src/ src/
RUN ./mvnw -B -ntp -q -DskipTests package

# Normalise the artifact name so the runtime stage doesn't depend on the version
# string in the filename (e.g. backend-0.0.1-SNAPSHOT.jar -> app.jar).
RUN cp target/*.jar /app.jar

# ===========================================================================
# Stage 2 — runtime: minimal JRE that runs the jar and nothing else.
# 21-jre-jammy is a slim JRE (no compiler/build tooling) — smaller + less to CVE.
# ===========================================================================
FROM eclipse-temurin:21-jre-jammy AS runtime

# Run as a NON-ROOT user. The temurin base has no unprivileged user by default,
# so we create one. A container that never runs as root limits blast radius if
# the app is ever compromised (defence in depth; also required by many platforms).
RUN groupadd --system app && useradd --system --gid app --home-dir /app app

WORKDIR /app

# Copy in ONLY the finished jar from the build stage (no source, no Maven, no JDK).
COPY --from=build --chown=app:app /app.jar /app/app.jar

# Sane default JVM flags. MaxRAMPercentage makes the JVM size its heap from the
# container's memory limit (cgroup-aware) instead of the host's RAM — the correct
# behaviour under Cloud Run / Kubernetes. Callers can override JAVA_OPTS entirely.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseContainerSupport"

# The app reads $PORT (Cloud Run injects it; defaults to 8080 in the app config).
# We default it here too so `docker run` without -e PORT still binds 8080.
ENV PORT=8080

# OSK-100: stamp the git commit into the image at BUILD time. image.yml passes
# `--build-arg GIT_SHA=${github.sha}`; we surface it as the GIT_SHA env var so the
# app's /version endpoint reports exactly which commit is running. Defaults to
# "unknown" for a plain `docker build` with no build-arg — never a hardcoded SHA.
ARG GIT_SHA=unknown
ENV GIT_SHA=${GIT_SHA}

# Document the default listen port. EXPOSE is metadata only; the app honours $PORT
# at runtime regardless, so a platform handing us a different port still works.
EXPOSE 8080

USER app

# exec form via sh -c so $JAVA_OPTS expands; `exec` replaces the shell so the JVM
# becomes PID 1 and receives SIGTERM directly for graceful shutdown.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
