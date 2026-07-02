# Dev command runner (OSK-22) — one entry point for the whole polyglot mono-repo,
# so nobody has to learn five toolchains. `make` (or `make help`) lists everything.
# These are THIN wrappers over the per-surface tooling (the Maven wrapper, docker
# compose, Spotless) — no build logic is duplicated here.

BACKEND := backend
COMPOSE := docker compose

.DEFAULT_GOAL := help
.PHONY: help setup up down build test run lint fmt

help: ## List available commands
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-8s\033[0m %s\n", $$1, $$2}'

setup: ## Generate a local .env with dev defaults (idempotent; never clobbers)
	@./scripts/dev-setup.sh

up: setup ## Start the full local stack (backend + web + Postgres)
	$(COMPOSE) up -d
	@echo "web: http://localhost:8081   backend: http://localhost:8080/health"

down: ## Stop the local stack (keeps the pgdata volume)
	$(COMPOSE) down

build: ## Build the backend jar + all container images
	cd $(BACKEND) && ./mvnw -q -B -ntp -DskipTests package
	$(COMPOSE) build

test: ## Run the backend test suite + coverage/format gates (mvn verify)
	cd $(BACKEND) && ./mvnw -B -ntp verify

run: ## Run the backend locally, no containers (Ctrl-C to stop)
	cd $(BACKEND) && ./mvnw spring-boot:run

lint: ## Check formatting (Spotless) — fails on violations
	cd $(BACKEND) && ./mvnw -q -B -ntp spotless:check

fmt: ## Auto-format the code (Spotless apply)
	cd $(BACKEND) && ./mvnw -q -B -ntp spotless:apply
