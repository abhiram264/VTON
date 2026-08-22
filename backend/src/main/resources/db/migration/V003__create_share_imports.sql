CREATE TABLE share_import (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    source_kind text NOT NULL,
    source_url text NULL,
    storage_key text NULL,
    resolver_state text NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE INDEX share_import_user_id_idx ON share_import (user_id);
