-- Apply to matching_db before deploying the W5 Matching Service.
ALTER TABLE match_requests
    ADD COLUMN IF NOT EXISTS active_reservation_event_id UUID,
    ADD COLUMN IF NOT EXISTS active_reservation_version INTEGER,
    ADD COLUMN IF NOT EXISTS active_reserved_until TIMESTAMPTZ;
