# Centralized container logs in Grafana

The user requested centralized system logs in the existing Grafana. Implement local Docker log collection with Alloy and Loki, without changing business code or application log formats.

Docker stdout/stderr -> Alloy (same Compose project only) -> single-node Loki with filesystem/TSDB storage -> provisioned Loki datasource and DS03 Logs dashboard in existing Grafana. Compared with a Docker logging driver, Alloy avoids a host plugin and keeps collection outside application delivery. An Elasticsearch stack is unnecessary for the requested local demo.

Builder defaults: pin Loki 3.7.10 and Alloy v1.22.0 (official current releases verified 2026-11-23), seven-day Loki retention, durable Loki/Alloy volumes, no published Loki/Alloy ports. Collect the eight application services plus Kafka and five databases. Exclude monitoring services to avoid collector feedback. Labels are Compose project, service name, container and low-cardinality severity; correlation/trip IDs remain searchable log content. Strip ANSI colors and combine Java stack traces.

Grafana dashboard filters project/service/level/content, shows log volume, errors and chronological logs. Existing Prometheus datasource remains the default. No database migration, application recreation or cloud deployment is needed; restart Grafana for provisioning and start the two new services.

Validation: Docker Compose config, Loki verify-config, Alloy validate, actual Docker collection, Grafana datasource proxy queries, cross-service correlation search, severity/multiline samples, project isolation and collector restart persistence. This is local single-node logging; Docker socket access is required by the collector and is not a security sandbox even with a read-only mount.
