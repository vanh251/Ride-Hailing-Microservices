-- Driver acceptance is separate from the reservation acknowledgement.
ALTER TABLE trips ADD COLUMN IF NOT EXISTS accepted_at TIMESTAMPTZ;
