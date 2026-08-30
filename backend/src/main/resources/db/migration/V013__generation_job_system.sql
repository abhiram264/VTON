ALTER TABLE generation_job
    ADD COLUMN status_version int NOT NULL DEFAULT 0,
    ADD COLUMN request_fingerprint text NULL,
    ADD COLUMN avatar_revision int NULL,
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN terminal_reason text NULL,
    ADD COLUMN current_step int NOT NULL DEFAULT 1,
    ADD COLUMN total_steps int NOT NULL DEFAULT 1,
    ADD COLUMN render_plan_version int NOT NULL DEFAULT 1,
    ADD COLUMN orchestration_deadline_at timestamptz NOT NULL DEFAULT (now() + INTERVAL '30 minutes');

UPDATE generation_job
SET updated_at = created_at,
    orchestration_deadline_at = created_at + INTERVAL '30 minutes';

UPDATE generation_job
SET status = CASE status
    WHEN 'SUCCEEDED' THEN 'COMPLETED'
    WHEN 'SUCCESS' THEN 'COMPLETED'
    WHEN 'RUNNING' THEN 'GENERATING'
    WHEN 'IN_PROGRESS' THEN 'GENERATING'
    WHEN 'PROCESSING' THEN 'GENERATING'
    WHEN 'CANCELLED' THEN 'FAILED'
    WHEN 'CANCELED' THEN 'FAILED'
    ELSE status
END;

UPDATE generation_job
SET status = 'FAILED',
    terminal_reason = CASE
        WHEN subject_deleted_at IS NOT NULL THEN 'SUBJECT_DELETED'
        WHEN cancelled_at IS NOT NULL THEN 'CANCELLED_BY_USER'
        ELSE terminal_reason
    END,
    completed_at = COALESCE(completed_at, subject_deleted_at, cancelled_at, updated_at, created_at)
WHERE cancelled_at IS NOT NULL
   OR subject_deleted_at IS NOT NULL;

UPDATE generation_job
SET completed_at = COALESCE(completed_at, updated_at, created_at)
WHERE status IN ('COMPLETED', 'FAILED')
  AND completed_at IS NULL;

UPDATE generation_job
SET status = 'COMPLETED'
WHERE status NOT IN ('COMPLETED', 'FAILED')
  AND completed_at IS NOT NULL;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM generation_job
        WHERE status NOT IN (
            'QUEUED',
            'PREFLIGHT',
            'SUBMITTED',
            'GENERATING',
            'QUALITY_CHECK',
            'RETRYING',
            'COMPLETED',
            'FAILED'
        )
    ) THEN
        RAISE EXCEPTION 'generation_job.status has values that do not map to the canonical lifecycle';
    END IF;
END
$$;

ALTER TABLE generation_job
    DROP CONSTRAINT generation_job_client_request_id_key,
    ADD CONSTRAINT generation_job_user_id_client_request_id_key UNIQUE (user_id, client_request_id),
    ADD CONSTRAINT generation_job_status_check
        CHECK (status IN (
            'QUEUED',
            'PREFLIGHT',
            'SUBMITTED',
            'GENERATING',
            'QUALITY_CHECK',
            'RETRYING',
            'COMPLETED',
            'FAILED'
        )),
    ADD CONSTRAINT generation_job_completed_at_check
        CHECK (
            (status IN ('COMPLETED', 'FAILED') AND completed_at IS NOT NULL)
            OR (status NOT IN ('COMPLETED', 'FAILED') AND completed_at IS NULL)
        ),
    ADD CONSTRAINT generation_job_cancelled_at_check
        CHECK (cancelled_at IS NULL OR status = 'FAILED'),
    ADD CONSTRAINT generation_job_subject_deleted_at_check
        CHECK (subject_deleted_at IS NULL OR status = 'FAILED'),
    ADD CONSTRAINT generation_job_total_steps_check
        CHECK (total_steps >= 1),
    ADD CONSTRAINT generation_job_current_step_check
        CHECK (current_step >= 1 AND current_step <= total_steps);

CREATE INDEX generation_job_user_id_created_at_idx
    ON generation_job (user_id, created_at DESC);

CREATE INDEX generation_job_active_status_updated_at_idx
    ON generation_job (status, updated_at)
    WHERE status IN (
        'QUEUED',
        'PREFLIGHT',
        'SUBMITTED',
        'GENERATING',
        'QUALITY_CHECK',
        'RETRYING'
    );

CREATE INDEX generation_job_active_deadline_idx
    ON generation_job (orchestration_deadline_at)
    WHERE status IN (
        'QUEUED',
        'PREFLIGHT',
        'SUBMITTED',
        'GENERATING',
        'QUALITY_CHECK',
        'RETRYING'
    );

CREATE TABLE generation_render_step (
    id uuid PRIMARY KEY,
    generation_job_id uuid NOT NULL REFERENCES generation_job (id),
    render_plan_version int NOT NULL,
    step_index int NOT NULL,
    capability_snapshot text NULL,
    created_at timestamptz NOT NULL,
    UNIQUE (generation_job_id, step_index)
);

CREATE TABLE generation_render_step_item (
    id uuid PRIMARY KEY,
    render_step_id uuid NOT NULL REFERENCES generation_render_step (id),
    item_ordinal int NOT NULL,
    vault_item_id uuid NOT NULL REFERENCES vault_item (id),
    category text NULL,
    slot text NULL,
    created_at timestamptz NOT NULL,
    UNIQUE (render_step_id, item_ordinal),
    UNIQUE (render_step_id, vault_item_id)
);

INSERT INTO generation_render_step (
    id,
    generation_job_id,
    render_plan_version,
    step_index,
    created_at
)
SELECT
    gen_random_uuid(),
    j.id,
    j.render_plan_version,
    1,
    j.created_at
FROM generation_job j
WHERE NOT EXISTS (
    SELECT 1
    FROM generation_job_product p
    WHERE p.generation_job_id = j.id
);

INSERT INTO generation_render_step (
    id,
    generation_job_id,
    render_plan_version,
    step_index,
    created_at
)
SELECT
    gen_random_uuid(),
    ranked.generation_job_id,
    j.render_plan_version,
    ranked.step_index,
    j.created_at
FROM (
    SELECT
        generation_job_id,
        vault_item_id,
        row_number() OVER (PARTITION BY generation_job_id ORDER BY vault_item_id) AS step_index
    FROM generation_job_product
) ranked
JOIN generation_job j ON j.id = ranked.generation_job_id;

INSERT INTO generation_render_step_item (
    id,
    render_step_id,
    item_ordinal,
    vault_item_id,
    created_at
)
SELECT
    gen_random_uuid(),
    s.id,
    1,
    ranked.vault_item_id,
    s.created_at
FROM (
    SELECT
        generation_job_id,
        vault_item_id,
        row_number() OVER (PARTITION BY generation_job_id ORDER BY vault_item_id) AS step_index
    FROM generation_job_product
) ranked
JOIN generation_render_step s
    ON s.generation_job_id = ranked.generation_job_id
    AND s.step_index = ranked.step_index;

UPDATE generation_job j
SET total_steps = GREATEST(
    1,
    (SELECT COUNT(*) FROM generation_render_step s WHERE s.generation_job_id = j.id)
);

ALTER TABLE generation_attempt
    ADD COLUMN render_step_id uuid NULL REFERENCES generation_render_step (id),
    ADD COLUMN transmission_count int NOT NULL DEFAULT 0,
    ADD COLUMN provider_accepted_at timestamptz NULL,
    ADD COLUMN execution_deadline_at timestamptz NULL,
    ADD COLUMN submission_recovery_deadline_at timestamptz NULL,
    ADD COLUMN quality_policy_version text NULL,
    ADD COLUMN pricing_catalog_version text NULL,
    ADD COLUMN estimated_units numeric NULL,
    ADD COLUMN unit_price numeric NULL,
    ADD COLUMN estimated_cost_ceiling numeric NULL,
    ADD COLUMN actual_cost numeric NULL,
    ADD COLUMN cost_status text NULL,
    ADD CONSTRAINT generation_attempt_cost_status_check
        CHECK (cost_status IS NULL OR cost_status IN ('ESTIMATED', 'FINAL', 'UNKNOWN'));

UPDATE generation_attempt
SET submission_key = id
WHERE submission_key IS NULL;

UPDATE generation_attempt a
SET render_step_id = s.id
FROM generation_render_step s
WHERE s.generation_job_id = a.generation_job_id
  AND s.step_index = 1
  AND a.render_step_id IS NULL;

CREATE UNIQUE INDEX generation_attempt_submission_key_uidx
    ON generation_attempt (submission_key)
    WHERE submission_key IS NOT NULL;

CREATE UNIQUE INDEX generation_attempt_provider_provider_job_id_uidx
    ON generation_attempt (provider, provider_job_id)
    WHERE provider_job_id IS NOT NULL;

CREATE INDEX generation_attempt_provider_job_id_idx
    ON generation_attempt (provider_job_id)
    WHERE provider_job_id IS NOT NULL;

CREATE INDEX generation_attempt_job_step_attempt_idx
    ON generation_attempt (generation_job_id, render_step_id, attempt_number);

CREATE TABLE generation_work_outbox (
    id uuid PRIMARY KEY,
    generation_job_id uuid NOT NULL REFERENCES generation_job (id),
    not_before timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    publish_state text NOT NULL DEFAULT 'PENDING',
    attempt_count int NOT NULL DEFAULT 0,
    last_error_category text NULL,
    sent_at timestamptz NULL,
    claimed_at timestamptz NULL,
    claimed_until timestamptz NULL,
    CHECK (publish_state IN ('PENDING', 'CLAIMED', 'SENT'))
);

CREATE INDEX generation_work_outbox_pending_not_before_idx
    ON generation_work_outbox (not_before)
    WHERE publish_state = 'PENDING';

CREATE TABLE generation_callback_inbox (
    id uuid PRIMARY KEY,
    dedupe_hash text NOT NULL,
    generation_attempt_id uuid NULL REFERENCES generation_attempt (id),
    candidate_provider_job_id text NULL,
    safe_terminal_hint text NULL,
    received_at timestamptz NOT NULL,
    UNIQUE (dedupe_hash)
);

CREATE TABLE generation_notification_event (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    generation_job_id uuid NOT NULL REFERENCES generation_job (id),
    type text NOT NULL,
    look_id uuid NULL REFERENCES look (id),
    created_at timestamptz NOT NULL,
    deadline_at timestamptz NOT NULL,
    UNIQUE (generation_job_id, type),
    CHECK (type IN ('LOOK_READY', 'GENERATION_FAILED'))
);

CREATE TABLE generation_notification_delivery (
    id uuid PRIMARY KEY,
    event_id uuid NOT NULL REFERENCES generation_notification_event (id),
    device_identity text NOT NULL,
    status text NOT NULL,
    attempt_count int NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NULL,
    provider_message_id text NULL,
    terminal_result text NULL,
    UNIQUE (event_id, device_identity)
);

CREATE TABLE generation_quota_reservation (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    generation_job_id uuid NOT NULL REFERENCES generation_job (id),
    reserved_at timestamptz NOT NULL,
    released_at timestamptz NULL,
    UNIQUE (generation_job_id)
);

CREATE INDEX generation_quota_reservation_user_reserved_idx
    ON generation_quota_reservation (user_id, reserved_at)
    WHERE released_at IS NULL;

CREATE TABLE generation_provider_capacity_slot (
    id uuid PRIMARY KEY,
    provider_key text NOT NULL,
    generation_attempt_id uuid NULL REFERENCES generation_attempt (id),
    leased_at timestamptz NOT NULL,
    lease_expires_at timestamptz NOT NULL,
    released_at timestamptz NULL
);

CREATE UNIQUE INDEX generation_provider_capacity_slot_attempt_uidx
    ON generation_provider_capacity_slot (generation_attempt_id)
    WHERE generation_attempt_id IS NOT NULL AND released_at IS NULL;

CREATE VIEW generation_legacy_submit_owned_without_provider_job AS
SELECT
    a.id AS generation_attempt_id,
    a.generation_job_id,
    a.submit_owned_at,
    a.submission_key,
    a.provider
FROM generation_attempt a
WHERE a.submit_owned_at IS NOT NULL
  AND a.provider_job_id IS NULL;
