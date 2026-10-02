package com.aifashionvault.platform.generation;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.aifashionvault.admission.AbuseDecision;
import com.aifashionvault.admission.AbuseDecisionReason;
import com.aifashionvault.admission.GlobalCostCircuitBreaker;
import com.aifashionvault.admission.ProviderExecutionBudgetRequest;
import com.aifashionvault.generation.CostAggregate;
import com.aifashionvault.generation.CostQuery;
import com.aifashionvault.generation.GenerationCostFact;
import com.aifashionvault.generation.GenerationCostMetrics;
import com.aifashionvault.generation.vton.ProviderKey;
import com.aifashionvault.generation.vton.ProviderRegistry;
import com.aifashionvault.generation.vton.VtonProvider;
import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C19: the approved spend caps are configuration, not compiled constants. These
 * tests drive the real admission breaker through a Spring context so a
 * configured ceiling must actually reach the constructed policy.
 */
class GenerationSpendPropertiesTest {

	private static final ProviderKey PROVIDER = new ProviderKey("fashn");

	private static final UserId USER = new UserId(UUID.fromString("00000000-0000-0000-0000-000000000028"));

	/** Observed spend reported by the stub cost metrics for every aggregate query. */
	private static volatile BigDecimal observedSpend = BigDecimal.ZERO;

	@Configuration(proxyBeanMethods = false)
	static class StubBeans {

		@Bean
		@Primary
		GenerationCostMetrics stubGenerationCostMetrics() {
			return new GenerationCostMetrics() {
				@Override
				public void recordAttempt(GenerationCostFact fact) {
				}

				@Override
				public CostAggregate aggregate(CostQuery query) {
					return new CostAggregate(observedSpend, null, null, null, null, null, 0, 0, 0, 0, 0, 0, "USD");
				}
			};
		}

		@Bean
		ProviderRegistry stubProviderRegistry() {
			return new ProviderRegistry() {
				@Override
				public VtonProvider resolve(ProviderKey providerKey) {
					return new ReplaySafeFakeVtonProvider(providerKey);
				}

				@Override
				public ProviderKey selectedProviderKey() {
					return PROVIDER;
				}
			};
		}
	}

	private static ApplicationContextRunner baseRunner() {
		return new ApplicationContextRunner()
			.withUserConfiguration(GenerationProcessorConfiguration.class, StubBeans.class);
	}

	private static ProviderExecutionBudgetRequest request() {
		return new ProviderExecutionBudgetRequest(USER, PROVIDER.value(), "route", new BigDecimal("1.00"), "USD",
			Instant.parse("2026-10-03T00:00:00Z"));
	}

	@Test
	void declaredDefaultsReproduceThePreviousLaunchCaps() {
		GenerationSpendProperties defaults = new GenerationSpendProperties();
		assertThat(defaults.getGlobalCeiling()).isEqualByComparingTo("500.00");
		assertThat(defaults.getPerUserCeiling()).isEqualByComparingTo("25.00");
		assertThat(defaults.getProviderCeiling()).isEqualByComparingTo("250.00");
		assertThat(defaults.getCurrency()).isEqualTo("USD");
		assertThat(defaults.getWindow()).isEqualTo(Duration.ofDays(1));
	}

	@Test
	void configuredPerUserCeilingReachesTheAdmissionPolicy() {
		baseRunner()
			.withPropertyValues("vault.generation.spend.per-user-ceiling=7.50")
			.run(context -> {
				GlobalCostCircuitBreaker breaker = context.getBeanProvider(GlobalCostCircuitBreaker.class)
					.getIfAvailable();
				assertThat(breaker).isNotNull();
				AbuseDecision decision = breaker.authorize(request());
				assertThat(decision.allowed()).isTrue();
				assertThat(decision.ceiling()).isEqualByComparingTo("7.50");
			});
	}

	@Test
	void configuredGlobalCeilingBlocksAdmission() {
		observedSpend = new BigDecimal("5.00");
		try {
			baseRunner()
				.withPropertyValues("vault.generation.spend.global-ceiling=1.00")
				.run(context -> {
					GlobalCostCircuitBreaker breaker = context.getBeanProvider(GlobalCostCircuitBreaker.class)
						.getIfAvailable();
					assertThat(breaker).isNotNull();
					AbuseDecision decision = breaker.authorize(request());
					assertThat(decision.allowed()).isFalse();
					assertThat(decision.reason()).isEqualTo(AbuseDecisionReason.GLOBAL_COST_CIRCUIT_OPEN);
					assertThat(decision.ceiling()).isEqualByComparingTo("1.00");
				});
		}
		finally {
			observedSpend = BigDecimal.ZERO;
		}
	}
}
