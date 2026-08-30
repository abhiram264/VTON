ALTER TABLE generation_job
    ADD COLUMN retry_of_job_id uuid NULL REFERENCES generation_job (id);

CREATE INDEX generation_job_retry_of_job_id_idx
    ON generation_job (retry_of_job_id)
    WHERE retry_of_job_id IS NOT NULL;

ALTER TABLE generation_attempt
    ADD COLUMN error_mapping_contract_version text NULL,
    ADD COLUMN evaluator_retry_count int NOT NULL DEFAULT 0,
    ADD CONSTRAINT generation_attempt_evaluator_retry_count_check
        CHECK (evaluator_retry_count >= 0 AND evaluator_retry_count <= 1),
    ADD CONSTRAINT generation_attempt_error_mapping_contract_version_check
        CHECK (error_mapping_contract_version IS NULL
            OR error_mapping_contract_version ~ '^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$');

ALTER TABLE quality_result
    ADD COLUMN evaluator_infrastructure boolean NOT NULL DEFAULT false;
