CREATE TABLE generation_provider_performance_daily (
    provider_key VARCHAR(64) NOT NULL,
    model_or_mode VARCHAR(128) NOT NULL,
    category VARCHAR(64) NOT NULL,
    bucket_day DATE NOT NULL,
    currency_key VARCHAR(3) NOT NULL,
    sample_count BIGINT NOT NULL,
    technical_success_count BIGINT NOT NULL,
    quality_evaluated_count BIGINT NOT NULL,
    quality_approved_count BIGINT NOT NULL,
    fidelity_axis_sum NUMERIC NOT NULL DEFAULT 0,
    fidelity_sample_count BIGINT NOT NULL DEFAULT 0,
    latency_sum_ms BIGINT NOT NULL DEFAULT 0,
    latency_sample_count BIGINT NOT NULL DEFAULT 0,
    known_cost_total NUMERIC,
    known_cost_count BIGINT NOT NULL DEFAULT 0,
    last_recomputed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (provider_key, model_or_mode, category, bucket_day, currency_key),
    CONSTRAINT generation_provider_performance_daily_counts_check CHECK (
        technical_success_count <= sample_count
        AND quality_evaluated_count <= sample_count
        AND quality_approved_count <= quality_evaluated_count
        AND fidelity_sample_count <= quality_approved_count
        AND latency_sample_count <= quality_approved_count
        AND known_cost_count <= sample_count
    ),
    CONSTRAINT generation_provider_performance_daily_sums_check CHECK (
        fidelity_axis_sum >= 0 AND latency_sum_ms >= 0 AND known_cost_count >= 0
    ),
    CONSTRAINT generation_provider_performance_daily_fidelity_null_check CHECK (
        (fidelity_sample_count = 0 AND fidelity_axis_sum = 0) OR fidelity_sample_count > 0
    ),
    CONSTRAINT generation_provider_performance_daily_cost_null_check CHECK (
        (known_cost_count = 0 AND known_cost_total IS NULL) OR known_cost_count > 0
    )
);
CREATE INDEX generation_provider_performance_daily_day_idx
    ON generation_provider_performance_daily (bucket_day);
CREATE INDEX generation_provider_performance_daily_provider_category_idx
    ON generation_provider_performance_daily (provider_key, category);
