package com.aifashionvault.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.aifashionvault.generation.CostAggregate;
import com.aifashionvault.generation.CostQuery;
import com.aifashionvault.generation.GenerationCostMetrics;
import com.aifashionvault.generation.GenerationCostFact;
import com.aifashionvault.shared.UserId;

class GenerationAbuseControlsTest {
	private static final UserId USER_ID = new UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
	private static final Instant NOW = Instant.parse("2026-08-31T12:00:00Z");
	private static final AbuseBudgetPolicy POLICY = new AbuseBudgetPolicy(
		new BigDecimal("100"), new BigDecimal("20"), Map.of("provider", new BigDecimal("50")), "USD",
		Duration.ofDays(1));

	@Test
	void facadeDelegatesAdmissionExactlyOnceAndReturnsTheSameDecision() {
		var calls = new AtomicInteger();
		var expected = GenerationAdmissionDecision.accepted(1, 5, LocalDate.parse("2026-08-31"));
		GenerationRateLimiter limiter = new GenerationRateLimiter() {
			@Override
			public Reservation reserve(GenerationAdmissionRequest request) {
				calls.incrementAndGet();
				return new Reservation(expected, "reservation-token");
			}

			@Override
			public void commit(Reservation reservation) {
			}

			@Override
			public void release(Reservation reservation) {
			}
		};
		var controls = new GenerationAdmissionFacade(limiter, request -> unknown(request, POLICY));
		var request = new GenerationAdmissionRequest(USER_ID, "device", "127.0.0.1", GenerationRiskTier.ORDINARY, NOW);

		assertThat(controls.admitGeneration(request)).isSameAs(expected);
		assertThat(calls).hasValue(1);
	}

	@Test
	void allowsWhenSpendPlusProjectionIsBelowEveryCeilingAndUsesPerUserCostQuery() {
		var metrics = new StubMetrics(new BigDecimal("30"), new BigDecimal("10"), new BigDecimal("5"), "USD");
		var breaker = new AggregateBackedCostCircuitBreaker(metrics, POLICY);

		var decision = breaker.authorize(request("4", "USD"));

		assertThat(decision.reason()).isEqualTo(AbuseDecisionReason.ALLOWED);
		assertThat(decision.allowed()).isTrue();
		assertThat(metrics.queries).anySatisfy(query -> {
			assertThat(query.userId()).isEqualTo(USER_ID);
			assertThat(query.currency()).isEqualTo("USD");
			assertThat(query.windowStart()).isEqualTo(NOW.minus(Duration.ofDays(1)));
			assertThat(query.windowEnd()).isEqualTo(NOW);
		});
	}

	@Test
	void providerCeilingAggregatesEveryModelRoute() {
		var metrics = new StubMetrics(BigDecimal.ZERO, new BigDecimal("49"), BigDecimal.ZERO, "USD");
		var decision = new AggregateBackedCostCircuitBreaker(metrics, POLICY).authorize(request("2", "USD"));

		assertThat(decision.reason()).isEqualTo(AbuseDecisionReason.PROVIDER_BUDGET_EXHAUSTED);
		assertThat(metrics.queries).anySatisfy(query -> {
			assertThat(query.providerKey()).isEqualTo("provider");
			assertThat(query.modelRouteAuditIdentity()).isNull();
		});
	}

	@Test
	void appliesGlobalThenProviderThenUserPrecedence() {
		assertThat(decide(new BigDecimal("99"), new BigDecimal("49"), new BigDecimal("19"), "2").reason())
			.isEqualTo(AbuseDecisionReason.GLOBAL_COST_CIRCUIT_OPEN);
		assertThat(decide(new BigDecimal("10"), new BigDecimal("49"), new BigDecimal("19"), "2").reason())
			.isEqualTo(AbuseDecisionReason.PROVIDER_BUDGET_EXHAUSTED);
		assertThat(decide(new BigDecimal("10"), new BigDecimal("10"), new BigDecimal("19"), "2").reason())
			.isEqualTo(AbuseDecisionReason.USER_COST_BUDGET_EXHAUSTED);
	}

	@Test
	void unknownOrWrongCurrencySpendFailsClosed() {
		assertThat(decide(null, new BigDecimal("1"), new BigDecimal("1"), "1").reason())
			.isEqualTo(AbuseDecisionReason.COST_UNKNOWN);
		var metrics = new StubMetrics(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, "EUR");
		assertThat(new AggregateBackedCostCircuitBreaker(metrics, POLICY).authorize(request("1", "USD")).reason())
			.isEqualTo(AbuseDecisionReason.COST_UNKNOWN);
	}

	@Test
	void requestCurrencyMismatchAndMissingProviderBudgetFailClosedWithoutAggregation() {
		var metrics = new StubMetrics(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "USD");
		var breaker = new AggregateBackedCostCircuitBreaker(metrics, POLICY);

		assertThat(breaker.authorize(request("1", "EUR")).reason()).isEqualTo(AbuseDecisionReason.COST_UNKNOWN);
		var missingProvider = new ProviderExecutionBudgetRequest(USER_ID, "other", "model", BigDecimal.ONE, "USD", NOW);
		assertThat(breaker.authorize(missingProvider).reason()).isEqualTo(AbuseDecisionReason.COST_UNKNOWN);
		assertThat(metrics.queries).isEmpty();
	}

	@Test
	void invalidPolicyAndRequestsAreRejectedAtConstruction() {
		assertThatThrownBy(() -> new AbuseBudgetPolicy(BigDecimal.ZERO, BigDecimal.ONE, Map.of("provider", BigDecimal.ONE),
			"USD", Duration.ofDays(1))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AbuseBudgetPolicy(BigDecimal.ONE, BigDecimal.ONE, Map.of("provider", BigDecimal.ZERO),
			"USD", Duration.ofDays(1))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AbuseBudgetPolicy(BigDecimal.ONE, BigDecimal.ONE, Map.of("provider", BigDecimal.ONE),
			"USD", Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ProviderExecutionBudgetRequest(USER_ID, "provider", "model", BigDecimal.ZERO, "USD", NOW))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ProviderExecutionBudgetRequest(USER_ID, " ", "model", BigDecimal.ONE, "USD", NOW))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void policyAndDecisionAreImmutableAndInternallyConsistent() {
		var providers = new java.util.HashMap<String, BigDecimal>();
		providers.put("provider", BigDecimal.TEN);
		var policy = new AbuseBudgetPolicy(BigDecimal.TEN, BigDecimal.TEN, providers, "USD", Duration.ofHours(1));
		providers.put("other", BigDecimal.ONE);
		assertThat(policy.providerCeilings()).containsOnlyKeys("provider");
		assertThatThrownBy(() -> new AbuseDecision(true, AbuseDecisionReason.COST_UNKNOWN, null, null, "USD",
			NOW.minusSeconds(1), NOW)).isInstanceOf(IllegalArgumentException.class);
	}

	private static AbuseDecision decide(BigDecimal global, BigDecimal provider, BigDecimal user, String projection) {
		var metrics = new StubMetrics(global, provider, user, "USD");
		return new AggregateBackedCostCircuitBreaker(metrics, POLICY).authorize(request(projection, "USD"));
	}

	private static ProviderExecutionBudgetRequest request(String projection, String currency) {
		return new ProviderExecutionBudgetRequest(USER_ID, "provider", "model", new BigDecimal(projection), currency, NOW);
	}

	private static AbuseDecision unknown(ProviderExecutionBudgetRequest request, AbuseBudgetPolicy policy) {
		return new AbuseDecision(false, AbuseDecisionReason.COST_UNKNOWN, null, null, request.currency(),
			request.now().minus(policy.window()), request.now());
	}

	private static final class StubMetrics implements GenerationCostMetrics {
		private final BigDecimal global;
		private final BigDecimal provider;
		private final BigDecimal user;
		private final String currency;
		private final ArrayList<CostQuery> queries = new ArrayList<>();

		private StubMetrics(BigDecimal global, BigDecimal provider, BigDecimal user, String currency) {
			this.global = global;
			this.provider = provider;
			this.user = user;
			this.currency = currency;
		}

		@Override
		public void recordAttempt(GenerationCostFact fact) {
			throw new AssertionError("authorization must not record spend");
		}

		@Override
		public CostAggregate aggregate(CostQuery query) {
			queries.add(query);
			var spend = query.userId() != null ? user : query.providerKey() != null ? provider : global;
			return new CostAggregate(spend, null, null, null, null, null, 0, 0, 0, 0, 0,
				spend == null ? 0 : 1, currency);
		}
	}
}
