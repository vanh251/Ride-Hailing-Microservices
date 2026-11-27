-- Apply to trip_db before starting a trip-service without the payment provider field. Safe to rerun.
-- A trip_db whose column was created by Hibernate is NOT NULL without a default and would reject new trips.
ALTER TABLE trips DROP CONSTRAINT IF EXISTS trips_payment_provider_check;
ALTER TABLE trips DROP COLUMN IF EXISTS payment_provider;
