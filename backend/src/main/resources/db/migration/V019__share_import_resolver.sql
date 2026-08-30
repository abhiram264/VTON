-- Additive resolver workflow columns for share_import (slice 27).
ALTER TABLE share_import ADD COLUMN idempotency_key text;
ALTER TABLE share_import ADD COLUMN request_fingerprint text;
ALTER TABLE share_import ADD COLUMN attempt_count integer NOT NULL DEFAULT 0;
ALTER TABLE share_import ADD COLUMN attempt_leased_at timestamptz;
ALTER TABLE share_import ADD COLUMN attempt_token uuid;
ALTER TABLE share_import ADD COLUMN terminal_reason_code text;
ALTER TABLE share_import ADD COLUMN recovery_key text;
ALTER TABLE share_import ADD COLUMN recovery_upload_id uuid;
ALTER TABLE share_import ADD COLUMN source_title text;
ALTER TABLE share_import ADD COLUMN source_site_name text;

-- Backfill idempotency columns for pre-existing rows, then enforce presence.
UPDATE share_import SET idempotency_key = id::text WHERE idempotency_key IS NULL;
UPDATE share_import SET request_fingerprint = 'legacy:' || id::text WHERE request_fingerprint IS NULL;
ALTER TABLE share_import ALTER COLUMN idempotency_key SET NOT NULL;

-- Migrate the legacy free-text state vocabulary.
UPDATE share_import SET resolver_state = 'RESOLVING' WHERE resolver_state = 'pending';

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM share_import
        WHERE resolver_state NOT IN ('RESOLVING', 'READY', 'NEEDS_IMAGE', 'FAILED_RETRYABLE', 'FAILED_TERMINAL', 'DELETED')
    ) THEN
        RAISE EXCEPTION 'share_import contains resolver_state values outside the frozen vocabulary';
    END IF;
END
$$;

ALTER TABLE share_import
    ADD CONSTRAINT share_import_user_id_idempotency_key_key UNIQUE (user_id, idempotency_key);
