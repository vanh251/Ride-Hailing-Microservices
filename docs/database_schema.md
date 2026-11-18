# Ride-Hailing Microservices — Complete PostgreSQL Database Schema

> **Database Optimizer Notes:**
> - All indexes on UUID soft-references are included to prevent sequential scans on JOIN-equivalent queries at application layer.
> - Partial indexes are used where the query pattern only touches a subset of rows (e.g., `WHERE published = FALSE`, `WHERE status = 'AVAILABLE'`).
> - `version` columns enable **Optimistic Concurrency Control (OCC)** — no pessimistic row locks needed.
> - All `updated_at` columns should be maintained by a `BEFORE UPDATE` trigger (template provided at the end).

**Target:** PostgreSQL 15+  
**Rule:** Zero physical `FOREIGN KEY` constraints across database boundaries. All cross-service references are `UUID` soft references.

---

## DATABASE 1 — `db_identity`

> **Owner:** Thành viên 3 (Foundation & Algorithm)  
> **Purpose:** Authentication, authorization, JWT issuance, RBAC.

```sql
-- ============================================================
-- db_identity
-- ============================================================
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ── users ────────────────────────────────────────────────────
CREATE TABLE users (
    user_id         UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    email           VARCHAR(255) NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    full_name       VARCHAR(100) NOT NULL,
    phone_number    VARCHAR(20),
    role            VARCHAR(20)  NOT NULL CHECK (role IN ('PASSENGER', 'DRIVER', 'ADMIN')),
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED')),

    -- OCC
    version         INTEGER      NOT NULL DEFAULT 1,

    -- Auditing
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Unique email per role scope; fast lookup on login
CREATE UNIQUE INDEX uq_users_email       ON users(email);
CREATE INDEX        idx_users_role       ON users(role);
CREATE INDEX        idx_users_status     ON users(status);

-- ── refresh_tokens ───────────────────────────────────────────
-- Short-lived JWTs + long-lived refresh tokens (rotation strategy)
CREATE TABLE refresh_tokens (
    token_id        UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id         UUID        NOT NULL,   -- soft ref → users.user_id
    token_hash      VARCHAR(255) NOT NULL,  -- store hash, never plaintext
    device_info     TEXT,
    ip_address      INET,
    expires_at      TIMESTAMPTZ  NOT NULL,
    revoked         BOOLEAN      NOT NULL DEFAULT FALSE,
    revoked_at      TIMESTAMPTZ,

    -- Auditing
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Fast lookup: validate an incoming refresh token
CREATE INDEX idx_refresh_tokens_user_id   ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_hash      ON refresh_tokens(token_hash);
-- Partial: only non-revoked, non-expired tokens need fast scans
CREATE INDEX idx_refresh_tokens_active    ON refresh_tokens(user_id, expires_at)
    WHERE revoked = FALSE;
```

---

## DATABASE 2 — `db_driver`

> **Owner:** Thành viên 2 (Concurrency & Geo)  
> **Purpose:** Driver profile, real-time location, availability state machine, Redis GEO mirror.

```sql
-- ============================================================
-- db_driver
-- ============================================================
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ── drivers ──────────────────────────────────────────────────
-- THIS TABLE IS THE SOURCE OF TRUTH FOR CONCURRENCY CONTROL.
-- The `version` column is used for Compare-And-Swap (CAS /
-- Optimistic Concurrency Control) to prevent double-booking.
--
-- How CAS works here:
--   Application reads: SELECT version, status FROM drivers WHERE driver_id = $1
--   Application writes:
--     UPDATE drivers
--       SET status = 'RESERVED',
--           reserved_for_trip = $trip_id,
--           reserved_until    = NOW() + INTERVAL '30 seconds',
--           version           = version + 1
--     WHERE driver_id = $1
--       AND status    = 'AVAILABLE'        -- guard condition
--       AND version   = $expected_version  -- CAS condition
--     RETURNING driver_id;
--
--   rowCount = 0 → CONFLICT → 409 DRIVER_ALREADY_RESERVED
--   rowCount = 1 → SUCCESS → proceed to Matching
--
-- driver_id IS the driver's identity user id (soft ref → db_identity.users.user_id).
-- (An older version had a generated driver_id plus a user_id column. ddl-auto: update cannot drop
--  that column, so a driver_db volume created before this change must be recreated:
--  docker compose down -v, see docs/test-data/driver/README.md.)
-- It is assigned at registration (POST /api/auth/register/driver), not generated, so one
-- identity user has at most one driver profile and the same id is used everywhere: JWT `sub`,
-- X-User-Id, /api/drivers/{id}, matching/trip `driverId` and every event's `driverId`.
CREATE TABLE drivers (
    driver_id           UUID        PRIMARY KEY,   -- = db_identity.users.user_id (no default)
    full_name           VARCHAR(100) NOT NULL,     -- copy of db_identity.users.full_name at registration
    license_number      VARCHAR(50)  NOT NULL,

    -- State machine (enum DriverStatus — CODING_CONVENTION §12.2):
    --   OFFLINE ⇄ AVAILABLE                 (driver: PATCH /status)
    --   AVAILABLE → RESERVED                (POST /reserve, CAS)
    --   RESERVED  → AVAILABLE               (DELETE /reserve, lease expiry, TripCancelled)
    --   RESERVED  → BUSY                    (consume DriverAssigned)
    --   BUSY      → AVAILABLE               (consume TripCompleted / TripCancelled)
    -- Full transition table: docs/api.md → PATCH /api/drivers/{id}/status
    status              VARCHAR(20)  NOT NULL DEFAULT 'OFFLINE'
                            CHECK (status IN ('OFFLINE', 'AVAILABLE', 'RESERVED', 'BUSY')),

    -- Reservation fields (set atomically with status = RESERVED)
    -- reserved_for_trip is kept while RESERVED and BUSY and cleared on return to
    -- AVAILABLE. Consumers only change status when it equals the event's tripId,
    -- so a late/duplicate event for an old trip cannot release the driver.
    reserved_for_trip   UUID,                   -- soft ref → db_trip.trips.trip_id
    reserved_until      TIMESTAMPTZ,            -- lease expiry (RESERVED only); cleared on RESERVED → BUSY

    -- Real-time location (primary store; Redis GEO is a cache layer)
    current_lat         DECIMAL(9, 6),
    current_lng         DECIMAL(9, 6),
    last_heartbeat_at   TIMESTAMPTZ,            -- used to detect stale/offline drivers

    -- OCC — incremented on every state change (status, reservation fields).
    -- Position reports (PATCH /location) update only current_lat/current_lng/last_heartbeat_at
    -- and do NOT bump it, so frequent heartbeats never make a reservation CAS fail.
    version             INTEGER      NOT NULL DEFAULT 1,

    -- Auditing
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Matching Service queries AVAILABLE drivers frequently
CREATE INDEX idx_drivers_status        ON drivers(status);
-- Partial: reservation cleanup job (find expired leases)
CREATE INDEX idx_drivers_reserved      ON drivers(reserved_until)
    WHERE status = 'RESERVED';
-- Partial: heartbeat monitor (find stale drivers)
CREATE INDEX idx_drivers_heartbeat     ON drivers(last_heartbeat_at)
    WHERE status IN ('AVAILABLE', 'RESERVED', 'BUSY');

-- Enforce: a driver can only be reserved for ONE trip at a time
CREATE UNIQUE INDEX uq_driver_active_reservation
    ON drivers(reserved_for_trip)
    WHERE reserved_for_trip IS NOT NULL;

-- ── vehicles ─────────────────────────────────────────────────
CREATE TABLE vehicles (
    vehicle_id          UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    driver_id           UUID        NOT NULL,   -- FK within same DB
    license_plate       VARCHAR(20)  NOT NULL,
    make                VARCHAR(50)  NOT NULL,
    model               VARCHAR(50)  NOT NULL,
    color               VARCHAR(30)  NOT NULL,
    year                SMALLINT     NOT NULL,
    vehicle_type        VARCHAR(20)  NOT NULL DEFAULT 'SEDAN'
                            CHECK (vehicle_type IN ('SEDAN', 'SUV', 'MOTORBIKE', 'VAN')),
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,

    -- Auditing
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_vehicles_driver FOREIGN KEY (driver_id)
        REFERENCES drivers(driver_id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_vehicles_license_plate ON vehicles(license_plate);
CREATE        INDEX idx_vehicles_driver_id    ON vehicles(driver_id);

-- ── driver_locations (time-series location history) ──────────
-- Append-only log. Redis GEO handles real-time; this is for
-- audit trail, fare calculation, and replay.
CREATE TABLE driver_locations (
    location_id         UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    driver_id           UUID        NOT NULL,   -- FK within same DB
    lat                 DECIMAL(9, 6) NOT NULL,
    lng                 DECIMAL(9, 6) NOT NULL,
    speed_kmh           DECIMAL(5, 2),
    heading_degrees     SMALLINT,
    recorded_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_locations_driver FOREIGN KEY (driver_id)
        REFERENCES drivers(driver_id) ON DELETE CASCADE
);

-- Time-range queries for a specific driver (trip replay)
CREATE INDEX idx_driver_locations_driver_time
    ON driver_locations(driver_id, recorded_at DESC);
-- Recent locations across all drivers (monitoring dashboard)
CREATE INDEX idx_driver_locations_recent
    ON driver_locations(recorded_at DESC);

-- ── processed_events ──────────────────────────────────────────
-- Consumer-side deduplication table (CODING_CONVENTION §21.1).
-- Driver Service consumes from trip-events: DriverAssigned,
-- TripCompleted, TripCancelled (see docs/events.md §5).
-- Insert event_id in the SAME transaction as the drivers status
-- update. If event_id already exists → skip (duplicate delivery).
CREATE TABLE processed_events (
    event_id            UUID         PRIMARY KEY,   -- the incoming Kafka message eventId
    event_type          VARCHAR(100) NOT NULL,      -- e.g., 'TripCompleted'
    processed_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
```

---

## DATABASE 3 — `db_trip`

> **Owner:** Thành viên 1 (Core & Data Consistency)  
> **Purpose:** Trip lifecycle state machine, Saga orchestration, Transactional Outbox, deduplication.

```sql
-- ============================================================
-- db_trip  (Saga Orchestrator Database)
-- ============================================================
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ── trips ────────────────────────────────────────────────────
CREATE TABLE trips (
    trip_id             UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),

    -- Soft cross-service references (UUIDs only, no FK constraints)
    passenger_id        UUID        NOT NULL,   -- soft ref → db_identity.users.user_id
    driver_id           UUID,                   -- soft ref → db_driver.drivers.driver_id (null until matched)

    -- Trip state machine
    -- REQUESTED → PRICED → SEARCHING → ASSIGNED → IN_PROGRESS
    --          → COMPLETED → PAID
    --          → PAYMENT_FAILED → CANCELLED (compensation path)
    status              VARCHAR(30)  NOT NULL DEFAULT 'REQUESTED'
                            CHECK (status IN (
                                'REQUESTED', 'PRICED', 'SEARCHING',
                                'ASSIGNED', 'IN_PROGRESS', 'COMPLETED',
                                'PAID', 'PAYMENT_FAILED', 'CANCELLED', 'FAILED'
                            )),

    -- Geo data
    pickup_address      TEXT,
    pickup_lat          DECIMAL(9, 6) NOT NULL,
    pickup_lng          DECIMAL(9, 6) NOT NULL,
    dropoff_address     TEXT,
    dropoff_lat         DECIMAL(9, 6) NOT NULL,
    dropoff_lng         DECIMAL(9, 6) NOT NULL,
    distance_km         DECIMAL(8, 3),

    -- Pricing (set after step ②)
    estimated_fare      DECIMAL(10, 2),
    currency            VARCHAR(3)   NOT NULL DEFAULT 'VND',
    fare_rule_id        UUID,                   -- soft ref → db_pricing.fare_rules.rule_id

    -- Distributed systems primitives
    correlation_id      UUID        NOT NULL,   -- traces this trip across all services in Jaeger/Loki
    idempotency_key     VARCHAR(255) NOT NULL,  -- client-provided; prevents duplicate trip creation

    -- OCC
    version             INTEGER      NOT NULL DEFAULT 1,

    -- Auditing
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    started_at          TIMESTAMPTZ,            -- when driver picked up passenger
    completed_at        TIMESTAMPTZ             -- when trip ended
);

CREATE UNIQUE INDEX uq_trips_idempotency_key   ON trips(idempotency_key);
CREATE        INDEX idx_trips_passenger_id     ON trips(passenger_id);
CREATE        INDEX idx_trips_driver_id        ON trips(driver_id);
CREATE        INDEX idx_trips_status           ON trips(status);
CREATE        INDEX idx_trips_correlation_id   ON trips(correlation_id);
-- Active trips only (exclude terminal states to keep index small)
CREATE        INDEX idx_trips_active           ON trips(status, created_at DESC)
    WHERE status NOT IN ('PAID', 'CANCELLED', 'FAILED');

-- ── saga_log ──────────────────────────────────────────────────
-- Tracks each step of the distributed Saga transaction.
-- The Orchestrator (Trip Service) writes here on every state transition.
CREATE TABLE saga_log (
    log_id              UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    trip_id             UUID        NOT NULL,   -- soft ref → trips.trip_id
    correlation_id      UUID        NOT NULL,

    -- STARTED → PRICING_DONE → SEARCHING → DRIVER_RESERVED
    --         → TRIP_COMPLETED → PAYMENT_PROCESSING
    --         → SAGA_COMPLETED (terminal happy)
    --         → COMPENSATING   → SAGA_COMPENSATED (terminal fail)
    saga_state          VARCHAR(30)  NOT NULL,

    -- Which compensating step is active (null if not compensating)
    compensation_step   VARCHAR(50),
    -- Snapshot of relevant data at this step for compensation logic
    context_snapshot    JSONB        NOT NULL DEFAULT '{}',
    -- Any error detail if this step failed
    error_detail        TEXT,

    -- Auditing
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Fast retrieval of saga history for a trip (Jaeger-style trace)
CREATE INDEX idx_saga_log_trip_id       ON saga_log(trip_id, created_at ASC);
CREATE INDEX idx_saga_log_correlation   ON saga_log(correlation_id);

-- ── outbox_events ─────────────────────────────────────────────
-- Transactional Outbox Pattern:
-- Event is inserted in the SAME database transaction as the business write.
-- A relay worker polls this table (using the partial index below) and
-- publishes to Apache Kafka, guaranteeing at-least-once delivery.
CREATE TABLE outbox_events (
    event_id            UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    aggregate_type      VARCHAR(50)  NOT NULL,  -- e.g., 'Trip'
    aggregate_id        UUID        NOT NULL,   -- e.g., trip_id
    event_type          VARCHAR(80)  NOT NULL,  -- e.g., 'trip.requested'
    payload             JSONB       NOT NULL,
    correlation_id      UUID        NOT NULL,

    -- Relay state
    published           BOOLEAN      NOT NULL DEFAULT FALSE,
    published_at        TIMESTAMPTZ,
    retry_count         SMALLINT     NOT NULL DEFAULT 0,
    last_error          TEXT,

    -- Auditing
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- THE CRITICAL INDEX: relay worker does `SELECT ... WHERE published = FALSE ORDER BY created_at`
-- Partial index skips already-published rows entirely → ultra-fast scan
CREATE INDEX idx_outbox_unpublished
    ON outbox_events(created_at ASC)
    WHERE published = FALSE;

-- ── processed_events ──────────────────────────────────────────
-- Consumer-side deduplication table.
-- Trip Service also consumes events (e.g., driver.reserved, payment.failed).
-- Before processing, insert event_id here. ON CONFLICT DO NOTHING → skip duplicate.
CREATE TABLE processed_events (
    event_id            UUID        NOT NULL,   -- the incoming Kafka message event_id
    consumer_name       VARCHAR(80)  NOT NULL,  -- e.g., 'trip-service.driver-reserved-consumer'
    processed_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    result_summary      TEXT,

    PRIMARY KEY (event_id, consumer_name)
);

-- ── idempotency_keys ──────────────────────────────────────────
-- Stores client-submitted idempotency keys with cached responses.
-- If the same key arrives again within TTL, return the cached response.
CREATE TABLE idempotency_keys (
    idem_key            VARCHAR(255) NOT NULL,
    resource_type       VARCHAR(50)  NOT NULL,  -- e.g., 'POST /trips'
    request_hash        VARCHAR(64)  NOT NULL,  -- SHA-256 of request body
    response_status     SMALLINT,               -- HTTP status code cached
    response_body       JSONB,                  -- cached response body
    locked              BOOLEAN      NOT NULL DEFAULT FALSE,  -- TRUE while first request is in-flight
    expires_at          TIMESTAMPTZ  NOT NULL,

    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    PRIMARY KEY (idem_key, resource_type)
);

CREATE INDEX idx_idempotency_expires ON idempotency_keys(expires_at);
```

---

## DATABASE 4 — `db_matching`

> **Owner:** Thành viên 2 (Concurrency & Geo)  
> **Purpose:** Records matching attempts, driver offers, and concurrency conflict audit.

```sql
-- ============================================================
-- db_matching
-- ============================================================
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ── match_requests ────────────────────────────────────────────
-- Created when Matching Service consumes a `trip.requested` event.
-- W3 M2 assigns request_id = trip_id; the SQL default remains for legacy/manual inserts.
CREATE TABLE match_requests (
    request_id          UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    trip_id             UUID        NOT NULL,   -- soft ref → db_trip.trips.trip_id
    passenger_id        UUID        NOT NULL,   -- soft ref → db_identity.users.user_id
    correlation_id      UUID        NOT NULL,

    pickup_lat          DECIMAL(9, 6) NOT NULL,
    pickup_lng          DECIMAL(9, 6) NOT NULL,
    search_radius_km    DECIMAL(5, 2) NOT NULL DEFAULT 5.0,

    -- PENDING → MATCHED → NO_DRIVER_FOUND → EXPIRED
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                            CHECK (status IN ('PENDING', 'MATCHING', 'MATCHED', 'NO_DRIVER_FOUND', 'EXPIRED')),
    matched_driver_id   UUID,                   -- soft ref → db_driver.drivers.driver_id
    active_reservation_event_id UUID,             -- fences recovery of a committed match
    active_reservation_version INTEGER,           -- Driver version returned by reserve CAS
    active_reserved_until TIMESTAMPTZ,
    attempt_count       SMALLINT     NOT NULL DEFAULT 1,
    expires_at          TIMESTAMPTZ  NOT NULL,  -- offer window timeout

    -- Auditing
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_match_requests_trip_id ON match_requests(trip_id);
CREATE        INDEX idx_match_requests_status  ON match_requests(status);
CREATE        INDEX idx_match_requests_active  ON match_requests(expires_at)
    WHERE status = 'PENDING';

-- ── driver_offers ─────────────────────────────────────────────
-- Each CAS attempt for a specific driver is logged here.
-- Provides audit trail for concurrency conflicts (race condition C1).
CREATE TABLE driver_offers (
    offer_id            UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    request_id          UUID        NOT NULL,   -- FK within same DB
    driver_id           UUID        NOT NULL,   -- soft ref → db_driver.drivers.driver_id
    trip_id             UUID        NOT NULL,   -- soft ref → db_trip.trips.trip_id
    correlation_id      UUID        NOT NULL,

    -- PENDING → ACCEPTED → REJECTED → CONFLICT → EXPIRED
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                            CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'CONFLICT', 'EXPIRED')),

    -- The driver_version used in the CAS attempt (for audit)
    driver_version_used INTEGER      NOT NULL,
    -- Distance from driver to pickup at time of matching
    distance_km         DECIMAL(8, 3),
    -- Rank in the candidate list (1 = closest/highest score)
    rank                SMALLINT     NOT NULL DEFAULT 1,

    offered_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    responded_at        TIMESTAMPTZ,

    CONSTRAINT fk_offers_request FOREIGN KEY (request_id)
        REFERENCES match_requests(request_id) ON DELETE CASCADE
);

CREATE INDEX idx_driver_offers_request_id ON driver_offers(request_id);
CREATE INDEX idx_driver_offers_driver_id  ON driver_offers(driver_id);
CREATE INDEX idx_driver_offers_trip_id    ON driver_offers(trip_id);

-- ── processed_events ──────────────────────────────────────────
CREATE TABLE processed_events (
    event_id            UUID        NOT NULL,
    consumer_name       VARCHAR(80)  NOT NULL,
    processed_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    result_summary      TEXT,

    PRIMARY KEY (event_id, consumer_name)
);
```

---

## DATABASE 5 — `db_pricing`

> **Owner:** Thành viên 3 (Foundation & Algorithm)  
> **Purpose:** Dynamic fare calculation rules, surge pricing zones, quote history.

```sql
-- ============================================================
-- db_pricing
-- ============================================================
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ── fare_rules ────────────────────────────────────────────────
-- Configurable pricing rules per vehicle type and zone.
CREATE TABLE fare_rules (
    rule_id             UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    name                VARCHAR(100) NOT NULL,
    vehicle_type        VARCHAR(20)  NOT NULL DEFAULT 'SEDAN'
                            CHECK (vehicle_type IN ('SEDAN', 'SUV', 'MOTORBIKE', 'VAN')),

    -- Base pricing formula: total = base_fare + (distance_km * per_km_rate)
    base_fare           DECIMAL(10, 2) NOT NULL DEFAULT 10000,  -- VND
    per_km_rate         DECIMAL(10, 2) NOT NULL DEFAULT 8000,   -- VND/km
    minimum_fare        DECIMAL(10, 2) NOT NULL DEFAULT 15000,

    -- Time-based rules (stored as HH:MM in 24h format)
    peak_hours_start    TIME,
    peak_hours_end      TIME,
    peak_multiplier     DECIMAL(4, 2) NOT NULL DEFAULT 1.0,

    currency            VARCHAR(3)   NOT NULL DEFAULT 'VND',
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,

    -- Auditing
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_fare_rules_vehicle_type ON fare_rules(vehicle_type);
CREATE INDEX idx_fare_rules_active       ON fare_rules(is_active);

-- ── surge_zones ───────────────────────────────────────────────
-- Geographic surge pricing zones (polygon-based; PostGIS optional).
CREATE TABLE surge_zones (
    zone_id             UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    name                VARCHAR(100) NOT NULL,
    -- Bounding box for fast filtering (PostGIS geometry for precision)
    center_lat          DECIMAL(9, 6) NOT NULL,
    center_lng          DECIMAL(9, 6) NOT NULL,
    radius_km           DECIMAL(6, 3) NOT NULL,
    surge_multiplier    DECIMAL(4, 2) NOT NULL DEFAULT 1.0,

    is_active           BOOLEAN      NOT NULL DEFAULT FALSE,
    active_from         TIMESTAMPTZ,
    active_until        TIMESTAMPTZ,

    -- Auditing
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Only query active surge zones
CREATE INDEX idx_surge_zones_active ON surge_zones(is_active, active_until)
    WHERE is_active = TRUE;

-- ── quotes ────────────────────────────────────────────────────
-- Audit log of every fare quote returned to Trip Service.
-- Enables fare dispute resolution and analytics.
CREATE TABLE quotes (
    quote_id            UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    trip_id             UUID        NOT NULL,   -- soft ref → db_trip.trips.trip_id
    correlation_id      UUID        NOT NULL,
    fare_rule_id        UUID        NOT NULL,   -- FK within same DB
    surge_zone_id       UUID,                   -- FK within same DB (nullable)

    distance_km         DECIMAL(8, 3) NOT NULL,
    base_fare           DECIMAL(10, 2) NOT NULL,
    surge_multiplier    DECIMAL(4, 2) NOT NULL DEFAULT 1.0,
    final_fare          DECIMAL(10, 2) NOT NULL,
    currency            VARCHAR(3)   NOT NULL DEFAULT 'VND',

    -- Quote expires; passenger must confirm within this window
    expires_at          TIMESTAMPTZ  NOT NULL,

    -- Auditing
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_quotes_rule FOREIGN KEY (fare_rule_id)
        REFERENCES fare_rules(rule_id),
    CONSTRAINT fk_quotes_surge FOREIGN KEY (surge_zone_id)
        REFERENCES surge_zones(zone_id)
);

CREATE UNIQUE INDEX uq_quotes_trip_id       ON quotes(trip_id);
CREATE        INDEX idx_quotes_correlation  ON quotes(correlation_id);
```

---

## DATABASE 6 — `db_payment`

> **Owner:** Thành viên 1 (Core & Data Consistency)  
> **Purpose:** Simulated payment gateway — pre-authorization, charge, refund, ledger audit.

```sql
-- ============================================================
-- db_payment
-- ============================================================
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ── payments ─────────────────────────────────────────────────
CREATE TABLE payments (
    payment_id          UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),

    -- Soft cross-service references
    trip_id             UUID        NOT NULL,   -- soft ref → db_trip.trips.trip_id
    passenger_id        UUID        NOT NULL,   -- soft ref → db_identity.users.user_id

    amount              DECIMAL(10, 2) NOT NULL,
    currency            VARCHAR(3)   NOT NULL DEFAULT 'VND',

    -- PENDING → SUCCESS → REFUNDED, or PENDING → FAILED
    -- Same values as PaymentStatus (CODING_CONVENTION §12.4) and docs/api.md §7
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                            CHECK (status IN (
                                'PENDING', 'SUCCESS', 'FAILED', 'REFUNDED'
                            )),

    -- Simulated payment method
    payment_method      VARCHAR(20)  NOT NULL DEFAULT 'MOCK_CARD'
                            CHECK (payment_method IN ('MOCK_CARD', 'MOCK_WALLET', 'CASH')),
    gateway_ref         VARCHAR(255),           -- simulated gateway transaction ID
    failure_reason      TEXT,                   -- populated if status = FAILED

    -- Distributed systems primitives
    correlation_id      UUID        NOT NULL,
    idempotency_key     VARCHAR(255) NOT NULL,  -- client-provided; prevents double-charge

    -- OCC
    version             INTEGER      NOT NULL DEFAULT 1,

    -- Auditing
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    captured_at         TIMESTAMPTZ,
    refunded_at         TIMESTAMPTZ
);

-- The most critical constraint: idempotency_key prevents double-charge
CREATE UNIQUE INDEX uq_payments_idempotency_key ON payments(idempotency_key);
CREATE        INDEX idx_payments_trip_id        ON payments(trip_id);
CREATE        INDEX idx_payments_passenger_id   ON payments(passenger_id);
CREATE        INDEX idx_payments_status         ON payments(status);
CREATE        INDEX idx_payments_correlation    ON payments(correlation_id);

-- ── ledger ────────────────────────────────────────────────────
-- Append-only financial audit log (never UPDATE or DELETE).
-- Every state change to a payment creates a ledger entry.
CREATE TABLE ledger (
    ledger_id           UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    payment_id          UUID        NOT NULL,   -- FK within same DB
    trip_id             UUID        NOT NULL,   -- soft ref (for audit queries)

    entry_type          VARCHAR(30)  NOT NULL
                            CHECK (entry_type IN (
                                'PREAUTH', 'PREAUTH_REVERSAL',
                                'CAPTURE', 'REFUND', 'ADJUSTMENT'
                            )),
    amount              DECIMAL(10, 2) NOT NULL,  -- positive = debit, negative = credit
    currency            VARCHAR(3)   NOT NULL DEFAULT 'VND',
    description         TEXT,

    -- Auditing (append-only: no updated_at)
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_ledger_payment FOREIGN KEY (payment_id)
        REFERENCES payments(payment_id)
);

CREATE INDEX idx_ledger_payment_id ON ledger(payment_id);
CREATE INDEX idx_ledger_trip_id    ON ledger(trip_id);
CREATE INDEX idx_ledger_created_at ON ledger(created_at DESC);

-- ── outbox_events ─────────────────────────────────────────────
-- Payment Service publishes payment.success / payment.failed events
-- via the Transactional Outbox Pattern (same TX as ledger insert).
CREATE TABLE outbox_events (
    event_id            UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    aggregate_type      VARCHAR(50)  NOT NULL DEFAULT 'Payment',
    aggregate_id        UUID        NOT NULL,   -- payment_id
    event_type          VARCHAR(80)  NOT NULL,  -- 'PaymentSucceeded' or 'PaymentFailed' (PascalCase, docs/events.md §2)
    payload             JSONB       NOT NULL,
    correlation_id      UUID        NOT NULL,

    published           BOOLEAN      NOT NULL DEFAULT FALSE,
    published_at        TIMESTAMPTZ,
    retry_count         SMALLINT     NOT NULL DEFAULT 0,
    last_error          TEXT,

    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_payment_outbox_unpublished
    ON outbox_events(created_at ASC)
    WHERE published = FALSE;

-- ── processed_events ──────────────────────────────────────────
CREATE TABLE processed_events (
    event_id            UUID        NOT NULL,
    consumer_name       VARCHAR(80)  NOT NULL,
    processed_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    result_summary      TEXT,

    PRIMARY KEY (event_id, consumer_name)
);
```

---

## DATABASE 7 — `db_notification`

> **Owner:** Thành viên 3 (Foundation & Algorithm)  
> **Purpose:** WebSocket push notifications to passengers and drivers; delivery tracking.

```sql
-- ============================================================
-- db_notification
-- ============================================================
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ── notifications ─────────────────────────────────────────────
CREATE TABLE notifications (
    notification_id     UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),

    -- Recipient (soft cross-service reference)
    recipient_user_id   UUID        NOT NULL,   -- soft ref → db_identity.users.user_id
    recipient_role      VARCHAR(20)  NOT NULL
                            CHECK (recipient_role IN ('PASSENGER', 'DRIVER')),

    -- Context (soft references for query/filtering)
    trip_id             UUID,                   -- soft ref → db_trip.trips.trip_id
    correlation_id      UUID        NOT NULL,

    -- Notification content
    event_type          VARCHAR(80)  NOT NULL,  -- e.g., 'driver.assigned', 'trip.cancelled'
    channel             VARCHAR(20)  NOT NULL DEFAULT 'WEBSOCKET'
                            CHECK (channel IN ('WEBSOCKET', 'PUSH', 'SMS', 'EMAIL')),
    title               VARCHAR(200),
    message             TEXT        NOT NULL,
    payload             JSONB       NOT NULL DEFAULT '{}',  -- structured data for frontend

    -- Delivery tracking
    -- PENDING → SENT → DELIVERED → FAILED
    delivery_status     VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                            CHECK (delivery_status IN ('PENDING', 'SENT', 'DELIVERED', 'FAILED')),
    sent_at             TIMESTAMPTZ,
    delivered_at        TIMESTAMPTZ,
    failure_reason      TEXT,
    retry_count         SMALLINT     NOT NULL DEFAULT 0,

    -- Auditing
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_notifications_recipient    ON notifications(recipient_user_id, created_at DESC);
CREATE INDEX idx_notifications_trip_id      ON notifications(trip_id);
CREATE INDEX idx_notifications_correlation  ON notifications(correlation_id);
-- Relay job: find unsent notifications to retry
CREATE INDEX idx_notifications_pending      ON notifications(created_at ASC)
    WHERE delivery_status IN ('PENDING', 'FAILED') AND retry_count < 3;

-- ── processed_events ──────────────────────────────────────────
CREATE TABLE processed_events (
    event_id            UUID        NOT NULL,
    consumer_name       VARCHAR(80)  NOT NULL,
    processed_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    result_summary      TEXT,

    PRIMARY KEY (event_id, consumer_name)
);
```

---

## Shared Utility: `updated_at` Trigger

> Apply this trigger to every table that has an `updated_at` column. This is cleaner and more reliable than relying on application code.

```sql
-- ── Reusable trigger function (run once per database) ──
CREATE OR REPLACE FUNCTION trigger_set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ── Example: apply to db_trip.trips ──
CREATE TRIGGER trg_trips_updated_at
    BEFORE UPDATE ON trips
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();

-- ── Apply the same pattern to every table with updated_at ──
-- (users, drivers, vehicles, trips, payments, notifications, etc.)
```

---

## Technical Explanation: CAS Concurrency Control on `drivers.version`

The `version` column in `db_driver.drivers` implements **Optimistic Concurrency Control (OCC)** — the mechanism that solves the C1 race condition (two passengers competing for the same driver).

### The Race Condition (C1)

```
Time → T1                    T2
P1:  Read driver(v=5, AVAILABLE)
P2:            Read driver(v=5, AVAILABLE)
P1:  UPDATE ... WHERE version=5 → rowCount=1 ✅ RESERVED (v=6)
P2:            UPDATE ... WHERE version=5 → rowCount=0 ❌ CONFLICT → 409
```

### The CAS Query (executed by Matching Service via REST to Driver Service)

```sql
-- Step 1: Matching reads candidate drivers
SELECT driver_id, status, version
FROM   drivers
WHERE  status = 'AVAILABLE'
ORDER  BY distance_to_pickup ASC
LIMIT  5;

-- Step 2: Matching attempts to atomically reserve the top candidate
UPDATE drivers
   SET status            = 'RESERVED',
       reserved_for_trip = $1,   -- trip_id
       reserved_until    = NOW() + INTERVAL '30 seconds',
       version           = version + 1
 WHERE driver_id  = $2           -- target driver
   AND status     = 'AVAILABLE'  -- guard: still available?
   AND version    = $3           -- CAS: version unchanged?
RETURNING driver_id, version;

-- If rowCount = 0 → CONFLICT → try next candidate in the list
-- If rowCount = 1 → SUCCESS   → publish driver.reserved event
```

### Why this is safe without distributed locks

| Scenario | Outcome |
|---|---|
| Only one Matching instance runs | CAS always succeeds on first try |
| Two Matching instances race for same driver | Exactly one UPDATE matches `version=$v`, the other gets `rowCount=0` → falls back to next candidate |
| Driver goes OFFLINE between read and write | `status = 'AVAILABLE'` guard fails → `rowCount=0` → try next |
| Redis GEO cache stale but DB has latest | DB is source of truth; CAS enforces correctness even with stale cache |

### The Partial Unique Index as a Safety Net

```sql
-- Belt-and-suspenders: even if CAS logic has a bug,
-- this index ensures a driver can never hold two reservations simultaneously.
CREATE UNIQUE INDEX uq_driver_active_reservation
    ON drivers(reserved_for_trip)
    WHERE reserved_for_trip IS NOT NULL;
```

A duplicate `INSERT` or `UPDATE` that tries to set the same `reserved_for_trip` on two different driver rows will throw a `UNIQUE_VIOLATION` error — the database itself acts as the last line of defense.

---

## Index Strategy Summary

| Table | Index | Rationale |
|---|---|---|
| `users` | `UNIQUE (email)` | Login lookup — executed on every request |
| `refresh_tokens` | `PARTIAL (user_id, expires_at) WHERE revoked=FALSE` | Only non-revoked tokens need fast scans |
| `drivers` | `PARTIAL (status) WHERE status='AVAILABLE'` | Matching queries only AVAILABLE drivers |
| `drivers` | `UNIQUE (reserved_for_trip) WHERE NOT NULL` | Invariant: one driver → one active trip |
| `trips` | `UNIQUE (idempotency_key)` | Prevent duplicate trip creation on retry |
| `outbox_events` | `PARTIAL (created_at) WHERE published=FALSE` | Relay worker fetches only unpublished rows |
| `processed_events` | `PK (event_id, consumer_name)` | O(1) deduplication check per event |
| `payments` | `UNIQUE (idempotency_key)` | Prevent double-charge on message replay |
| `ledger` | `(payment_id)` + `(created_at DESC)` | Append-only audit; range queries by time |
