CREATE TABLE studio_session (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    avatar_profile_id uuid NULL REFERENCES avatar_profile (id),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE INDEX studio_session_user_id_idx ON studio_session (user_id);

CREATE TABLE studio_item (
    id uuid PRIMARY KEY,
    studio_session_id uuid NOT NULL REFERENCES studio_session (id),
    slot text NOT NULL,
    vault_item_id uuid NOT NULL REFERENCES vault_item (id),
    position int NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    UNIQUE (studio_session_id, slot, position)
);

CREATE INDEX studio_item_studio_session_id_idx ON studio_item (studio_session_id);
