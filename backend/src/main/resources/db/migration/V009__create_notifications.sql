CREATE TABLE notification (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    type text NOT NULL,
    payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    read_at timestamptz NULL,
    created_at timestamptz NOT NULL
);

CREATE INDEX notification_user_id_idx ON notification (user_id);
