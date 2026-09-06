package com.aifashionvault.generation.vton;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JDBC implementation of {@link ProviderPerformanceStore} over the V023
 * {@code generation_provider_performance_daily} rollup (slice-73 D1/D2/D6).
 *
 * <p>The daily table is a derived view: {@link #recompute} replaces whole UTC
 * days in one transaction from the append-only {@code generation_attempt} /
 * {@code quality_result} facts — there is no write-path hook, so the rollup
 * can never diverge from its sources within a recomputed window, and running
 * it twice (or from concurrent instances) writes identical content. Buckets
 * persist sums and counts only; means and rates are derived at read time with
 * {@link MathContext#DECIMAL128} division. Mean latency is a deliberate,
 * documented deviation from {@code BenchmarkMatrixRow}'s P50: exact
 * percentiles are not mergeable across daily buckets, so a mean keeps
 * multi-day window aggregation exact (D2).</p>
 */
public final class JdbcProviderPerformanceStore implements ProviderPerformanceStore {

	static final String NO_CURRENCY_KEY = "XXX";
	static final String UNATTRIBUTED_CATEGORY = "unattributed";
	static final String UNKNOWN_MODEL = "default";

	private static final MathContext DIVISION = MathContext.DECIMAL128;
	private static final BigDecimal FIDELITY_AXES = BigDecimal.valueOf(3);

	/**
	 * Deterministic category provenance (D3): the render step's first item
	 * category, else the job product's smallest vault item category, else the
	 * explicit 'unattributed' sentinel. Correlated scalar subqueries only —
	 * generation_job_product holds several rows per job, so a LEFT JOIN would
	 * multiply samples.
	 */
	private static final String CATEGORY_EXPRESSION = ""
		+ "COALESCE(\n"
		+ "\t(SELECT i.category\n"
		+ "\t\tFROM generation_render_step_item i\n"
		+ "\t\tWHERE i.render_step_id = a.render_step_id\n"
		+ "\t\tORDER BY i.item_ordinal\n"
		+ "\t\tLIMIT 1),\n"
		+ "\t(SELECT v.category\n"
		+ "\t\tFROM generation_job_product p\n"
		+ "\t\tJOIN vault_item v ON v.id = p.vault_item_id\n"
		+ "\t\tWHERE p.generation_job_id = a.generation_job_id\n"
		+ "\t\tORDER BY p.vault_item_id\n"
		+ "\t\tLIMIT 1),\n"
		+ "\t'" + UNATTRIBUTED_CATEGORY + "'\n"
		+ ")";

	/**
	 * Known cost per attempt (D5): actual when FINAL, else estimated when
	 * ESTIMATED — only when the amount and the currency are both known.
	 * UNKNOWN/null cost contributes no money.
	 */
	private static final String KNOWN_COST_EXPRESSION = ""
		+ "CASE\n"
		+ "\tWHEN a.cost_status = 'FINAL' AND a.actual_cost IS NOT NULL AND a.currency IS NOT NULL\n"
		+ "\t\tTHEN a.actual_cost\n"
		+ "\tWHEN a.cost_status = 'ESTIMATED' AND a.estimated_cost IS NOT NULL AND a.currency IS NOT NULL\n"
		+ "\t\tTHEN a.estimated_cost\n"
		+ "\tELSE NULL\n"
		+ "END";

	private static final String RECOMPUTE_INSERT_SQL = buildRecomputeInsertSql();
	private static final String SNAPSHOT_SQL = buildSnapshotSql();
	private static final Comparator<ProviderPerformanceRow> ROW_ORDER = Comparator
		.comparing(ProviderPerformanceRow::providerKey)
		.thenComparing(ProviderPerformanceRow::category)
		.thenComparing(ProviderPerformanceRow::modelOrMode)
		.thenComparing(ProviderPerformanceRow::currency, Comparator.nullsFirst(Comparator.naturalOrder()));

	private final JdbcTemplate jdbc;
	private final TransactionTemplate transactions;
	private final Clock clock;

	public JdbcProviderPerformanceStore(DataSource dataSource, Clock clock) {
		if (dataSource == null) {
			throw new IllegalArgumentException("dataSource is required");
		}
		if (clock == null) {
			throw new IllegalArgumentException("clock is required");
		}
		this.jdbc = new JdbcTemplate(dataSource);
		this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
		this.clock = clock;
	}

	@Override
	public ProviderPerformanceSnapshot snapshot(ProviderPerformanceQuery query) {
		if (query == null) {
			throw new IllegalArgumentException("query is required");
		}
		StringBuilder sql = new StringBuilder(SNAPSHOT_SQL);
		List<Object> args = new ArrayList<>();
		// Same half-open UTC day expansion as recompute (D6): the window covers
		// to's day only when to has a time-of-day.
		LocalDate firstDay = LocalDate.ofInstant(query.windowStart(), ZoneOffset.UTC);
		LocalDate lastDay = LocalDate.ofInstant(query.windowEnd(), ZoneOffset.UTC);
		if (query.windowEnd().equals(lastDay.atStartOfDay().toInstant(ZoneOffset.UTC))) {
			lastDay = lastDay.minusDays(1);
		}
		args.add(Date.valueOf(firstDay));
		args.add(Date.valueOf(lastDay));
		if (query.providerKey() != null) {
			sql.append("\nAND provider_key = ?");
			args.add(query.providerKey());
		}
		if (query.category() != null) {
			sql.append("\nAND category = ?");
			args.add(query.category());
		}
		if (query.currency() != null) {
			sql.append("\nAND currency_key = ?");
			args.add(query.currency());
		}
		sql.append("\nGROUP BY provider_key, model_or_mode, category, currency_key");
		List<ProviderPerformanceRow> rows = jdbc.query(sql.toString(), this::mapRow, args.toArray());
		rows.sort(ROW_ORDER);
		return new ProviderPerformanceSnapshot(rows, clock.instant());
	}

	@Override
	public void recompute(Instant fromInclusive, Instant toExclusive) {
		if (fromInclusive == null) {
			throw new IllegalArgumentException("fromInclusive is required");
		}
		if (toExclusive == null) {
			throw new IllegalArgumentException("toExclusive is required");
		}
		if (!fromInclusive.isBefore(toExclusive)) {
			throw new IllegalArgumentException("fromInclusive must be before toExclusive");
		}
		LocalDate firstDay = LocalDate.ofInstant(fromInclusive, ZoneOffset.UTC);
		// [from, to) covers to's UTC day only when to has a time-of-day (D6).
		LocalDate dayOfTo = LocalDate.ofInstant(toExclusive, ZoneOffset.UTC);
		LocalDate lastDay = toExclusive.equals(dayOfTo.atStartOfDay().toInstant(ZoneOffset.UTC))
			? dayOfTo.minusDays(1)
			: dayOfTo;
		Instant runAt = clock.instant();
		transactions.executeWithoutResult(status -> replaceDays(firstDay, lastDay, runAt));
	}

	private void replaceDays(LocalDate firstDay, LocalDate lastDay, Instant runAt) {
		jdbc.update(
			"DELETE FROM generation_provider_performance_daily WHERE bucket_day >= ? AND bucket_day <= ?",
			Date.valueOf(firstDay), Date.valueOf(lastDay));
		jdbc.update(RECOMPUTE_INSERT_SQL, ps -> {
			ps.setTimestamp(1, Timestamp.from(runAt));
			ps.setDate(2, Date.valueOf(firstDay));
			ps.setDate(3, Date.valueOf(lastDay));
		});
	}

	private ProviderPerformanceRow mapRow(ResultSet rs, int rowNum) throws SQLException {
		long sampleCount = rs.getLong("sample_count");
		long technicalSuccessCount = rs.getLong("technical_success_count");
		long qualityEvaluatedCount = rs.getLong("quality_evaluated_count");
		long qualityApprovedCount = rs.getLong("quality_approved_count");
		long latencySampleCount = rs.getLong("latency_sample_count");
		long knownCostCount = rs.getLong("known_cost_count");
		BigDecimal fidelityAxisSum = rs.getBigDecimal("fidelity_axis_sum");
		long fidelitySampleCount = rs.getLong("fidelity_sample_count");
		long latencySumMs = rs.getLong("latency_sum_ms");
		BigDecimal knownCostTotal = rs.getBigDecimal("known_cost_total");
		String currencyKey = rs.getString("currency_key");
		return new ProviderPerformanceRow(
			rs.getString("provider_key"),
			rs.getString("model_or_mode"),
			rs.getString("category"),
			ratio(fidelityAxisSum, FIDELITY_AXES.multiply(BigDecimal.valueOf(fidelitySampleCount), DIVISION)),
			ratio(BigDecimal.valueOf(qualityApprovedCount), qualityEvaluatedCount),
			meanLatencyMs(latencySumMs, latencySampleCount),
			ratio(BigDecimal.valueOf(sampleCount - technicalSuccessCount), sampleCount),
			knownCostTotal == null ? null : ratio(knownCostTotal, qualityApprovedCount),
			NO_CURRENCY_KEY.equals(currencyKey) ? null : currencyKey,
			sampleCount,
			technicalSuccessCount,
			qualityApprovedCount);
	}

	/** Null when the denominator is zero; DECIMAL128 division otherwise (D2). */
	private static BigDecimal ratio(BigDecimal numerator, long denominator) {
		return denominator == 0 ? null
			: numerator.divide(BigDecimal.valueOf(denominator), DIVISION);
	}

	private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
		return denominator.signum() == 0 ? null : numerator.divide(denominator, DIVISION);
	}

	private static Long meanLatencyMs(long latencySumMs, long latencySampleCount) {
		return latencySampleCount == 0
			? null
			: BigDecimal.valueOf(latencySumMs)
				.divide(BigDecimal.valueOf(latencySampleCount), DIVISION)
				.setScale(0, RoundingMode.FLOOR)
				.longValueExact();
	}

	private static String buildRecomputeInsertSql() {
		return """
			INSERT INTO generation_provider_performance_daily (
				provider_key, model_or_mode, category, bucket_day, currency_key,
				sample_count, technical_success_count, quality_evaluated_count, quality_approved_count,
				fidelity_axis_sum, fidelity_sample_count, latency_sum_ms, latency_sample_count,
				known_cost_total, known_cost_count, last_recomputed_at
			)
			SELECT
				a.provider,
				COALESCE(NULLIF(a.model, ''), '%s'),
				%s,
				(a.completed_at AT TIME ZONE 'UTC')::DATE,
				COALESCE(CASE WHEN %s IS NOT NULL THEN a.currency END, '%s'),
				COUNT(*),
				COUNT(*) FILTER (WHERE a.technical_outcome = 'SUCCESS'),
				COUNT(q.id),
				COUNT(*) FILTER (WHERE q.decision = 'PASS'),
				COALESCE(SUM(q.identity_preservation + q.garment_fidelity + q.color_fidelity)
					FILTER (WHERE q.decision = 'PASS'
						AND q.identity_preservation IS NOT NULL
						AND q.garment_fidelity IS NOT NULL
						AND q.color_fidelity IS NOT NULL), 0),
				COUNT(*) FILTER (WHERE q.decision = 'PASS'
					AND q.identity_preservation IS NOT NULL
					AND q.garment_fidelity IS NOT NULL
					AND q.color_fidelity IS NOT NULL),
				COALESCE(SUM(a.latency_ms) FILTER (WHERE q.decision = 'PASS' AND a.latency_ms IS NOT NULL), 0),
				COUNT(*) FILTER (WHERE q.decision = 'PASS' AND a.latency_ms IS NOT NULL),
				SUM(%s),
				COUNT(%s),
				?
			FROM generation_attempt a
			LEFT JOIN quality_result q ON q.generation_attempt_id = a.id
			WHERE a.completed_at IS NOT NULL
				AND (a.completed_at AT TIME ZONE 'UTC')::DATE >= ?
				AND (a.completed_at AT TIME ZONE 'UTC')::DATE <= ?
			GROUP BY a.provider, COALESCE(NULLIF(a.model, ''), '%s'), %s, (a.completed_at AT TIME ZONE 'UTC')::DATE,
				COALESCE(CASE WHEN %s IS NOT NULL THEN a.currency END, '%s')
			""".formatted(
			UNKNOWN_MODEL,
			CATEGORY_EXPRESSION,
			KNOWN_COST_EXPRESSION, NO_CURRENCY_KEY,
			KNOWN_COST_EXPRESSION,
			KNOWN_COST_EXPRESSION,
			UNKNOWN_MODEL,
			CATEGORY_EXPRESSION,
			KNOWN_COST_EXPRESSION, NO_CURRENCY_KEY);
	}

	private static String buildSnapshotSql() {
		return """
			SELECT
				provider_key,
				model_or_mode,
				category,
				currency_key,
				SUM(sample_count) AS sample_count,
				SUM(technical_success_count) AS technical_success_count,
				SUM(quality_evaluated_count) AS quality_evaluated_count,
				SUM(quality_approved_count) AS quality_approved_count,
				SUM(fidelity_axis_sum) AS fidelity_axis_sum,
				SUM(fidelity_sample_count) AS fidelity_sample_count,
				SUM(latency_sum_ms) AS latency_sum_ms,
				SUM(latency_sample_count) AS latency_sample_count,
				SUM(known_cost_total) AS known_cost_total,
				SUM(known_cost_count) AS known_cost_count
			FROM generation_provider_performance_daily
			WHERE bucket_day >= ?
				AND bucket_day <= ?""";
	}
}
