ALTER TABLE generation_job
    ADD COLUMN retry_not_before_at timestamptz NULL,
    ADD COLUMN retry_policy_snapshot text NULL,
    ADD CONSTRAINT generation_job_retry_not_before_check
        CHECK (retry_not_before_at IS NULL OR status = 'RETRYING');

CREATE INDEX generation_job_retry_not_before_idx
    ON generation_job (retry_not_before_at)
    WHERE status = 'RETRYING'
      AND retry_not_before_at IS NOT NULL;

ALTER TABLE generation_attempt
    ADD CONSTRAINT generation_attempt_transmission_count_check
        CHECK (transmission_count >= 0 AND transmission_count <= 3);
