package com.aifashionvault.admission;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcGenerationRateLimiter implements GenerationRateLimiter {
	private final JdbcTemplate jdbc;
	private final TransactionTemplate transactions;
	private final Clock clock;

	public JdbcGenerationRateLimiter(DataSource source, Clock clock) {
		this.jdbc = new JdbcTemplate(Objects.requireNonNull(source, "dataSource"));
		this.transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
		this.clock = Objects.requireNonNull(clock, "clock");
	}

    @Override public GenerationAdmissionDecision admit(GenerationAdmissionRequest request) {
        Objects.requireNonNull(request, "request");
        LocalDate day = clock.instant().atZone(java.time.ZoneOffset.UTC).toLocalDate();
        int limit = request.riskTier().dailyLimit();
        if (request.deviceId() == null || request.deviceId().isBlank())
            return GenerationAdmissionDecision.rejected("DEVICE_DAILY_LIMIT", 0, limit, day);
        if (request.ipAddress() == null || request.ipAddress().isBlank())
            return GenerationAdmissionDecision.rejected("IP_DAILY_LIMIT", 0, limit, day);
        return transactions.execute(status -> {
            List<String> keys = List.of("user:" + request.userId().value(), "device:" + request.deviceId().strip(), "ip:" + request.ipAddress().strip());
            List<Long> counts = new ArrayList<>();
            for (String key : keys) {
                jdbc.update("INSERT INTO generation_admission_counter (id, bucket_key, bucket_day, count) VALUES (?, ?, ?, 0) ON CONFLICT (bucket_key, bucket_day) DO NOTHING", UUID.randomUUID(), key, Date.valueOf(day));
                Long count = jdbc.queryForObject("SELECT count FROM generation_admission_counter WHERE bucket_key = ? AND bucket_day = ? FOR UPDATE", Long.class, key, Date.valueOf(day));
                counts.add(count == null ? 0L : count);
            }
            String[] reasons = {"USER_DAILY_LIMIT", "DEVICE_DAILY_LIMIT", "IP_DAILY_LIMIT"};
            for (int i = 0; i < counts.size(); i++) if (counts.get(i) >= limit)
                return GenerationAdmissionDecision.rejected(request.riskTier() == GenerationRiskTier.ORDINARY ? reasons[i] : "RISK_TIER_LIMIT", counts.get(i).intValue(), limit, day);
            for (String key : keys) jdbc.update("UPDATE generation_admission_counter SET count = count + 1 WHERE bucket_key = ? AND bucket_day = ?", key, Date.valueOf(day));
            return GenerationAdmissionDecision.accepted(counts.getFirst().intValue() + 1, limit, day);
        });
    }
}
