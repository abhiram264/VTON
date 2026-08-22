CREATE TABLE source_reference (
    id uuid PRIMARY KEY,
    url text NULL,
    retailer text NULL,
    raw_metadata jsonb NULL,
    created_at timestamptz NOT NULL
);

CREATE TABLE vault_item (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    storage_key text NOT NULL,
    source_reference_id uuid NULL REFERENCES source_reference (id),
    name text NULL,
    brand text NULL,
    category text NULL,
    subcategory text NULL,
    color text NULL,
    pattern text NULL,
    attributes jsonb NULL,
    created_at timestamptz NOT NULL
);

CREATE INDEX vault_item_user_id_idx ON vault_item (user_id);
CREATE INDEX vault_item_source_reference_id_idx ON vault_item (source_reference_id);
