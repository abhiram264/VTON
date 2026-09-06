package com.aifashionvault.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifashionvault.generation.CostAggregate;
import com.aifashionvault.generation.CostQuery;
import com.aifashionvault.generation.GenerationCostFact;
import com.aifashionvault.generation.GenerationCostMetrics;
import com.aifashionvault.shared.UserId;

class GenerationCostCircuitBreakerTest {
	private static final Instant NOW = Instant.parse("2026-09-05T12:00:00Z");
	private static final UserId USER_A = new UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
	private static final UserId USER_B = new UserId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
	private static final AbuseBudgetPolicy POLICY = new AbuseBudgetPolicy(
		new BigDecimal("100"), new BigDecimal("20"), Map.of("provider", new BigDecimal("50")), "USD",
		Duration.ofDays(1));
	private static final CostBreachPolicy BREACH_POLICY = CostBreachPolicy.DEFAULT;

	@Test
	void normalSpendIsAllowedWithNonSuspectedIndicatorsAndNoActions() {
		var metrics = new StubMetrics();
		metrics.put(WINDOW.GLOBAL_CEILING, spend("10"));
		metrics.put(WINDOW.PROVIDER_CEILING, spend("5"));
		metrics.putUser(USER_A, spend("1"));

		var decision = breaker(metrics).authorize(request(USER_A, "1"));

		assertThat(decision.allowed()).isTrue();
		assertThat(decision.reason()).isEqualTo(AbuseDecisionReason.ALLOWED);
		assertThat(decision.observedSpend()).isEqualByComparingTo(new BigDecimal("1"));
		assertThat(decision.ceiling()).isEqualByComparingTo(new BigDecimal("20"));
		assertThat(decision.indicators()).isNotNull();
		assertThat(decision.indicators().softBreach()).isNull();
		assertThat(decision.indicators().spike()).isNotNull();
		assertThat(decision.indicators().spike().suspected()).isFalse();
		assertThat(decision.indicators().actions()).isEmpty();
	}

	@Test
	void globalSoftBreachAtExactlyEightyPercentIsAllowedWithThreeActions() {
		var metrics = new StubMetrics();
		metrics.put(WINDOW.GLOBAL_CEILING, spend("79")); // 79 + 1 == 100 x 0.80 exactly
		metrics.put(WINDOW.PROVIDER_CEILING, spend("5"));
		metrics.putUser(USER_A, spend("1"));

		var decision = breaker(metrics).authorize(request(USER_A, "1"));

		assertThat(decision.allowed()).isTrue();
		assertThat(decision.reason()).isEqualTo(AbuseDecisionReason.ALLOWED);
		assertThat(decision.indicators().softBreach()).isEqualTo(SoftBreachDimension.GLOBAL);
		assertThat(decision.indicators().spike().suspected()).isFalse();
		assertThat(decision.indicators().actions()).containsExactlyInAnyOrder(CostBreachAction.DISABLE_HD_GENERATION,
			CostBreachAction.RESTRICT_MULTI_ITEM, CostBreachAction.PREFER_CHEAPER_PROVIDER);
	}

	@Test
	void softBreachPrecedenceReportsProviderBeforeUser() {
		var metrics = new StubMetrics();
		metrics.put(WINDOW.GLOBAL_CEILING, spend("10"));
		metrics.put(WINDOW.PROVIDER_CEILING, spend("39")); // 39 + 1 == 50 x 0.80
		metrics.putUser(USER_A, spend("15")); // 15 + 1 == 20 x 0.80 as well

		var decision = breaker(metrics).authorize(request(USER_A, "1"));

		assertThat(decision.allowed()).isTrue();
		assertThat(decision.indicators().softBreach()).isEqualTo(SoftBreachDimension.PROVIDER);
	}

	@Test
	void projectionCountsTowardTheSoftThreshold() {
		var metrics = new StubMetrics();
		metrics.put(WINDOW.GLOBAL_CEILING, spend("70")); // below 80 alone, 70 + 10 reaches it
		metrics.put(WINDOW.PROVIDER_CEILING, spend("5"));
		metrics.putUser(USER_A, spend("1"));

		var decision = breaker(metrics).authorize(request(USER_A, "10"));

		assertThat(decision.allowed()).isTrue();
		assertThat(decision.indicators().softBreach()).isEqualTo(SoftBreachDimension.GLOBAL);
		assertThat(decision.indicators().actions()).containsExactlyInAnyOrder(CostBreachAction.DISABLE_HD_GENERATION,
			CostBreachAction.RESTRICT_MULTI_ITEM, CostBreachAction.PREFER_CHEAPER_PROVIDER);
	}

	@Test
	void suspectedGlobalSpikeIsAllowedWithAllFourActions() {
		var metrics = new StubMetrics();
		metrics.put(WINDOW.GLOBAL_CEILING, spend("10"));
		metrics.put(WINDOW.PROVIDER_CEILING, spend("5"));
		metrics.putUser(USER_A, spend("1"));
		// Baseline 240 over 24 prior hours (mean 10/hour); current 31: 31 x 24 = 744 > 240 x 3.0 = 720.
		metrics.put(WINDOW.GLOBAL_BASELINE, window("240", 240));
		metrics.put(WINDOW.GLOBAL_CURRENT, window("31", 6));

		var decision = breaker(metrics).authorize(request(USER_A, "1"));

		assertThat(decision.allowed()).isTrue();
		assertThat(decision.reason()).isEqualTo(AbuseDecisionReason.ALLOWED);
		assertThat(decision.indicators().softBreach()).isNull();
		var spike = decision.indicators().spike();
		assertThat(spike.suspected()).isTrue();
		assertThat(spike.dimension()).isEqualTo(SoftBreachDimension.GLOBAL);
		assertThat(spike.currentWindowSpend()).isEqualByComparingTo(new BigDecimal("31"));
		assertThat(spike.baselineMeanWindowSpend()).isEqualByComparingTo(new BigDecimal("10"));
		assertThat(spike.currentWindowExecutions()).isEqualTo(6);
		assertThat(spike.observationWindowStart()).isEqualTo(NOW.minus(Duration.ofHours(1)));
		assertThat(spike.observationWindowEnd()).isEqualTo(NOW);
		assertThat(decision.indicators().actions()).containsExactlyInAnyOrder(CostBreachAction.values());
	}

	@Test
	void providerScopedSpikeIsSuspectedWhenGlobalWindowsStayNormal() {
		var metrics = new StubMetrics();
		metrics.put(WINDOW.GLOBAL_CEILING, spend("10"));
		metrics.put(WINDOW.PROVIDER_CEILING, spend("5"));
		metrics.putUser(USER_A, spend("1"));
		metrics.put(WINDOW.GLOBAL_BASELINE, window("240", 240));
		metrics.put(WINDOW.GLOBAL_CURRENT, window("25", 5)); // 25 x 24 = 600 <= 720
		metrics.put(WINDOW.PROVIDER_BASELINE, window("50", 50));
		metrics.put(WINDOW.PROVIDER_CURRENT, window("51", 5)); // 51 x 24 = 1224 > 50 x 3.0 = 150

		var decision = breaker(metrics).authorize(request(USER_A, "1"));

		assertThat(decision.allowed()).isTrue();
		assertThat(decision.indicators().spike().suspected()).isTrue();
		assertThat(decision.indicators().spike().dimension()).isEqualTo(SoftBreachDimension.PROVIDER);
		assertThat(decision.indicators().actions()).containsExactlyInAnyOrder(CostBreachAction.values());
	}

	@Test
	void globalHardBreachKeepsReasonAndPayloadAndCarriesAllFourActions() {
		var metrics = new StubMetrics();
		metrics.put(WINDOW.GLOBAL_CEILING, spend("99"));
		metrics.put(WINDOW.PROVIDER_CEILING, spend("49"));
		metrics.putUser(USER_A, spend("19"));

		var decision = breaker(metrics).authorize(request(USER_A, "2"));

		assertThat(decision.allowed()).isFalse();
		assertThat(decision.reason()).isEqualTo(AbuseDecisionReason.GLOBAL_COST_CIRCUIT_OPEN);
		assertThat(decision.observedSpend()).isEqualByComparingTo(new BigDecimal("99"));
		assertThat(decision.ceiling()).isEqualByComparingTo(new BigDecimal("100"));
		assertThat(decision.indicators()).isNotNull();
		assertThat(decision.indicators().softBreach()).isEqualTo(SoftBreachDimension.GLOBAL);
		assertThat(decision.indicators().actions()).containsExactlyInAnyOrder(CostBreachAction.values());
	}

	@Test
	void providerHardBreachKeepsReasonAndPayloadAndCarriesAllFourActions() {
		var metrics = new StubMetrics();
		metrics.put(WINDOW.GLOBAL_CEILING, spend("10"));
		metrics.put(WINDOW.PROVIDER_CEILING, spend("49"));
		metrics.putUser(USER_A, spend("1"));

		var decision = breaker(metrics).authorize(request(USER_A, "2"));

		assertThat(decision.allowed()).isFalse();
		assertThat(decision.reason()).isEqualTo(AbuseDecisionReason.PROVIDER_BUDGET_EXHAUSTED);
		assertThat(decision.observedSpend()).isEqualByComparingTo(new BigDecimal("49"));
		assertThat(decision.ceiling()).isEqualByComparingTo(new BigDecimal("50"));
		assertThat(decision.indicators().softBreach()).isEqualTo(SoftBreachDimension.PROVIDER);
		assertThat(decision.indicators().actions()).containsExactlyInAnyOrder(CostBreachAction.values());
	}

	@Test
	void userHardBreachKeepsReasonAndPayloadAndCarriesAllFourActions() {
		var metrics = new StubMetrics();
		metrics.put(WINDOW.GLOBAL_CEILING, spend("10"));
		metrics.put(WINDOW.PROVIDER_CEILING, spend("10"));
		metrics.putUser(USER_A, spend("19"));

		var decision = breaker(metrics).authorize(request(USER_A, "2"));

		assertThat(decision.allowed()).isFalse();
		assertThat(decision.reason()).isEqualTo(AbuseDecisionReason.USER_COST_BUDGET_EXHAUSTED);
		assertThat(decision.observedSpend()).isEqualByComparingTo(new BigDecimal("19"));
		assertThat(decision.ceiling()).isEqualByComparingTo(new BigDecimal("20"));
		assertThat(decision.indicators().softBreach()).isEqualTo(SoftBreachDimension.USER);
		assertThat(decision.indicators().actions()).containsExactlyInAnyOrder(CostBreachAction.values());
	}

	@Test
	void failClosedPathsKeepCostUnknownAndNullIndicators() {
		var metrics = new StubMetrics();
		metrics.put(WINDOW.PROVIDER_CEILING, spend("1"));
		metrics.putUser(USER_A, spend("1"));
		var breaker = breaker(metrics);

		var wrongCurrency = breaker.authorize(new ProviderExecutionBudgetRequest(USER_A, "provider", "model",
			BigDecimal.ONE, "EUR", NOW));
		assertThat(wrongCurrency.allowed()).isFalse();
		assertThat(wrongCurrency.reason()).isEqualTo(AbuseDecisionReason.COST_UNKNOWN);
		assertThat(wrongCurrency.indicators()).isNull();

		var missingProvider = breaker.authorize(new ProviderExecutionBudgetRequest(USER_A, "other", "model",
			BigDecimal.ONE, "USD", NOW));
		assertThat(missingProvider.allowed()).isFalse();
		assertThat(missingProvider.reason()).isEqualTo(AbuseDecisionReason.COST_UNKNOWN);
		assertThat(missingProvider.indicators()).isNull();
		assertThat(metrics.queries).isEmpty();

		var unknownGlobal = new StubMetrics();
		unknownGlobal.put(WINDOW.PROVIDER_CEILING, spend("1"));
		unknownGlobal.putUser(USER_A, spend("1"));
		var unknown = breaker(unknownGlobal).authorize(request(USER_A, "1"));
		assertThat(unknown.allowed()).isFalse();
		assertThat(unknown.reason()).isEqualTo(AbuseDecisionReason.COST_UNKNOWN);
		assertThat(unknown.indicators()).isNull();
		assertThat(unknownGlobal.queries).hasSize(3);
		assertThat(unknownGlobal.queries).allSatisfy(query -> {
			assertThat(query.windowStart()).isEqualTo(NOW.minus(Duration.ofDays(1)));
			assertThat(query.windowEnd()).isEqualTo(NOW);
		});
	}

	@Test
	void anotherUsersSpendDoesNotDegradeThisUsersDecision() {
		var metrics = new StubMetrics();
		metrics.put(WINDOW.GLOBAL_CEILING, spend("10"));
		metrics.put(WINDOW.PROVIDER_CEILING, spend("5"));
		metrics.putUser(USER_A, spend("1"));
		metrics.putUser(USER_B, spend("18")); // 90% of the per-user ceiling
		var breaker = breaker(metrics);

		var decision = breaker.authorize(request(USER_A, "1"));

		assertThat(decision.allowed()).isTrue();
		assertThat(decision.reason()).isEqualTo(AbuseDecisionReason.ALLOWED);
		assertThat(decision.indicators().softBreach()).isNull();

		var otherUser = breaker.authorize(request(USER_B, "1")); // 18 + 1 >= 20 x 0.80 but below the ceiling
		assertThat(otherUser.allowed()).isTrue();
		assertThat(otherUser.indicators().softBreach()).isEqualTo(SoftBreachDimension.USER);
	}

	@Test
	void twoArgConstructorAppliesDefaultSoftFractionsAndSpikePolicy() {
		var metrics = new StubMetrics();
		metrics.put(WINDOW.GLOBAL_CEILING, spend("79")); // 79 + 1 == 100 x 0.80 exactly
		metrics.put(WINDOW.PROVIDER_CEILING, spend("5"));
		metrics.putUser(USER_A, spend("1"));
		metrics.put(WINDOW.GLOBAL_BASELINE, window("240", 240));
		metrics.put(WINDOW.GLOBAL_CURRENT, window("31", 6));

		var decision = new AggregateBackedCostCircuitBreaker(metrics, POLICY).authorize(request(USER_A, "1"));

		assertThat(decision.allowed()).isTrue();
		assertThat(decision.indicators().softBreach()).isEqualTo(SoftBreachDimension.GLOBAL);
		assertThat(decision.indicators().spike().suspected()).isTrue();
		assertThat(decision.indicators().actions()).containsExactlyInAnyOrder(CostBreachAction.values());
	}

	@Test
	void identicalRequestsAgainstUnchangedAggregatesProduceEqualDecisions() {
		var metrics = new StubMetrics();
		metrics.put(WINDOW.GLOBAL_CEILING, spend("79"));
		metrics.put(WINDOW.PROVIDER_CEILING, spend("5"));
		metrics.putUser(USER_A, spend("1"));
		metrics.put(WINDOW.GLOBAL_BASELINE, window("240", 240));
		metrics.put(WINDOW.GLOBAL_CURRENT, window("31", 6));
		var breaker = breaker(metrics);

		var first = breaker.authorize(request(USER_A, "1"));
		var second = breaker.authorize(request(USER_A, "1"));

		assertThat(second).isEqualTo(first);
	}

	@Test
	void invalidBreachPoliciesAndIndicatorsAreRejectedAtConstruction() {
		assertThatThrownBy(() -> new CostBreachPolicy(BigDecimal.ZERO, new BigDecimal("0.80"),
			new BigDecimal("0.80"), CostSpikePolicy.DEFAULT)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CostBreachPolicy(new BigDecimal("1.01"), new BigDecimal("0.80"),
			new BigDecimal("0.80"), CostSpikePolicy.DEFAULT)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CostBreachPolicy(null, new BigDecimal("0.80"), new BigDecimal("0.80"),
			CostSpikePolicy.DEFAULT)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CostBreachPolicy(new BigDecimal("0.80"), new BigDecimal("0.80"),
			new BigDecimal("0.80"), null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CostBreachIndicators(SoftBreachDimension.GLOBAL, null, Set.of()))
			.isInstanceOf(IllegalArgumentException.class);
		var suspected = new CostSpikeAssessment(true, SoftBreachDimension.GLOBAL, BigDecimal.ONE, BigDecimal.ONE, 5,
			NOW.minusSeconds(60), NOW);
		assertThatThrownBy(() -> new CostBreachIndicators(null, suspected, Set.of()))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void indicatorsDefensivelyCopyTheActionSet() {
		var actions = new HashSet<>(Set.of(CostBreachAction.PREFER_CHEAPER_PROVIDER));

		var indicators = new CostBreachIndicators(SoftBreachDimension.GLOBAL, null, actions);
		actions.add(CostBreachAction.TEMPORARILY_QUEUE);

		assertThat(indicators.actions()).containsExactly(CostBreachAction.PREFER_CHEAPER_PROVIDER);
		assertThatThrownBy(() -> indicators.actions().add(CostBreachAction.RESTRICT_MULTI_ITEM))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void sevenArgAbuseDecisionConstructorStaysCompatibleWithNullIndicators() {
		var decision = new AbuseDecision(true, AbuseDecisionReason.ALLOWED, BigDecimal.ONE, new BigDecimal("20"),
			"USD", NOW.minus(Duration.ofDays(1)), NOW);

		assertThat(decision.indicators()).isNull();
	}

	private static AggregateBackedCostCircuitBreaker breaker(StubMetrics metrics) {
		return new AggregateBackedCostCircuitBreaker(metrics, POLICY, BREACH_POLICY);
	}

	private static ProviderExecutionBudgetRequest request(UserId userId, String projection) {
		return new ProviderExecutionBudgetRequest(userId, "provider", "model", new BigDecimal(projection), "USD", NOW);
	}

	private static CostAggregate spend(String amount) {
		var cost = amount == null ? null : new BigDecimal(amount);
		return new CostAggregate(cost, null, null, null, null, null, 0, 0, 0, 0, 0, cost == null ? 0 : 1, "USD");
	}

	private static CostAggregate window(String amount, long requestCount) {
		return new CostAggregate(new BigDecimal(amount), null, null, null, null, null, requestCount, 0, 0, 0, 0,
			requestCount, "USD");
	}

	private enum WINDOW {
		GLOBAL_CEILING,
		PROVIDER_CEILING,
		USER_CEILING,
		GLOBAL_BASELINE,
		GLOBAL_CURRENT,
		PROVIDER_BASELINE,
		PROVIDER_CURRENT
	}

	private static WINDOW classify(CostQuery query) {
		var providerScoped = query.providerKey() != null;
		if (query.windowStart().equals(NOW.minus(Duration.ofDays(1)))) {
			if (query.userId() != null) {
				return WINDOW.USER_CEILING;
			}
			return providerScoped ? WINDOW.PROVIDER_CEILING : WINDOW.GLOBAL_CEILING;
		}
		if (query.windowEnd().equals(NOW)) {
			return providerScoped ? WINDOW.PROVIDER_CURRENT : WINDOW.GLOBAL_CURRENT;
		}
		return providerScoped ? WINDOW.PROVIDER_BASELINE : WINDOW.GLOBAL_BASELINE;
	}

	private static final class StubMetrics implements GenerationCostMetrics {
		private final Map<WINDOW, CostAggregate> aggregates = new HashMap<>();
		private final Map<UserId, CostAggregate> userCeilings = new HashMap<>();
		private final ArrayList<CostQuery> queries = new ArrayList<>();

		private void put(WINDOW window, CostAggregate aggregate) {
			aggregates.put(window, aggregate);
		}

		private void putUser(UserId userId, CostAggregate aggregate) {
			userCeilings.put(userId, aggregate);
		}

		@Override
		public void recordAttempt(GenerationCostFact fact) {
			throw new AssertionError("authorization must not record spend");
		}

		@Override
		public CostAggregate aggregate(CostQuery query) {
			queries.add(query);
			if (classify(query) == WINDOW.USER_CEILING) {
				return userCeilings.get(query.userId());
			}
			return aggregates.get(classify(query));
		}
	}
}
