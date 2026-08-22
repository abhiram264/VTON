CREATE TABLE avatar_profile (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    primary_image_id uuid NULL,
    status text NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE INDEX avatar_profile_user_id_idx ON avatar_profile (user_id);

CREATE TABLE avatar_image (
    id uuid PRIMARY KEY,
    profile_id uuid NOT NULL REFERENCES avatar_profile (id),
    storage_key text NOT NULL,
    width int NULL,
    height int NULL,
    pose text NULL,
    quality_status text NOT NULL,
    created_at timestamptz NOT NULL
);

CREATE INDEX avatar_image_profile_id_idx ON avatar_image (profile_id);

ALTER TABLE avatar_profile
    ADD CONSTRAINT avatar_profile_primary_image_id_fkey
    FOREIGN KEY (primary_image_id) REFERENCES avatar_image (id);
