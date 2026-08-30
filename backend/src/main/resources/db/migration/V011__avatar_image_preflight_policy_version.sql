ALTER TABLE avatar_profile
    ADD COLUMN revision int NOT NULL DEFAULT 1,
    ADD CONSTRAINT avatar_profile_status_check
        CHECK (status IN ('ACTIVE', 'DELETING')),
    ADD CONSTRAINT avatar_profile_user_id_key UNIQUE (user_id);

ALTER TABLE avatar_image
    ADD COLUMN source_upload_id uuid NULL,
    ADD COLUMN lifecycle_state text NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN warnings text[] NOT NULL DEFAULT '{}',
    ADD COLUMN preflight_policy_version text NULL,
    ADD CONSTRAINT avatar_image_width_check
        CHECK (width IS NULL OR width > 0),
    ADD CONSTRAINT avatar_image_height_check
        CHECK (height IS NULL OR height > 0),
    ADD CONSTRAINT avatar_image_source_upload_id_key UNIQUE (source_upload_id),
    ADD CONSTRAINT avatar_image_id_profile_id_key UNIQUE (id, profile_id),
    ADD CONSTRAINT avatar_image_storage_key_key UNIQUE (storage_key),
    ADD CONSTRAINT avatar_image_preflight_policy_version_check
        CHECK (preflight_policy_version IS NULL OR length(btrim(preflight_policy_version)) > 0);

ALTER TABLE avatar_profile
    DROP CONSTRAINT avatar_profile_primary_image_id_fkey,
    ADD CONSTRAINT avatar_profile_primary_image_id_fkey
        FOREIGN KEY (primary_image_id, id) REFERENCES avatar_image (id, profile_id);

CREATE TABLE avatar_consumed_upload (
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    upload_id uuid NOT NULL,
    consumed_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, upload_id)
);
