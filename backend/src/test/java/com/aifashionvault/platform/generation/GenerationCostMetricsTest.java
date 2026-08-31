package com.aifashionvault.platform.generation;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifashionvault.generation.CostAggregate;
import com.aifashionvault.generation.CostQuery;
import com.aifashionvault.generation.GenerationCostFact;
import com.aifashionvault.generation.PricingSnapshot;
import com.aifashionvault.shared.GenerationAttemptId;
import com.aifashionvault.shared.GenerationJobId;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenerationCostMetricsTest {
	private static final Instant NOW = Instant.parse("2026-08-31T12:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
	private static final CostQuery ALL = new CostQuery(null, null, null, NOW.minusSeconds(1), NOW.plusSeconds(1));

	@Test
	void recordsFinalCostAndExplicitDenominators() {
		GenerationMetrics metrics = metrics();
		metrics.recordAttempt(fact("provider-a", "route-a", "SUCCESS", "PASS", "2.50", PricingSnapshot.COST_FINAL, true, true, true));
		CostAggregate a = metrics.aggregate(ALL);
		assertThat(a.totalCost()).isEqualByComparingTo("2.50");
		assertThat(a.costPerRequest()).isEqualByComparingTo("2.50");
		assertThat(a.costPerProviderSuccess()).isEqualByComparingTo("2.50");
		assertThat(a.costPerQualityApprovedLook()).isEqualByComparingTo("2.50");
		assertThat(a.costPerActiveUser()).isEqualByComparingTo("2.50");
		assertThat(a.costPerRetainedUser()).isEqualByComparingTo("2.50");
		assertThat(a.requestCount()).isEqualTo(1);
		assertThat(a.providerSuccessCount()).isEqualTo(1);
		assertThat(a.qualityApprovedLookCount()).isEqualTo(1);
		assertThat(a.activeUserCount()).isEqualTo(1);
		assertThat(a.retainedUserCount()).isEqualTo(1);
		assertThat(a.knownCostCount()).isEqualTo(1);
		assertThat(a.currency()).isEqualTo("USD");
	}

	@Test
	void failuresAndQualityRejectsRemainSpendButSeparateDenominators() {
		GenerationMetrics metrics = metrics();
		metrics.recordAttempt(fact("provider-a", "route-a", "FAILED", null, "1.00", PricingSnapshot.COST_FINAL, false, false, false));
		metrics.recordAttempt(fact("provider-a", "route-a", "SUCCESS", "FAIL", "2.00", PricingSnapshot.COST_FINAL, false, true, false));
		CostAggregate a = metrics.aggregate(ALL);
		assertThat(a.totalCost()).isEqualByComparingTo("3.00");
		assertThat(a.requestCount()).isEqualTo(2);
		assertThat(a.providerSuccessCount()).isEqualTo(1);
		assertThat(a.qualityApprovedLookCount()).isZero();
		assertThat(a.costPerProviderSuccess()).isEqualByComparingTo("3.00");
		assertThat(a.costPerQualityApprovedLook()).isNull();
	}

	@Test
	void unknownIsNotZeroAndEstimatedReconcilesIdempotently() {
		GenerationMetrics metrics = metrics();
		metrics.recordAttempt(fact("provider-a", "route-a", "SUCCESS", "PASS", null, PricingSnapshot.COST_UNKNOWN, true, false, false));
		assertThat(metrics.aggregate(ALL).totalCost()).isNull();
		assertThat(metrics.aggregate(ALL).knownCostCount()).isZero();
		GenerationMetrics reconciler = metrics();
		GenerationCostFact estimated = fact("provider-a", "route-a", "SUCCESS", "PASS", "4.00", PricingSnapshot.COST_ESTIMATED, true, false, false);
		reconciler.recordAttempt(estimated);
		GenerationCostFact settled = replacement(estimated, "3.25", PricingSnapshot.COST_FINAL);
		reconciler.recordAttempt(settled);
		reconciler.recordAttempt(settled);
		CostAggregate a = reconciler.aggregate(ALL);
		assertThat(a.totalCost()).isEqualByComparingTo("3.25");
		assertThat(a.requestCount()).isEqualTo(1);
		assertThat(a.knownCostCount()).isEqualTo(1);
		assertThatThrownBy(() -> reconciler.recordAttempt(replacement(estimated, "3.50", PricingSnapshot.COST_FINAL)))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void zeroDenominatorsAndMultipleCurrenciesAreUndefined() {
		GenerationMetrics metrics = metrics();
		metrics.recordAttempt(fact("provider-a", "route-a", "FAILED", null, "1.00", PricingSnapshot.COST_FINAL, false, false, false));
		assertThat(metrics.aggregate(ALL).costPerProviderSuccess()).isNull();
		assertThat(metrics.aggregate(ALL).costPerQualityApprovedLook()).isNull();
		assertThat(metrics.aggregate(ALL).costPerActiveUser()).isNull();
		metrics.recordAttempt(fact("provider-b", "route-b", "SUCCESS", "PASS", "2.00", PricingSnapshot.COST_FINAL, true, true, true, "EUR"));
		CostAggregate mixed = metrics.aggregate(ALL);
		assertThat(mixed.totalCost()).isNull();
		assertThat(mixed.currency()).isNull();
		assertThat(mixed.costPerRequest()).isNull();
	}

	@Test
	void providerAndModelFiltersIsolateRoutes() {
		GenerationMetrics metrics = metrics();
		metrics.recordAttempt(fact("provider-a", "opaque-route-a", "SUCCESS", "PASS", "1.00", PricingSnapshot.COST_FINAL, true, false, false));
		metrics.recordAttempt(fact("provider-b", "opaque-route-b", "SUCCESS", "PASS", "9.00", PricingSnapshot.COST_FINAL, true, false, false));
		assertThat(metrics.aggregate(new CostQuery("provider-a", null, "USD", NOW.minusSeconds(1), NOW.plusSeconds(1))).totalCost()).isEqualByComparingTo("1.00");
		assertThat(metrics.aggregate(new CostQuery(null, "opaque-route-b", "USD", NOW.minusSeconds(1), NOW.plusSeconds(1))).totalCost()).isEqualByComparingTo("9.00");
	}

	@Test
	void costMetersHaveBoundedPrivacySafeTags() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		new GenerationMetrics(registry, CLOCK).recordAttempt(fact("provider-a", "opaque-route-a", "SUCCESS", "PASS", "1.00", PricingSnapshot.COST_FINAL, true, true, true));
		assertThat(registry.find(GenerationMetrics.COST_ATTEMPT).tag("provider", "provider-a").tag("model", "opaque-route-a")
			.tag("technical_outcome", "SUCCESS").tag("quality_outcome", "PASS").tag("cost_status", PricingSnapshot.COST_FINAL).counter()).isNotNull();
		assertThat(registry.getMeters().stream().flatMap(m -> m.getId().getTags().stream()).map(Tag::getKey))
			.doesNotContain("user", "user_id", "job", "generation", "attempt", "url", "body", "media", "secret");
	}

	private static GenerationMetrics metrics() { return new GenerationMetrics(new SimpleMeterRegistry(), CLOCK); }
	private static GenerationCostFact fact(String p, String m, String t, String q, String c, String s, boolean approved, boolean active, boolean retained) { return fact(p, m, t, q, c, s, approved, active, retained, "USD"); }
	private static GenerationCostFact fact(String p, String m, String t, String q, String c, String s, boolean approved, boolean active, boolean retained, String currency) {
		return new GenerationCostFact(new GenerationJobId(UUID.randomUUID()), new GenerationAttemptId(UUID.randomUUID()), p, m, t, q, BigDecimal.ONE, BigDecimal.ONE, c == null ? null : new BigDecimal(c), currency, s, approved, active, retained);
	}
	private static GenerationCostFact replacement(GenerationCostFact f, String cost, String status) {
		return new GenerationCostFact(f.generationId(), f.attemptId(), f.providerKey(), f.modelRouteAuditIdentity(), f.technicalOutcome(), f.qualityOutcome(), f.billedCredits(), f.billedUnits(), new BigDecimal(cost), f.currency(), status, f.qualityApprovedLook(), f.activeUser(), f.retainedUser());
	}
}
