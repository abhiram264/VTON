package com.aifashionvault.generation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifashionvault.generation.vton.SubmissionKey;
import com.aifashionvault.shared.GenerationAttemptId;
import com.aifashionvault.shared.GenerationJobId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenerationEconomicsTest {

	private static final Instant OBSERVED_AT = Instant.parse("2026-08-22T00:00:00Z");
	private static final Instant STARTED_AT = Instant.parse("2026-08-22T00:00:01Z");
	private static final GenerationAttemptId ATTEMPT_ID = new GenerationAttemptId(
		UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
	private static final GenerationJobId JOB_ID = new GenerationJobId(
		UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));

	@Test
	void snapshotIncludesProviderRouteCatalogObservedBillingPriceCurrencyAndCeiling() {
		PricingSnapshot snapshot = catalog("0.04", "0.08");

		assertThat(snapshot.providerKey()).isEqualTo("example-provider");
		assertThat(snapshot.modelRouteAuditIdentity()).isEqualTo("route-v1");
		assertThat(snapshot.pricingCatalogVersion()).isEqualTo("catalog-2026-08");
		assertThat(snapshot.observedAt()).isEqualTo(OBSERVED_AT);
		assertThat(snapshot.billingUnit()).isEqualTo("generation");
		assertThat(snapshot.unitPrice()).isEqualByComparingTo("0.04");
		assertThat(snapshot.currency()).isEqualTo("USD");
		assertThat(snapshot.estimatedCostCeiling()).isEqualByComparingTo("0.08");
		assertThat(snapshot.estimatedCost()).isEqualByComparingTo("0.04");
	}

	@Test
	void bindPricingMarksEstimatedAndDoesNotAssumeZero() {
		PricingSnapshot snapshot = catalog("0.04", "0.08");
		GenerationAttempt bound = pendingAttempt().bindPricing(snapshot);

		assertThat(bound.costStatus()).isEqualTo(PricingSnapshot.COST_ESTIMATED);
		assertThat(bound.pricingCatalogVersion()).isEqualTo("catalog-2026-08");
		assertThat(bound.estimatedUnits()).isEqualByComparingTo("1");
		assertThat(bound.unitPrice()).isEqualByComparingTo("0.04");
		assertThat(bound.estimatedCostCeiling()).isEqualByComparingTo("0.08");
		assertThat(bound.estimatedCost()).isEqualByComparingTo("0.04");
		assertThat(bound.currency()).isEqualTo("USD");
		assertThat(bound.pricingObservedAt()).isEqualTo(OBSERVED_AT);
		assertThat(bound.billingUnit()).isEqualTo("generation");
		assertThat(bound.actualCost()).isNull();
	}

	@Test
	void laterCatalogPriceDoesNotMutateHistoricalSnapshotOrBoundAttempt() {
		PricingSnapshot snapshot = catalog("0.04", "0.08");
		GenerationAttempt bound = pendingAttempt().bindPricing(snapshot);
		PricingSnapshot laterCatalog = catalog("9.99", "19.98");

		assertThat(snapshot.unitPrice()).isEqualByComparingTo("0.04");
		assertThat(snapshot.estimatedCostCeiling()).isEqualByComparingTo("0.08");
		assertThat(snapshot.estimatedCost()).isEqualByComparingTo("0.04");
		assertThat(bound.unitPrice()).isEqualByComparingTo("0.04");
		assertThat(bound.estimatedCostCeiling()).isEqualByComparingTo("0.08");
		assertThat(bound.estimatedCost()).isEqualByComparingTo("0.04");
		assertThat(bound.pricingCatalogVersion()).isEqualTo("catalog-2026-08");
		assertThat(laterCatalog.unitPrice()).isEqualByComparingTo("9.99");
		assertThat(laterCatalog.estimatedCost()).isEqualByComparingTo("9.99");
	}

	@Test
	void settleFinalRecordsKnownActualCost() {
		GenerationAttempt settled = pendingAttempt()
			.bindPricing(catalog("0.04", "0.08"))
			.settleFinal(new BigDecimal("0.05"));

		assertThat(settled.costStatus()).isEqualTo(PricingSnapshot.COST_FINAL);
		assertThat(settled.actualCost()).isEqualByComparingTo("0.05");
		assertThat(settled.unitPrice()).isEqualByComparingTo("0.04");
		assertThat(settled.pricingObservedAt()).isEqualTo(OBSERVED_AT);
		assertThat(settled.billingUnit()).isEqualTo("generation");
		assertThat(settled.estimatedCost()).isEqualByComparingTo("0.04");
	}

	@Test
	void unknownRemoteBillingIsUnknownNeverAssumedZero() {
		GenerationAttempt unknown = pendingAttempt()
			.bindPricing(catalog("0.04", "0.08"))
			.markCostUnknown();

		assertThat(unknown.costStatus()).isEqualTo(PricingSnapshot.COST_UNKNOWN);
		assertThat(unknown.actualCost()).isNull();
		assertThat(unknown.actualBilledUnits()).isNull();
		assertThat(unknown.pricingObservedAt()).isEqualTo(OBSERVED_AT);
		assertThat(unknown.billingUnit()).isEqualTo("generation");
		assertThat(unknown.estimatedCost()).isEqualByComparingTo("0.04");
		assertThat(PricingSnapshot.isCostStatus(unknown.costStatus())).isTrue();
	}

	@Test
	void qualityRejectedAttemptRemainsInCostHistory() {
		GenerationAttempt rejected = pendingAttempt()
			.bindPricing(catalog("0.04", "0.08"))
			.settleFinal(new BigDecimal("0.04"));

		assertThat(rejected.costStatus()).isEqualTo(PricingSnapshot.COST_FINAL);
		assertThat(rejected.actualCost()).isEqualByComparingTo("0.04");
		assertThat(rejected.estimatedCost()).isEqualByComparingTo("0.04");
	}

	@Test
	void actualBilledUnitsRemainIndependentFromLegacyCredits() {
		GenerationAttempt attempt = new GenerationAttempt(
			ATTEMPT_ID,
			JOB_ID,
			"example-provider",
			"route-v1",
			null,
			1,
			STARTED_AT,
			null,
			null,
			new BigDecimal("7.5"),
			null,
			"USD",
			null,
			"SUCCESS",
			new SubmissionKey(UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")),
			null,
			false,
			null,
			null,
			null,
			0,
			null,
			null,
			null,
			"quality-v1",
			"catalog-2026-08",
			new BigDecimal("1"),
			new BigDecimal("0.04"),
			new BigDecimal("0.08"),
			null,
			PricingSnapshot.COST_ESTIMATED,
			new BigDecimal("3.25"),
			OBSERVED_AT,
			"generation");

		assertThat(attempt.creditsUsed()).isEqualByComparingTo("7.5");
		assertThat(attempt.actualBilledUnits()).isEqualByComparingTo("3.25");
		GenerationAttempt settled = attempt.settleFinal(new BigDecimal("0.13"));
		assertThat(settled.actualBilledUnits()).isEqualByComparingTo("3.25");
		assertThat(settled.costStatus()).isEqualTo(PricingSnapshot.COST_FINAL);
	}

	@Test
	void estimatedCostIsNullWhenUnitsOrPriceMissingNeverZero() {
		PricingSnapshot snapshot = new PricingSnapshot(
			"example-provider",
			"route-v1",
			"catalog-2026-08",
			OBSERVED_AT,
			"generation",
			null,
			null,
			"USD",
			null);

		assertThat(snapshot.estimatedCost()).isNull();
		assertThat(pendingAttempt().bindPricing(snapshot).estimatedCost()).isNull();
		assertThat(pendingAttempt().bindPricing(snapshot).actualCost()).isNull();
		assertThat(pendingAttempt().bindPricing(snapshot).costStatus()).isEqualTo(PricingSnapshot.COST_ESTIMATED);
	}

	@Test
	void settleFinalRequiresActualCost() {
		assertThatThrownBy(() -> pendingAttempt().bindPricing(catalog("0.04", "0.08")).settleFinal(null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void costStatusConstantsAreEstimatedFinalUnknown() {
		assertThat(PricingSnapshot.COST_ESTIMATED).isEqualTo("ESTIMATED");
		assertThat(PricingSnapshot.COST_FINAL).isEqualTo("FINAL");
		assertThat(PricingSnapshot.COST_UNKNOWN).isEqualTo("UNKNOWN");
		assertThat(PricingSnapshot.isCostStatus("ESTIMATED")).isTrue();
		assertThat(PricingSnapshot.isCostStatus("FINAL")).isTrue();
		assertThat(PricingSnapshot.isCostStatus("UNKNOWN")).isTrue();
		assertThat(PricingSnapshot.isCostStatus("ZERO")).isFalse();
	}

	private static PricingSnapshot catalog(String unitPrice, String ceiling) {
		return new PricingSnapshot(
			"example-provider",
			"route-v1",
			"catalog-2026-08",
			OBSERVED_AT,
			"generation",
			new BigDecimal("1"),
			new BigDecimal(unitPrice),
			"USD",
			new BigDecimal(ceiling));
	}

	private static GenerationAttempt pendingAttempt() {
		return new GenerationAttempt(
			ATTEMPT_ID,
			JOB_ID,
			"example-provider",
			null,
			null,
			1,
			STARTED_AT,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			new SubmissionKey(UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")),
			null,
			false,
			null,
			null);
	}
}
