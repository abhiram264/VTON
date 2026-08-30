ALTER TABLE generation_job
    ADD COLUMN avatar_image_id uuid NULL REFERENCES avatar_image (id),
    ADD COLUMN cancelled_at timestamptz NULL,
    ADD COLUMN subject_deleted_at timestamptz NULL;

CREATE TABLE generation_job_product (
    generation_job_id uuid NOT NULL REFERENCES generation_job (id),
    vault_item_id uuid NOT NULL REFERENCES vault_item (id),
    PRIMARY KEY (generation_job_id, vault_item_id)
);

ALTER TABLE generation_attempt
    ADD COLUMN submission_key uuid NULL,
    ADD COLUMN submit_owned_at timestamptz NULL,
    ADD COLUMN submission_indeterminate boolean NOT NULL DEFAULT false,
    ADD COLUMN temp_storage_key text NULL,
    ADD COLUMN provider_output_ref text NULL,
    ADD CONSTRAINT generation_attempt_temp_storage_key_check
        CHECK (temp_storage_key IS NULL OR (lower(temp_storage_key) NOT LIKE 'http://%' AND lower(temp_storage_key) NOT LIKE 'https://%')),
    ADD CONSTRAINT generation_attempt_provider_output_ref_check
        CHECK (provider_output_ref IS NULL OR (lower(provider_output_ref) NOT LIKE 'http://%' AND lower(provider_output_ref) NOT LIKE 'https://%'));

CREATE UNIQUE INDEX look_one_per_generation_job ON look (generation_job_id) WHERE generation_job_id IS NOT NULL;
