-- driver_db: transactional outbox of driver-service for DriverAssignmentConfirmed / DriverAssignmentRejected
-- (topic driver-events, docs/events.md §4.10-4.11). Same columns as outbox_events in trip_db.
-- driver-service creates this table itself with spring.jpa.hibernate.ddl-auto=update (the default, also in
-- docker-compose.yml). Run this script only where JPA_DDL_AUTO is validate or none. Safe to run more than once:
--   docker compose exec -T driver-db psql -U postgres -d driver_db < scripts/db/2026-11-20-m1-driver-outbox.sql
CREATE TABLE IF NOT EXISTS outbox_events (
    id             UUID         PRIMARY KEY,
    aggregate_id   VARCHAR(255) NOT NULL,
    aggregate_type VARCHAR(255) NOT NULL,
    event_type     VARCHAR(255) NOT NULL,
    payload        TEXT         NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_driver_outbox_created_at ON outbox_events (created_at);
