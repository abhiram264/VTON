CREATE TABLE generation_job (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    studio_session_id uuid NULL REFERENCES studio_session (id),
    status text NOT NULL,
    requested_mode text NULL,
    client_request_id text NULL UNIQUE,
    created_at timestamptz NOT NULL,
    completed_at timestamptz NULL
);

CREATE INDEX generation_job_user_id_idx ON generation_job (user_id);

CREATE TABLE generation_attempt (
    id uuid PRIMARY KEY,
    generation_job_id uuid NOT NULL REFERENCES generation_job (id),
    provider text NOT NULL,
    model text NULL,
    provider_job_id text NULL,
    attempt_number int NOT NULL,
    started_at timestamptz NOT NULL,
    completed_at timestamptz NULL,
    latency_ms int NULL,
    credits_used numeric NULL,
    estimated_cost numeric NULL,
    currency text NULL,
    failure_code text NULL,
    technical_outcome text NULL,
    UNIQUE (generation_job_id, attempt_number)
);

CREATE INDEX generation_attempt_generation_job_id_idx ON generation_attempt (generation_job_id);

CREATE TABLE quality_result (
    id uuid PRIMARY KEY,
    generation_attempt_id uuid NOT NULL UNIQUE REFERENCES generation_attempt (id),
    decision text NOT NULL,
    identity_preservation numeric NULL,
    garment_fidelity numeric NULL,
    color_fidelity numeric NULL,
    anatomy_valid boolean NULL,
    artifact_detected boolean NULL,
    created_at timestamptz NOT NULL
);
