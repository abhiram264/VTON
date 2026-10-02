CREATE TABLE device_registration (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    device_token text NOT NULL,
    platform text NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    last_seen_at timestamptz NOT NULL,
    CONSTRAINT device_registration_device_token_key UNIQUE (device_token),
    CONSTRAINT device_registration_platform_check CHECK (platform IN ('ANDROID', 'IOS')),
    CONSTRAINT device_registration_device_token_nonblank_check CHECK (btrim(device_token) <> '')
);

CREATE TABLE notification_preference (
    user_id uuid PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    push_enabled boolean NOT NULL,
    updated_at timestamptz NOT NULL
);
