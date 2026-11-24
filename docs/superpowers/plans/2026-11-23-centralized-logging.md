# Centralized logging implementation

Execute the requested bounded infrastructure change inline. Use the isolated native worktree, then transfer validated changes after baseline checks. No commit/push required.

1. Add `deploy/monitoring/loki/loki.yml` and `alloy/config.alloy`; add Loki/Alloy and durable volumes to Compose. Scope discovery by `${COMPOSE_PROJECT_NAME}` and retain only known project services. Validate using the pinned container binaries.
2. Provision datasource UID `loki` and dashboard UID `ds03-logs`, with project/service/level/text filters and log volume/error/log panels. Keep Prometheus default. Validate JSON and queries through the actual Grafana API.
3. Transfer files to the source checkout, start only Loki/Alloy and restart Grafana. Generate a real correlated request without changing existing user records; verify resulting logs in Grafana, multiline handling, project filtering and collector restart. Preserve evidence without credentials in `docs/acceptance` and document usage/retention/socket scope in `docs/operations.md`.
