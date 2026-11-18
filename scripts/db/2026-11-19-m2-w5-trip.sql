-- Apply to trip_db before deploying the W5 Trip Service.
ALTER TABLE trips
    ADD COLUMN IF NOT EXISTS pending_driver_id UUID,
    ADD COLUMN IF NOT EXISTS pending_reservation_version INTEGER,
    ADD COLUMN IF NOT EXISTS pending_reserved_until TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS pending_source_event_id UUID,
    ADD COLUMN IF NOT EXISTS pending_assignment_event_id UUID;

ALTER TABLE trips DROP CONSTRAINT IF EXISTS trips_status_check;
ALTER TABLE trips ADD CONSTRAINT trips_status_check CHECK (status IN (
    'REQUESTED', 'PRICED', 'SEARCHING', 'ASSIGNING', 'ASSIGNED',
    'IN_PROGRESS', 'COMPLETED', 'PAID', 'PAYMENT_FAILED', 'CANCELLED', 'FAILED'
));
