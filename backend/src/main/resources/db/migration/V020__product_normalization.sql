ALTER TABLE vault_item
    ADD CONSTRAINT vault_item_id_user_id_key UNIQUE (id, user_id);

CREATE TABLE product_correction_event (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL,
    vault_item_id uuid NOT NULL,
    predicted_category text NOT NULL,
    corrected_category text NOT NULL,
    confidence double precision NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT product_correction_event_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT product_correction_event_vault_item_owner_fkey
        FOREIGN KEY (vault_item_id, user_id) REFERENCES vault_item (id, user_id) ON DELETE CASCADE,
    CONSTRAINT product_correction_event_predicted_category_check CHECK (
        predicted_category IN ('TOP', 'BOTTOM', 'ONE_PIECE', 'ETHNIC_FULL_BODY', 'SAREE', 'LEHENGA',
            'KURTA', 'SHERWANI', 'FOOTWEAR', 'HEADWEAR', 'BAG', 'JEWELLERY', 'ACCESSORY', 'UNKNOWN')
    ),
    CONSTRAINT product_correction_event_corrected_category_check CHECK (
        corrected_category IN ('TOP', 'BOTTOM', 'ONE_PIECE', 'ETHNIC_FULL_BODY', 'SAREE', 'LEHENGA',
            'KURTA', 'SHERWANI', 'FOOTWEAR', 'HEADWEAR', 'BAG', 'JEWELLERY', 'ACCESSORY')
    ),
    CONSTRAINT product_correction_event_distinct_categories_check
        CHECK (corrected_category <> predicted_category),
    CONSTRAINT product_correction_event_confidence_check
		CHECK (confidence <> 'NaN'::double precision AND confidence >= 0.0 AND confidence < 0.70)
);

CREATE INDEX product_correction_event_user_id_created_at_idx
    ON product_correction_event (user_id, created_at);
