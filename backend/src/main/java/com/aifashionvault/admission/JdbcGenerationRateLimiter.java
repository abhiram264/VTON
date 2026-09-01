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

	@Override public Reservation reserve(GenerationAdmissionRequest request) {
		Objects.requireNonNull(request, "request");
		LocalDate day = clock.instant().atZone(java.time.ZoneOffset.UTC).toLocalDate();
		int limit = request.riskTier().dailyLimit();
		if (request.deviceId() == null || request.deviceId().isBlank())
			return new Reservation(GenerationAdmissionDecision.rejected("DEVICE_DAILY_LIMIT", 0, limit, day), null);
		if (request.ipAddress() == null || request.ipAddress().isBlank())
			return new Reservation(GenerationAdmissionDecision.rejected("IP_DAILY_LIMIT", 0, limit, day), null);
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
				return new Reservation(GenerationAdmissionDecision.rejected(request.riskTier() == GenerationRiskTier.ORDINARY ? reasons[i] : "RISK_TIER_LIMIT", counts.get(i).intValue(), limit, day), null);
			String token = UUID.randomUUID().toString();
			for (String key : keys) {
				jdbc.update("UPDATE generation_admission_counter SET count = count + 1 WHERE bucket_key = ? AND bucket_day = ?", key, Date.valueOf(day));
				jdbc.update("INSERT INTO generation_admission_reservation (token, bucket_key, bucket_day) VALUES (?, ?, ?)", token, key, Date.valueOf(day));
			}
			return new Reservation(GenerationAdmissionDecision.accepted(counts.getFirst().intValue() + 1, limit, day), token);
		});
	}

	@Override public void commit(Reservation reservation) {
		requireAccepted(reservation);
		transactions.executeWithoutResult(status -> {
			int changed = jdbc.update("UPDATE generation_admission_reservation SET committed_at = CURRENT_TIMESTAMP WHERE token = ? AND committed_at IS NULL AND released_at IS NULL", reservation.token());
			if (changed != 3 && changed != 0) throw new IllegalStateException("incomplete admission reservation");
		});
	}

	@Override public void release(Reservation reservation) {
		requireAccepted(reservation);
		transactions.executeWithoutResult(status -> {
			List<java.util.Map<String, Object>> rows = jdbc.queryForList("SELECT bucket_key, bucket_day FROM generation_admission_reservation WHERE token = ? AND committed_at IS NULL AND released_at IS NULL FOR UPDATE", reservation.token());
			if (rows.isEmpty()) return;
			if (rows.size() != 3) throw new IllegalStateException("incomplete admission reservation");
			for (var row : rows) jdbc.update("UPDATE generation_admission_counter SET count = count - 1 WHERE bucket_key = ? AND bucket_day = ? AND count > 0", row.get("bucket_key"), row.get("bucket_day"));
			jdbc.update("UPDATE generation_admission_reservation SET released_at = CURRENT_TIMESTAMP WHERE token = ? AND committed_at IS NULL AND released_at IS NULL", reservation.token());
		});
	}

	private static void requireAccepted(Reservation reservation) {
		if (reservation == null || !reservation.decision().allowed()) throw new IllegalArgumentException("accepted reservation is required");
	}
}
