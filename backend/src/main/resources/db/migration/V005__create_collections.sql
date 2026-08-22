CREATE TABLE collection (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    name text NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE INDEX collection_user_id_idx ON collection (user_id);

CREATE TABLE collection_item (
    collection_id uuid NOT NULL REFERENCES collection (id) ON DELETE CASCADE,
    vault_item_id uuid NOT NULL REFERENCES vault_item (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (collection_id, vault_item_id)
);

CREATE INDEX collection_item_vault_item_id_idx ON collection_item (vault_item_id);
