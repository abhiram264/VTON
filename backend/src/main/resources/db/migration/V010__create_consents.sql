CREATE TABLE consent (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    purpose text NOT NULL,
    version text NOT NULL,
    granted_at timestamptz NOT NULL,
    revoked_at timestamptz NULL
);

CREATE INDEX consent_user_id_idx ON consent (user_id);
CREATE INDEX consent_user_id_purpose_idx ON consent (user_id, purpose);
