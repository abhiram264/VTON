CREATE TABLE generation_admission_counter (
    id UUID PRIMARY KEY,
    bucket_key VARCHAR(300) NOT NULL,
    bucket_day DATE NOT NULL,
    count BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT generation_admission_counter_bucket_unique UNIQUE (bucket_key, bucket_day),
    CONSTRAINT generation_admission_counter_count_nonnegative CHECK (count >= 0)
);
CREATE INDEX generation_admission_counter_day_idx ON generation_admission_counter (bucket_day);
