-- Apply to trip_db before deploying payOS-enabled Trip Service.
-- The default preserves inserts from older service versions during rollout.
ALTER TABLE trips ADD COLUMN IF NOT EXISTS payment_provider VARCHAR(10);
ALTER TABLE trips ALTER COLUMN payment_provider SET DEFAULT 'MOCK';
UPDATE trips SET payment_provider = 'MOCK' WHERE payment_provider IS NULL;
ALTER TABLE trips ALTER COLUMN payment_provider SET NOT NULL;
ALTER TABLE trips DROP CONSTRAINT IF EXISTS trips_payment_provider_check;
ALTER TABLE trips ADD CONSTRAINT trips_payment_provider_check
    CHECK (payment_provider IN ('MOCK', 'PAYOS'));
