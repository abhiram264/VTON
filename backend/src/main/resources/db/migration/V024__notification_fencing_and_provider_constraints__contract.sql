ALTER TABLE generation_notification_delivery
    ADD COLUMN claim_token uuid,
    ADD COLUMN claim_epoch bigint NOT NULL DEFAULT 0,
    ADD CONSTRAINT generation_notification_delivery_claim_epoch_nonnegative_check CHECK (claim_epoch >= 0);


ALTER TABLE collection
    ADD CONSTRAINT collection_id_user_id_key UNIQUE (id, user_id);

ALTER TABLE collection_item
    ADD COLUMN user_id uuid;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM collection_item item
        LEFT JOIN collection owner ON owner.id = item.collection_id
        WHERE owner.id IS NULL
    ) THEN
        RAISE EXCEPTION 'V024 cannot backfill collection_item.user_id: orphan collection reference';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM collection_item item
        JOIN collection owner ON owner.id = item.collection_id
        JOIN vault_item vault ON vault.id = item.vault_item_id
        WHERE vault.user_id <> owner.user_id
    ) THEN
        RAISE EXCEPTION 'V024 cannot backfill collection_item.user_id: collection/vault owner mismatch';
    END IF;
END $$;

DO $$
DECLARE
    rows_updated integer;
BEGIN
    LOOP
        WITH batch AS (
            SELECT item.ctid, item.collection_id
            FROM collection_item item
            JOIN collection owner ON owner.id = item.collection_id
            WHERE item.user_id IS NULL
            LIMIT 1000
        )
        UPDATE collection_item item
        SET user_id = owner.user_id
        FROM batch
        JOIN collection owner ON owner.id = batch.collection_id
        WHERE item.ctid = batch.ctid;
        GET DIAGNOSTICS rows_updated = ROW_COUNT;
        EXIT WHEN rows_updated = 0;
    END LOOP;
END $$;

ALTER TABLE collection_item
    ADD CONSTRAINT collection_item_user_id_not_null_check CHECK (user_id IS NOT NULL) NOT VALID;

ALTER TABLE collection_item
    VALIDATE CONSTRAINT collection_item_user_id_not_null_check;

ALTER TABLE collection_item
    ALTER COLUMN user_id SET NOT NULL,
    ADD CONSTRAINT collection_item_collection_id_user_id_fkey
        FOREIGN KEY (collection_id, user_id) REFERENCES collection (id, user_id) ON DELETE CASCADE,
    ADD CONSTRAINT collection_item_vault_item_id_user_id_fkey
        FOREIGN KEY (vault_item_id, user_id) REFERENCES vault_item (id, user_id) ON DELETE CASCADE;

ALTER TABLE avatar_profile
    ADD CONSTRAINT avatar_profile_id_user_id_key UNIQUE (id, user_id);

ALTER TABLE studio_session
    ADD CONSTRAINT studio_session_id_user_id_key UNIQUE (id, user_id);

ALTER TABLE look
    ADD CONSTRAINT look_avatar_profile_id_user_id_fkey
        FOREIGN KEY (avatar_profile_id, user_id) REFERENCES avatar_profile (id, user_id),
    ADD CONSTRAINT look_studio_session_id_user_id_fkey
        FOREIGN KEY (studio_session_id, user_id) REFERENCES studio_session (id, user_id);

ALTER TABLE generation_provider_performance_daily
    ADD CONSTRAINT generation_provider_performance_daily_nonnegative_counts_check CHECK (
        sample_count >= 0
        AND technical_success_count >= 0
        AND quality_evaluated_count >= 0
        AND quality_approved_count >= 0
        AND fidelity_sample_count >= 0
        AND latency_sample_count >= 0
        AND known_cost_count >= 0
    ),
    ADD CONSTRAINT generation_provider_performance_daily_quality_approved_tech_check CHECK (
        quality_approved_count <= technical_success_count
    ),
    ADD CONSTRAINT generation_provider_performance_daily_fidelity_sum_bound_check CHECK (
        fidelity_axis_sum <= 3 * fidelity_sample_count
    ),
    ADD CONSTRAINT generation_provider_performance_daily_known_cost_nonnegative_check CHECK (
        known_cost_total IS NULL
        OR (
            known_cost_total >= 0
            AND known_cost_total <> 'NaN'::numeric
            AND known_cost_total <> 'Infinity'::numeric
            AND known_cost_total <> '-Infinity'::numeric
        )
    ),
    ADD CONSTRAINT generation_provider_performance_daily_known_cost_coherence_check CHECK (
        (known_cost_count = 0 AND known_cost_total IS NULL)
        OR (known_cost_count > 0 AND known_cost_total IS NOT NULL)
    );
