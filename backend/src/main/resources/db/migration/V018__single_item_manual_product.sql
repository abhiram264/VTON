ALTER TABLE generation_job
    ADD COLUMN product_source_upload_id uuid NULL,
    ADD COLUMN product_media_id uuid NULL,
    ADD COLUMN render_step_ordinal int NULL,
    ADD COLUMN render_step_count int NULL,
    ADD CONSTRAINT generation_job_product_source_upload_id_key UNIQUE (product_source_upload_id),
    ADD CONSTRAINT generation_job_single_item_check CHECK (
        (product_source_upload_id IS NULL AND product_media_id IS NULL
            AND render_step_ordinal IS NULL AND render_step_count IS NULL)
        OR (product_source_upload_id IS NOT NULL AND product_media_id IS NOT NULL
            AND render_step_ordinal = 1 AND render_step_count = 1)
    );

ALTER TABLE look
    ADD COLUMN deleted_at timestamptz NULL,
    ADD CONSTRAINT look_generation_job_id_key UNIQUE (generation_job_id);
