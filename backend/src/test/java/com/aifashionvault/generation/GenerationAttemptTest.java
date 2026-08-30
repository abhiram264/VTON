package com.aifashionvault.generation;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifashionvault.generation.vton.ProviderOutputRef;
import com.aifashionvault.generation.vton.SubmissionKey;
import com.aifashionvault.shared.GenerationAttemptId;
import com.aifashionvault.shared.GenerationJobId;
import com.aifashionvault.shared.StorageKey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenerationAttemptTest {

	private static final GenerationAttemptId ID = new GenerationAttemptId(
		UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
	private static final GenerationJobId JOB_ID = new GenerationJobId(
		UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
	private static final Instant STARTED_AT = Instant.parse("2026-08-22T00:00:00Z");
	private static final SubmissionKey SUBMISSION_KEY = new SubmissionKey(
		UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"));
	private static final StorageKey TEMP_STORAGE_KEY = new StorageKey(
		"temp/user/generation/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa.jpg");
	private static final ProviderOutputRef PROVIDER_OUTPUT_REF = new ProviderOutputRef("provider-output-1");

	@Test
	void technicalSuccessConstructsIndependently() {
		GenerationAttempt attempt = new GenerationAttempt(
			ID,
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
			"SUCCESS",
			null,
			null,
			false,
			null,
			null);

		assertThat(attempt.id()).isEqualTo(ID);
		assertThat(attempt.generationJobId()).isEqualTo(JOB_ID);
		assertThat(attempt.provider()).isEqualTo("example-provider");
		assertThat(attempt.attemptNumber()).isEqualTo(1);
		assertThat(attempt.startedAt()).isEqualTo(STARTED_AT);
		assertThat(attempt.technicalOutcome()).isEqualTo("SUCCESS");
		assertThat(attempt.submissionIndeterminate()).isFalse();
		assertThat(attempt).isInstanceOf(GenerationAttempt.class);
	}

	@Test
	void hasNoQualityDecisionOrLookComponentOrMethod() {
		assertThat(GenerationAttempt.class.getRecordComponents())
			.extracting(component -> component.getType().getSimpleName())
			.doesNotContain("QualityDecision", "Look", "QualityResult");
		assertThat(GenerationAttempt.class.getDeclaredMethods())
			.extracting(Method::getReturnType)
			.extracting(Class::getSimpleName)
			.doesNotContain("QualityDecision", "Look", "QualityResult");
	}

	@Test
	void hasNoFashnTypes() {
		assertThat(GenerationAttempt.class.getSimpleName()).doesNotStartWith("Fashn");
		assertThat(GenerationAttempt.class.getRecordComponents())
			.extracting(component -> component.getType().getSimpleName())
			.noneMatch(name -> name.startsWith("Fashn"));
		assertThat(GenerationAttempt.class.getDeclaredMethods())
			.extracting(Method::getReturnType)
			.extracting(Class::getSimpleName)
			.noneMatch(name -> name.startsWith("Fashn"));
	}

	@Test
	void providerNeutralComponentShape() {
		assertThat(GenerationAttempt.class.getRecordComponents())
			.extracting(RecordComponent::getName)
			.containsExactly(
				"id",
				"generationJobId",
				"provider",
				"model",
				"providerJobId",
				"attemptNumber",
				"startedAt",
				"completedAt",
				"latencyMs",
				"creditsUsed",
				"estimatedCost",
				"currency",
				"failureCode",
				"technicalOutcome",
				"submissionKey",
				"submitOwnedAt",
				"submissionIndeterminate",
				"tempStorageKey",
				"providerOutputRef",
				"renderStepId",
				"transmissionCount",
				"providerAcceptedAt",
				"executionDeadlineAt",
				"submissionRecoveryDeadlineAt",
				"qualityPolicyVersion",
				"pricingCatalogVersion",
				"estimatedUnits",
				"unitPrice",
				"estimatedCostCeiling",
				"actualCost",
				"costStatus",
				"actualBilledUnits",
				"pricingObservedAt",
				"billingUnit",
				"errorMappingContractVersion",
				"evaluatorRetryCount");
		assertThat(GenerationAttempt.class.getRecordComponents())
			.extracting(RecordComponent::getType)
			.containsExactly(
				GenerationAttemptId.class,
				GenerationJobId.class,
				String.class,
				String.class,
				String.class,
				int.class,
				Instant.class,
				Instant.class,
				Integer.class,
				BigDecimal.class,
				BigDecimal.class,
				String.class,
				String.class,
				String.class,
				SubmissionKey.class,
				Instant.class,
				boolean.class,
				StorageKey.class,
				ProviderOutputRef.class,
				java.util.UUID.class,
				int.class,
				Instant.class,
				Instant.class,
				Instant.class,
				String.class,
				String.class,
				BigDecimal.class,
				BigDecimal.class,
				BigDecimal.class,
				BigDecimal.class,
				String.class,
				BigDecimal.class,
				Instant.class,
				String.class,
				String.class,
				int.class);
	}

	@Test
	void rejectsAttemptNumberZero() {
		assertThatThrownBy(() -> attempt(0, "SUCCESS"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsAttemptNumberBelowOne() {
		assertThatThrownBy(() -> attempt(-1, "FAILED"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void failedAndNullTechnicalOutcomeConstruct() {
		assertThat(attempt(1, "FAILED").technicalOutcome()).isEqualTo("FAILED");
		assertThat(attempt(2, null).technicalOutcome()).isNull();
	}

	@Test
	void rejectsUnknownTechnicalOutcome() {
		assertThatThrownBy(() -> attempt(1, "PASS"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void transmissionCountAllowsZeroThroughThreeOnly() {
		for (int transmissionCount = 0; transmissionCount <= 3; transmissionCount++) {
			assertThat(attemptWithTransmissionCount(transmissionCount).transmissionCount())
				.isEqualTo(transmissionCount);
		}
		assertThatThrownBy(() -> attemptWithTransmissionCount(4))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private static GenerationAttempt attemptWithTransmissionCount(int transmissionCount) {
		return new GenerationAttempt(
			ID,
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
			null,
			null,
			false,
			null,
			null,
			null,
			transmissionCount,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null);
	}

	@Test
	void requiresIdJobProviderAndStartedAt() {
		assertThatThrownBy(() -> new GenerationAttempt(
			null, JOB_ID, "example-provider", null, null, 1, STARTED_AT, null, null, null, null, null, null, null,
			null, null, false, null, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new GenerationAttempt(
			ID, null, "example-provider", null, null, 1, STARTED_AT, null, null, null, null, null, null, null,
			null, null, false, null, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new GenerationAttempt(
			ID, JOB_ID, null, null, null, 1, STARTED_AT, null, null, null, null, null, null, null,
			null, null, false, null, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new GenerationAttempt(
			ID, JOB_ID, "   ", null, null, 1, STARTED_AT, null, null, null, null, null, null, null,
			null, null, false, null, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new GenerationAttempt(
			ID, JOB_ID, "example-provider", null, null, 1, null, null, null, null, null, null, null, null,
			null, null, false, null, null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void optionalFieldsMayBePopulated() {
		Instant completedAt = Instant.parse("2026-08-22T00:00:01Z");
		Instant submitOwnedAt = Instant.parse("2026-08-22T00:00:00.500Z");
		GenerationAttempt attempt = new GenerationAttempt(
			ID,
			JOB_ID,
			"example-provider",
			"example-model",
			"provider-job-1",
			1,
			STARTED_AT,
			completedAt,
			1000,
			new BigDecimal("1.5"),
			new BigDecimal("0.02"),
			"USD",
			null,
			"SUCCESS",
			SUBMISSION_KEY,
			submitOwnedAt,
			false,
			TEMP_STORAGE_KEY,
			PROVIDER_OUTPUT_REF);

		assertThat(attempt.model()).isEqualTo("example-model");
		assertThat(attempt.providerJobId()).isEqualTo("provider-job-1");
		assertThat(attempt.completedAt()).isEqualTo(completedAt);
		assertThat(attempt.latencyMs()).isEqualTo(1000);
		assertThat(attempt.creditsUsed()).isEqualByComparingTo("1.5");
		assertThat(attempt.estimatedCost()).isEqualByComparingTo("0.02");
		assertThat(attempt.currency()).isEqualTo("USD");
		assertThat(attempt.failureCode()).isNull();
		assertThat(attempt.submissionKey()).isEqualTo(SUBMISSION_KEY);
		assertThat(attempt.submitOwnedAt()).isEqualTo(submitOwnedAt);
		assertThat(attempt.submissionIndeterminate()).isFalse();
		assertThat(attempt.tempStorageKey()).isEqualTo(TEMP_STORAGE_KEY);
		assertThat(attempt.providerOutputRef()).isEqualTo(PROVIDER_OUTPUT_REF);
	}

	@Test
	void recordsQualityRejectedSpendWithoutLookTypes() {
		Instant completedAt = Instant.parse("2026-08-22T00:00:01Z");
		GenerationAttempt attempt = new GenerationAttempt(
			ID,
			JOB_ID,
			"example-provider",
			"example-model",
			"provider-job-1",
			2,
			STARTED_AT,
			completedAt,
			1800,
			new BigDecimal("2.0"),
			new BigDecimal("0.04"),
			"USD",
			"QUALITY_REJECTED",
			"SUCCESS",
			SUBMISSION_KEY,
			STARTED_AT,
			false,
			TEMP_STORAGE_KEY,
			PROVIDER_OUTPUT_REF);

		assertThat(attempt.attemptNumber()).isEqualTo(2);
		assertThat(attempt.creditsUsed()).isEqualByComparingTo("2.0");
		assertThat(attempt.estimatedCost()).isEqualByComparingTo("0.04");
		assertThat(attempt.currency()).isEqualTo("USD");
		assertThat(attempt.failureCode()).isEqualTo("QUALITY_REJECTED");
		assertThat(attempt.technicalOutcome()).isEqualTo("SUCCESS");
		assertThat(attempt.tempStorageKey()).isEqualTo(TEMP_STORAGE_KEY);
		assertThat(attempt.providerOutputRef()).isEqualTo(PROVIDER_OUTPUT_REF);
		assertThat(GenerationAttempt.class.getRecordComponents())
			.extracting(component -> component.getType().getSimpleName())
			.doesNotContain("QualityDecision", "Look", "QualityResult");
	}

	@Test
	void retryBudgetDefaultIsThree() {
		assertThat(GenerationRetryBudget.DEFAULT_MAX_PAID_ATTEMPTS).isEqualTo(3);
		assertThat(new GenerationRetryBudget(GenerationRetryBudget.DEFAULT_MAX_PAID_ATTEMPTS).maxPaidAttempts())
			.isEqualTo(3);
	}

	@Test
	void retryBudgetRejectsZero() {
		assertThatThrownBy(() -> new GenerationRetryBudget(0))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void retryBudgetRejectsBelowOne() {
		assertThatThrownBy(() -> new GenerationRetryBudget(-1))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void compatibilityConstructorDefaultsNewFields() {
		GenerationAttempt attempt = attempt(1, "SUCCESS");
		assertThat(attempt.renderStepId()).isNull();
		assertThat(attempt.transmissionCount()).isZero();
		assertThat(attempt.costStatus()).isNull();
		assertThat(attempt.actualBilledUnits()).isNull();
		assertThat(attempt.pricingObservedAt()).isNull();
		assertThat(attempt.billingUnit()).isNull();
		assertThat(attempt.errorMappingContractVersion()).isNull();
		assertThat(attempt.evaluatorRetryCount()).isZero();
		assertThat(attempt.hasDurableProviderJobId()).isFalse();
	}

	@Test
	void expandedCompatibilityConstructorDefaultsTrailingEconomicsFields() {
		GenerationAttempt attempt = new GenerationAttempt(
			ID,
			JOB_ID,
			"example-provider",
			"route-v1",
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
			null,
			null,
			false,
			null,
			null,
			null,
			0,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null);

		assertThat(attempt.actualBilledUnits()).isNull();
		assertThat(attempt.pricingObservedAt()).isNull();
		assertThat(attempt.billingUnit()).isNull();
	}

	@Test
	void retryBudgetForStepCountIsBasePlusTwo() {
		assertThat(GenerationRetryBudget.forStepCount(1).maxPaidAttempts()).isEqualTo(3);
		assertThat(GenerationRetryBudget.forStepCount(2).maxPaidAttempts()).isEqualTo(4);
	}

	@Test
	void rejectsUnknownCostStatus() {
		assertThatThrownBy(() -> new GenerationAttempt(
			ID, JOB_ID, "example-provider", null, null, 1, STARTED_AT, null, null, null, null, null, null, null,
			null, null, false, null, null, null, 0, null, null, null, null, null, null, null, null, null, "PAID"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void evaluatorRetryCountAllowsZeroAndOneOnly() {
		assertThat(attemptWithPolicy(null, 0).evaluatorRetryCount()).isZero();
		assertThat(attemptWithPolicy(null, 1).evaluatorRetryCount()).isEqualTo(1);
		assertThatThrownBy(() -> attemptWithPolicy(null, 2))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> attemptWithPolicy(null, -1))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void errorMappingContractVersionNormalizesAndValidatesToken() {
		assertThat(attemptWithPolicy("fashn-error-mapping-v1", 0).errorMappingContractVersion())
			.isEqualTo("fashn-error-mapping-v1");
		assertThat(attemptWithPolicy("  v2.0:adapter-1  ", 0).errorMappingContractVersion())
			.isEqualTo("v2.0:adapter-1");
		assertThat(attemptWithPolicy("   ", 0).errorMappingContractVersion()).isNull();
		assertThatThrownBy(() -> attemptWithPolicy("contains space", 0))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> attemptWithPolicy("a".repeat(129), 0))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void preStrategyCompatibilityConstructorDefaultsPolicyFields() {
		GenerationAttempt attempt = new GenerationAttempt(
			ID,
			JOB_ID,
			"example-provider",
			"route-v1",
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
			SUBMISSION_KEY,
			null,
			false,
			null,
			null,
			null,
			0,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null);

		assertThat(attempt.errorMappingContractVersion()).isNull();
		assertThat(attempt.evaluatorRetryCount()).isZero();
	}

	private static GenerationAttempt attemptWithPolicy(String errorMappingContractVersion, int evaluatorRetryCount) {
		return new GenerationAttempt(
			ID,
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
			SUBMISSION_KEY,
			null,
			false,
			null,
			null,
			null,
			0,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			errorMappingContractVersion,
			evaluatorRetryCount);
	}

	private static GenerationAttempt attempt(int attemptNumber, String technicalOutcome) {
		return new GenerationAttempt(
			ID,
			JOB_ID,
			"example-provider",
			null,
			null,
			attemptNumber,
			STARTED_AT,
			null,
			null,
			null,
			null,
			null,
			null,
			technicalOutcome,
			null,
			null,
			false,
			null,
			null);
	}
}
