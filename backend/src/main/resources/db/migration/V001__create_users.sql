CREATE TABLE users (
    id uuid PRIMARY KEY,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    deleted_at timestamptz NULL
);

CREATE TABLE identity_mapping (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    issuer text NOT NULL,
    subject text NOT NULL,
    created_at timestamptz NOT NULL,
    UNIQUE (issuer, subject)
);

CREATE INDEX identity_mapping_user_id_idx ON identity_mapping (user_id);
