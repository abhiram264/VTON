package com.aifashionvault.generation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifashionvault.shared.GenerationJobId;
import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuotaAdmissionTest {

	@Test
	void defaultsAreFiveAcceptedTwoActiveSixProducts() {
		assertThat(AdmissionPolicy.DEFAULT.acceptedJobsPer24Hours()).isEqualTo(5);
		assertThat(AdmissionPolicy.DEFAULT.maxActiveJobs()).isEqualTo(2);
		assertThat(AdmissionPolicy.DEFAULT.maxProductsPerJob()).isEqualTo(6);
		assertThat(AdmissionPolicy.DEFAULT.allowsProductCount(0)).isTrue();
		assertThat(AdmissionPolicy.DEFAULT.allowsProductCount(6)).isTrue();
		assertThat(AdmissionPolicy.DEFAULT.allowsProductCount(7)).isFalse();
	}

	@Test
	void rejectsNonPositiveLimits() {
		assertThatThrownBy(() -> new AdmissionPolicy(0, 2, 6))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AdmissionPolicy(5, 0, 6))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AdmissionPolicy(5, 2, 0))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void quotaReservationIsActiveUntilReleased() {
		QuotaReservation reserved = new QuotaReservation(
			UUID.randomUUID(),
			new UserId(UUID.randomUUID()),
			new GenerationJobId(UUID.randomUUID()),
			Instant.parse("2026-08-22T00:00:00Z"),
			null);

		assertThat(reserved.active()).isTrue();
		assertThat(new QuotaReservation(
			reserved.id(),
			reserved.userId(),
			reserved.generationJobId(),
			reserved.reservedAt(),
			Instant.parse("2026-08-22T00:01:00Z")).active()).isFalse();
	}

	@Test
	void pricingSnapshotRequiresProviderAndCurrency() {
		Instant observedAt = Instant.parse("2026-08-22T00:00:00Z");
		PricingSnapshot snapshot = new PricingSnapshot(
			"example-provider",
			"route-v1",
			"catalog-2026-08",
			observedAt,
			"generation",
			new BigDecimal("1"),
			new BigDecimal("0.04"),
			"USD",
			new BigDecimal("0.08"));

		assertThat(snapshot.providerKey()).isEqualTo("example-provider");
		assertThat(snapshot.pricingCatalogVersion()).isEqualTo("catalog-2026-08");
		assertThat(PricingSnapshot.isCostStatus("ESTIMATED")).isTrue();
		assertThat(PricingSnapshot.isCostStatus("FINAL")).isTrue();
		assertThat(PricingSnapshot.isCostStatus("UNKNOWN")).isTrue();
		assertThat(PricingSnapshot.isCostStatus(null)).isTrue();
		assertThat(PricingSnapshot.isCostStatus("PAID")).isFalse();
		assertThatThrownBy(() -> new PricingSnapshot(
			" ", "route-v1", "catalog", observedAt, "generation", null, null, "USD", null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void qualityGateBaselinePolicyIsVersioned() {
		assertThat(QualityGate.BASELINE_POLICY_VERSION).isEqualTo("baseline-v1");
		assertThat(new QualityGate() {
			@Override
			public QualityResult evaluate(
				com.aifashionvault.shared.GenerationAttemptId attemptId,
				byte[] applicationOwnedImage) {
				return null;
			}
		}.policyVersion()).isEqualTo("baseline-v1");
	}
}
