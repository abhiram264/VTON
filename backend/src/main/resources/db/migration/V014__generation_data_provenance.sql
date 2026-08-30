ALTER TABLE generation_attempt
    ADD COLUMN actual_billed_units numeric NULL,
    ADD COLUMN pricing_observed_at timestamptz NULL,
    ADD COLUMN billing_unit text NULL;

ALTER TABLE quality_result
    ADD COLUMN quality_policy_version text NULL,
    ADD COLUMN reason_code text NULL;

UPDATE generation_attempt
SET actual_billed_units = credits_used
WHERE credits_used IS NOT NULL;
