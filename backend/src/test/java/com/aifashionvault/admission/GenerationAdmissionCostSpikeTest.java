package com.aifashionvault.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.aifashionvault.generation.CostAggregate;
import com.aifashionvault.generation.CostQuery;
import com.aifashionvault.generation.GenerationCostFact;
import com.aifashionvault.generation.GenerationCostMetrics;

class GenerationAdmissionCostSpikeTest {
	private static final Instant NOW = Instant.parse("2026-09-05T12:34:56Z");
	private static final String PROVIDER = "provider";

	@Test
	void globalWindowExceedingThreeTimesTheBaselineMeanIsSuspected() {
		// Baseline 240 over 24 prior hours (mean 10/hour); current 31: 31 x 24 = 744 > 240 x 3.0 = 720.
		var metrics = new StubMetrics();
		metrics.put(WINDOW.GLOBAL_BASELINE, window("240", 240, "USD"));
		metrics.put(WINDOW.GLOBAL_CURRENT, window("31", 6, "USD"));
		CostSpikeDetector detector = new AggregateBackedCostSpikeDetector(metrics, CostSpikePolicy.DEFAULT);

		var assessment = detector.assess(new CostSpikeQuery(PROVIDER, "USD", NOW));

		assertThat(assessment.suspected()).isTrue();
		assertThat(assessment.dimension()).isEqualTo(SoftBreachDimension.GLOBAL);
		assertThat(assessment.currentWindowSpend()).isEqualByComparingTo(new BigDecimal("31"));
		assertThat(assessment.baselineMeanWindowSpend()).isEqualByComparingTo(new BigDecimal("10"));
		assertThat(assessment.currentWindowExecutions()).isEqualTo(6);
		assertThat(assessment.observationWindowStart()).isEqualTo(NOW.minus(Duration.ofHours(1)));
		assertThat(assessment.observationWindowEnd()).isEqualTo(NOW);
	}

	@Test
	void spendAtExactlyTheBaselineTimesFactorIsNotSuspected() {
		// 31.25 x 24 = 750.0 = 250 x 3.0 exactly: strict inequality means equality is not a spike.
		var metrics = new StubMetrics();
		metrics.put(WINDOW.GLOBAL_BASELINE, window("250", 250, "USD"));
		metrics.put(WINDOW.GLOBAL_CURRENT, window("31.25", 10, "USD"));

		var assessment = new AggregateBackedCostSpikeDetector(metrics, CostSpikePolicy.DEFAULT)
			.assess(new CostSpikeQuery(PROVIDER, "USD", NOW));

		assertThat(assessment.suspected()).isFalse();
		assertThat(assessment.dimension()).isNull();
	}

	@Test
	void providerWindowSpikeIsSuspectedWhenGlobalWindowsStayNormal() {
		var metrics = new StubMetrics();
		metrics.put(WINDOW.GLOBAL_BASELINE, window("240", 240, "USD"));
		metrics.put(WINDOW.GLOBAL_CURRENT, window("25", 5, "USD")); // 25 x 24 = 600 <= 720
		metrics.put(WINDOW.PROVIDER_BASELINE, window("50", 50, "USD"));
		metrics.put(WINDOW.PROVIDER_CURRENT, window("51", 5, "USD")); // 51 x 24 = 1224 > 50 x 3.0 = 150

		var assessment = new AggregateBackedCostSpikeDetector(metrics, CostSpikePolicy.DEFAULT)
			.assess(new CostSpikeQuery(PROVIDER, "USD", NOW));

		assertThat(assessment.suspected()).isTrue();
		assertThat(assessment.dimension()).isEqualTo(SoftBreachDimension.PROVIDER);
		assertThat(assessment.currentWindowSpend()).isEqualByComparingTo(new BigDecimal("51"));
		assertThat(assessment.baselineMeanWindowSpend())
			.isEqualByComparingTo(new BigDecimal("50").divide(new BigDecimal("24"), 6, RoundingMode.HALF_UP));
		assertThat(assessment.currentWindowExecutions()).isEqualTo(5);
		assertThat(metrics.queries()).hasSize(4);
		assertThat(metrics.queries().get(2).providerKey()).isEqualTo(PROVIDER);
		assertThat(metrics.queries().get(3).providerKey()).isEqualTo(PROVIDER);
	}

	@Test
	void zeroBaselineSpendIsNeverASpike() {
		var metrics = new StubMetrics();
		metrics.put(WINDOW.GLOBAL_BASELINE, window("0", 0, "USD"));
		metrics.put(WINDOW.GLOBAL_CURRENT, window("100", 50, "USD"));
		metrics.put(WINDOW.PROVIDER_BASELINE, window("0", 0, "USD"));
		metrics.put(WINDOW.PROVIDER_CURRENT, window("100", 50, "USD"));

		var assessment = new AggregateBackedCostSpikeDetector(metrics, CostSpikePolicy.DEFAULT)
			.assess(new CostSpikeQuery(PROVIDER, "USD", NOW));

		assertThat(assessment.suspected()).isFalse();
		assertThat(assessment.dimension()).isNull();
	}

	@Test
	void windowsBelowTheMinimumExecutionCountAreNotSuspected() {
		// 31 x 24 = 744 > 720 exceeds the factor, but 4 executions stay below the minimum of 5.
		var metrics = new StubMetrics();
		metrics.put(WINDOW.GLOBAL_BASELINE, window("240", 240, "USD"));
		metrics.put(WINDOW.GLOBAL_CURRENT, window("31", 4, "USD"));

		var assessment = new AggregateBackedCostSpikeDetector(metrics, CostSpikePolicy.DEFAULT)
			.assess(new CostSpikeQuery(PROVIDER, "USD", NOW));

		assertThat(assessment.suspected()).isFalse();
		assertThat(assessment.dimension()).isNull();
	}

	@Test
	void windowsDerivePurelyFromTheQueryInstant() {
		var metrics = new StubMetrics();

		new AggregateBackedCostSpikeDetector(metrics, CostSpikePolicy.DEFAULT)
			.assess(new CostSpikeQuery(PROVIDER, "USD", NOW));

		assertThat(metrics.queries()).hasSize(4);
		assertThat(metrics.queries().get(0).providerKey()).isNull();
		assertThat(metrics.queries().get(0).windowStart()).isEqualTo(Instant.parse("2026-09-04T11:34:56Z"));
		assertThat(metrics.queries().get(0).windowEnd()).isEqualTo(Instant.parse("2026-09-05T11:34:56Z"));
		assertThat(metrics.queries().get(1).windowStart()).isEqualTo(Instant.parse("2026-09-05T11:34:56Z"));
		assertThat(metrics.queries().get(1).windowEnd()).isEqualTo(NOW);
		assertThat(metrics.queries().get(2).providerKey()).isEqualTo(PROVIDER);
		assertThat(metrics.queries().get(2).windowStart()).isEqualTo(Instant.parse("2026-09-04T11:34:56Z"));
		assertThat(metrics.queries().get(2).windowEnd()).isEqualTo(Instant.parse("2026-09-05T11:34:56Z"));
		assertThat(metrics.queries().get(3).windowStart()).isEqualTo(Instant.parse("2026-09-05T11:34:56Z"));
		assertThat(metrics.queries().get(3).windowEnd()).isEqualTo(NOW);
	}

	@Test
	void invalidPoliciesQueriesAndAssessmentsAreRejectedAtConstruction() {
		var validDimensions = Set.of(SoftBreachDimension.GLOBAL);
		assertThatThrownBy(() -> new CostSpikePolicy(Duration.ZERO, 24, new BigDecimal("3.0"), 5, validDimensions))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CostSpikePolicy(Duration.ofHours(-1), 24, new BigDecimal("3.0"), 5,
			validDimensions)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CostSpikePolicy(Duration.ofHours(1), 0, new BigDecimal("3.0"), 5, validDimensions))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CostSpikePolicy(Duration.ofHours(1), 24, BigDecimal.ZERO, 5, validDimensions))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CostSpikePolicy(Duration.ofHours(1), 24, new BigDecimal("-1"), 5, validDimensions))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CostSpikePolicy(Duration.ofHours(1), 24, new BigDecimal("3.0"), -1,
			validDimensions)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CostSpikePolicy(Duration.ofHours(1), 24, new BigDecimal("3.0"), 5, Set.of()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CostSpikePolicy(Duration.ofHours(1), 24, new BigDecimal("3.0"), 5,
			Set.of(SoftBreachDimension.USER))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CostSpikePolicy(Duration.ofHours(1), 24, new BigDecimal("3.0"), 5,
			EnumSet.of(SoftBreachDimension.GLOBAL, SoftBreachDimension.USER)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CostSpikeQuery(null, "USD", NOW)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CostSpikeQuery(" ", "USD", NOW)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CostSpikeQuery(PROVIDER, null, NOW)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CostSpikeQuery(PROVIDER, " ", NOW)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CostSpikeQuery(PROVIDER, "USD", null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CostSpikeAssessment(true, null, BigDecimal.ONE, BigDecimal.ONE, 1,
			NOW.minusSeconds(60), NOW)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CostSpikeAssessment(false, SoftBreachDimension.GLOBAL, BigDecimal.ONE,
			BigDecimal.ONE, 1, NOW.minusSeconds(60), NOW)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CostSpikeAssessment(true, SoftBreachDimension.USER, BigDecimal.ONE, BigDecimal.ONE,
			1, NOW.minusSeconds(60), NOW)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CostSpikeAssessment(true, SoftBreachDimension.GLOBAL, new BigDecimal("-1"),
			BigDecimal.ONE, 1, NOW.minusSeconds(60), NOW)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CostSpikeAssessment(false, null, BigDecimal.ONE, null, 0, NOW.minusSeconds(60),
			NOW)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CostSpikeAssessment(false, null, BigDecimal.ONE, BigDecimal.ONE, -1,
			NOW.minusSeconds(60), NOW)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CostSpikeAssessment(false, null, BigDecimal.ONE, BigDecimal.ONE, 0, NOW,
			NOW.minusSeconds(60))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AggregateBackedCostSpikeDetector(null, CostSpikePolicy.DEFAULT))
			.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new AggregateBackedCostSpikeDetector(new StubMetrics(), null))
			.isInstanceOf(NullPointerException.class);
	}

	@Test
	void defaultPolicyCarriesTheFrozenThresholds() {
		assertThat(CostSpikePolicy.DEFAULT.observationWindow()).isEqualTo(Duration.ofHours(1));
		assertThat(CostSpikePolicy.DEFAULT.baselineWindowCount()).isEqualTo(24);
		assertThat(CostSpikePolicy.DEFAULT.spikeFactor()).isEqualByComparingTo(new BigDecimal("3.0"));
		assertThat(CostSpikePolicy.DEFAULT.minimumWindowExecutions()).isEqualTo(5);
		assertThat(CostSpikePolicy.DEFAULT.spikeDimensions()).containsExactlyInAnyOrder(SoftBreachDimension.GLOBAL,
			SoftBreachDimension.PROVIDER);
		var dimensions = new HashSet<>(Set.of(SoftBreachDimension.GLOBAL));
		var policy = new CostSpikePolicy(Duration.ofHours(1), 24, new BigDecimal("3.0"), 5, dimensions);
		dimensions.add(SoftBreachDimension.PROVIDER);
		assertThat(policy.spikeDimensions()).containsExactly(SoftBreachDimension.GLOBAL);
		assertThatThrownBy(() -> policy.spikeDimensions().add(SoftBreachDimension.PROVIDER))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void unknownAggregatesDoNotFlagSpikesButReportKnownGlobalMeasurements() {
		var metrics = new StubMetrics();
		metrics.put(WINDOW.GLOBAL_BASELINE, window("240", 240, "USD"));
		metrics.put(WINDOW.GLOBAL_CURRENT, window("25", 5, "USD")); // 25 x 24 = 600 <= 720: global stays normal
		metrics.put(WINDOW.PROVIDER_BASELINE, window("10", 10, "EUR"));
		metrics.put(WINDOW.PROVIDER_CURRENT, window("300", 6, "EUR"));

		var assessment = new AggregateBackedCostSpikeDetector(metrics, CostSpikePolicy.DEFAULT)
			.assess(new CostSpikeQuery(PROVIDER, "USD", NOW));

		assertThat(assessment.suspected()).isFalse();
		assertThat(assessment.dimension()).isNull();
		assertThat(assessment.currentWindowSpend()).isEqualByComparingTo(new BigDecimal("25"));
		assertThat(assessment.baselineMeanWindowSpend()).isEqualByComparingTo(new BigDecimal("10"));
		assertThat(assessment.currentWindowExecutions()).isEqualTo(5);
	}

	@Test
	void negativeAndMissingAggregatesAreNotSuspected() {
		var metrics = new StubMetrics();
		metrics.put(WINDOW.GLOBAL_BASELINE, window("-1", 0, "USD"));
		metrics.put(WINDOW.GLOBAL_CURRENT, window("100", 10, "USD"));

		var assessment = new AggregateBackedCostSpikeDetector(metrics, CostSpikePolicy.DEFAULT)
			.assess(new CostSpikeQuery(PROVIDER, "USD", NOW));

		assertThat(assessment.suspected()).isFalse();
		assertThat(assessment.dimension()).isNull();
		assertThat(assessment.baselineMeanWindowSpend()).isEqualByComparingTo(BigDecimal.ZERO);
	}

	@Test
	void unknownGlobalAggregatesReturnZeroedMeasurements() {
		var metrics = new StubMetrics();
		metrics.put(WINDOW.GLOBAL_CURRENT, window(null, 0, "USD"));

		var assessment = new AggregateBackedCostSpikeDetector(metrics, CostSpikePolicy.DEFAULT)
			.assess(new CostSpikeQuery(PROVIDER, "USD", NOW));

		assertThat(assessment.suspected()).isFalse();
		assertThat(assessment.dimension()).isNull();
		assertThat(assessment.currentWindowSpend()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(assessment.baselineMeanWindowSpend()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(assessment.currentWindowExecutions()).isZero();
		assertThat(assessment.observationWindowStart()).isEqualTo(NOW.minus(Duration.ofHours(1)));
		assertThat(assessment.observationWindowEnd()).isEqualTo(NOW);
	}

	@Test
	void globalSpikeShortCircuitsBeforeProviderQueriesAreIssued() {
		var metrics = new StubMetrics();
		metrics.put(WINDOW.GLOBAL_BASELINE, window("240", 240, "USD"));
		metrics.put(WINDOW.GLOBAL_CURRENT, window("31", 6, "USD"));

		var assessment = new AggregateBackedCostSpikeDetector(metrics, CostSpikePolicy.DEFAULT)
			.assess(new CostSpikeQuery(PROVIDER, "USD", NOW));

		assertThat(assessment.suspected()).isTrue();
		assertThat(assessment.dimension()).isEqualTo(SoftBreachDimension.GLOBAL);
		assertThat(metrics.queries()).hasSize(2);
		assertThat(metrics.queries()).allSatisfy(query -> assertThat(query.providerKey()).isNull());
	}

	private enum WINDOW {
		GLOBAL_BASELINE,
		GLOBAL_CURRENT,
		PROVIDER_BASELINE,
		PROVIDER_CURRENT
	}

	private static WINDOW classify(CostQuery query) {
		var providerScoped = query.providerKey() != null;
		if (query.windowEnd().equals(NOW)) {
			return providerScoped ? WINDOW.PROVIDER_CURRENT : WINDOW.GLOBAL_CURRENT;
		}
		return providerScoped ? WINDOW.PROVIDER_BASELINE : WINDOW.GLOBAL_BASELINE;
	}

	private static CostAggregate window(String spend, long requestCount, String currency) {
		return new CostAggregate(spend == null ? null : new BigDecimal(spend), null, null, null, null, null,
			requestCount, 0, 0, 0, 0, requestCount, currency);
	}

	private static final class StubMetrics implements GenerationCostMetrics {
		private final Map<WINDOW, CostAggregate> aggregates = new HashMap<>();
		private final ArrayList<CostQuery> queries = new ArrayList<>();

		private void put(WINDOW window, CostAggregate aggregate) {
			aggregates.put(window, aggregate);
		}

		private ArrayList<CostQuery> queries() {
			return queries;
		}

		@Override
		public void recordAttempt(GenerationCostFact fact) {
			throw new AssertionError("spike detection must not record spend");
		}

		@Override
		public CostAggregate aggregate(CostQuery query) {
			queries.add(query);
			return aggregates.get(classify(query));
		}
	}
}
