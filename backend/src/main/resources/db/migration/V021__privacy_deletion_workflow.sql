CREATE TABLE account_deletion_workflow (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users (id),
    next_step text NOT NULL,
    status text NOT NULL,
    requested_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    completed_at timestamptz NULL,
    lease_token uuid NULL,
    lease_expires_at timestamptz NULL,
    attempt_count int NOT NULL DEFAULT 0,
    last_error_code text NULL,
    CONSTRAINT account_deletion_workflow_user_id_key UNIQUE (user_id),
    CONSTRAINT account_deletion_workflow_next_step_check CHECK (next_step IN (
        'REQUESTED',
        'DELETE_AVATARS',
        'DELETE_VAULT',
        'DELETE_LOOKS',
        'DELETE_TEMPORARY_GENERATIONS',
        'REVOKE_AUTH',
        'REMOVE_PUSH_TOKENS',
        'ANONYMIZE_OPERATIONAL_DATA',
        'COMPLETE'
    )),
    CONSTRAINT account_deletion_workflow_status_check CHECK (status IN (
        'REQUESTED',
        'RUNNING',
        'RETRYABLE_FAILURE',
        'COMPLETE'
    )),
    CONSTRAINT account_deletion_workflow_lease_check CHECK (
        (lease_token IS NULL) = (lease_expires_at IS NULL)
    ),
    CONSTRAINT account_deletion_workflow_attempt_count_check CHECK (attempt_count >= 0),
    CONSTRAINT account_deletion_workflow_completion_check CHECK (
        (status = 'COMPLETE' AND next_step = 'COMPLETE' AND completed_at IS NOT NULL)
        OR (status <> 'COMPLETE' AND next_step <> 'COMPLETE' AND completed_at IS NULL)
    )
);
