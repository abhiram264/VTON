CREATE TABLE look (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    avatar_profile_id uuid NULL REFERENCES avatar_profile (id),
    generation_job_id uuid NULL REFERENCES generation_job (id),
    studio_session_id uuid NULL REFERENCES studio_session (id),
    storage_key text NOT NULL,
    render_plan jsonb NULL,
    provider text NULL,
    provider_model text NULL,
    quality_status text NOT NULL,
    created_at timestamptz NOT NULL
);

CREATE INDEX look_user_id_idx ON look (user_id);

CREATE TABLE look_item (
    look_id uuid NOT NULL REFERENCES look (id),
    vault_item_id uuid NOT NULL REFERENCES vault_item (id),
    slot text NULL,
    PRIMARY KEY (look_id, vault_item_id)
);
