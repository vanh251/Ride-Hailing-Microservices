-- Apply to payment_db before restarting Payment Service. Safe to rerun.
BEGIN;
ALTER TABLE payments DROP CONSTRAINT IF EXISTS payments_status_check;
ALTER TABLE payments ADD CONSTRAINT payments_status_check
  CHECK (status IN ('PENDING','SUCCESS','FAILED','REFUNDED','REVIEW_REQUIRED'));
CREATE SEQUENCE IF NOT EXISTS payos_order_code_seq START WITH 100000 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS payos_attempts (
  order_code bigint PRIMARY KEY DEFAULT nextval('payos_order_code_seq'),
  payment_id uuid NOT NULL REFERENCES payments(payment_id),
  payment_link_id varchar(255) UNIQUE,
  checkout_url varchar(2048),
  state varchar(30) NOT NULL,
  expires_at timestamptz,
  last_checked_at timestamptz NOT NULL DEFAULT '1970-01-01T00:00:00Z',
  message varchar(500),
  captured_reference varchar(255)
);
CREATE INDEX IF NOT EXISTS idx_payos_attempt_payment ON payos_attempts(payment_id);
CREATE INDEX IF NOT EXISTS idx_payos_attempt_reconciliation ON payos_attempts(last_checked_at)
  WHERE state IN ('NEW','UNCERTAIN','PENDING','CANCELLED','EXPIRED');
CREATE TABLE IF NOT EXISTS payos_receipts (
  id uuid PRIMARY KEY,
  order_code bigint NOT NULL REFERENCES payos_attempts(order_code),
  provider_reference varchar(255) NOT NULL,
  amount bigint NOT NULL,
  currency varchar(3) NOT NULL,
  payload text NOT NULL,
  received_at timestamptz NOT NULL,
  UNIQUE(order_code,provider_reference)
);
-- Existing idempotency-key uniqueness ensures one canonical PAYOS obligation per trip.
-- Database uniqueness also prevents more than one capture for that obligation.
CREATE UNIQUE INDEX IF NOT EXISTS uq_payos_capture ON ledger(payment_id)
  WHERE entry_type = 'CAPTURE';
COMMIT;
