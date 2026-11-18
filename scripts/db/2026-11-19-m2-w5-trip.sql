-- Apply to trip_db before deploying the W5 Trip Service.
ALTER TABLE trips
    ADD COLUMN IF NOT EXISTS pending_driver_id UUID,
    ADD COLUMN IF NOT EXISTS pending_reservation_version INTEGER,
    ADD COLUMN IF NOT EXISTS pending_reserved_until TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS pending_source_event_id UUID,
    ADD COLUMN IF NOT EXISTS pending_assignment_event_id UUID;

-- Hibernate and hand-written baseline schemas can name enum checks differently.
-- Remove only status-related CHECKs on these two tables before adding the W5 set.
DO $m2$
DECLARE old_check RECORD;
BEGIN
    FOR old_check IN
        SELECT conrelid::regclass AS table_name, conname
        FROM pg_constraint
        WHERE conrelid IN ('trips'::regclass, 'trip_status_history'::regclass)
          AND contype = 'c'
          AND pg_get_constraintdef(oid) LIKE '%status%'
    LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', old_check.table_name, old_check.conname);
    END LOOP;
END
$m2$;

ALTER TABLE trips ADD CONSTRAINT trips_status_check CHECK (status IN (
    'REQUESTED', 'PRICED', 'SEARCHING', 'ASSIGNING', 'ASSIGNED',
    'IN_PROGRESS', 'COMPLETED', 'PAID', 'PAYMENT_FAILED', 'CANCELLED', 'FAILED'
));

ALTER TABLE trip_status_history ADD CONSTRAINT trip_status_history_from_status_check CHECK (from_status IN (
    'REQUESTED', 'PRICED', 'SEARCHING', 'ASSIGNING', 'ASSIGNED',
    'IN_PROGRESS', 'COMPLETED', 'PAID', 'PAYMENT_FAILED', 'CANCELLED', 'FAILED'
));
ALTER TABLE trip_status_history ADD CONSTRAINT trip_status_history_to_status_check CHECK (to_status IN (
    'REQUESTED', 'PRICED', 'SEARCHING', 'ASSIGNING', 'ASSIGNED',
    'IN_PROGRESS', 'COMPLETED', 'PAID', 'PAYMENT_FAILED', 'CANCELLED', 'FAILED'
));
