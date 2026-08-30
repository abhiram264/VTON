package com.aifashionvault.generation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.aifashionvault.generation.vton.ProviderOutputRef;
import com.aifashionvault.generation.vton.SubmissionKey;
import com.aifashionvault.shared.GenerationAttemptId;
import com.aifashionvault.shared.GenerationJobId;
import com.aifashionvault.shared.StorageKey;

/**
 * One distinct intended provider execution for one render step (Q3).
 *
 * <p>The trailing {@code errorMappingContractVersion} freezes the adapter
 * error-mapping contract that classified the failure so historical decisions
 * are never reclassified by later mapper code (Q48). The trailing
 * {@code evaluatorRetryCount} bounds evaluator retries of the same candidate
 * at one (Q11).</p>
 */
public record GenerationAttempt(
	GenerationAttemptId id,
	GenerationJobId generationJobId,
	String provider,
	String model,
	String providerJobId,
	int attemptNumber,
	Instant startedAt,
	Instant completedAt,
	Integer latencyMs,
	BigDecimal creditsUsed,
	BigDecimal estimatedCost,
	String currency,
	String failureCode,
	String technicalOutcome,
	SubmissionKey submissionKey,
	Instant submitOwnedAt,
	boolean submissionIndeterminate,
	StorageKey tempStorageKey,
	ProviderOutputRef providerOutputRef,
	UUID renderStepId,
	int transmissionCount,
	Instant providerAcceptedAt,
	Instant executionDeadlineAt,
	Instant submissionRecoveryDeadlineAt,
	String qualityPolicyVersion,
	String pricingCatalogVersion,
	BigDecimal estimatedUnits,
	BigDecimal unitPrice,
	BigDecimal estimatedCostCeiling,
	BigDecimal actualCost,
	String costStatus,
	BigDecimal actualBilledUnits,
	Instant pricingObservedAt,
	String billingUnit,
	String errorMappingContractVersion,
	int evaluatorRetryCount) {

	public GenerationAttempt(
		GenerationAttemptId id,
		GenerationJobId generationJobId,
		String provider,
		String model,
		String providerJobId,
		int attemptNumber,
		Instant startedAt,
		Instant completedAt,
		Integer latencyMs,
		BigDecimal creditsUsed,
		BigDecimal estimatedCost,
		String currency,
		String failureCode,
		String technicalOutcome,
		SubmissionKey submissionKey,
		Instant submitOwnedAt,
		boolean submissionIndeterminate,
		StorageKey tempStorageKey,
		ProviderOutputRef providerOutputRef) {
		this(
			id,
			generationJobId,
			provider,
			model,
			providerJobId,
			attemptNumber,
			startedAt,
			completedAt,
			latencyMs,
			creditsUsed,
			estimatedCost,
			currency,
			failureCode,
			technicalOutcome,
			submissionKey,
			submitOwnedAt,
			submissionIndeterminate,
			tempStorageKey,
			providerOutputRef,
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
	}

	public GenerationAttempt(
		GenerationAttemptId id,
		GenerationJobId generationJobId,
		String provider,
		String model,
		String providerJobId,
		int attemptNumber,
		Instant startedAt,
		Instant completedAt,
		Integer latencyMs,
		BigDecimal creditsUsed,
		BigDecimal estimatedCost,
		String currency,
		String failureCode,
		String technicalOutcome,
		SubmissionKey submissionKey,
		Instant submitOwnedAt,
		boolean submissionIndeterminate,
		StorageKey tempStorageKey,
		ProviderOutputRef providerOutputRef,
		UUID renderStepId,
		int transmissionCount,
		Instant providerAcceptedAt,
		Instant executionDeadlineAt,
		Instant submissionRecoveryDeadlineAt,
		String qualityPolicyVersion,
		String pricingCatalogVersion,
		BigDecimal estimatedUnits,
		BigDecimal unitPrice,
		BigDecimal estimatedCostCeiling,
		BigDecimal actualCost,
		String costStatus) {
		this(
			id,
			generationJobId,
			provider,
			model,
			providerJobId,
			attemptNumber,
			startedAt,
			completedAt,
			latencyMs,
			creditsUsed,
			estimatedCost,
			currency,
			failureCode,
			technicalOutcome,
			submissionKey,
			submitOwnedAt,
			submissionIndeterminate,
			tempStorageKey,
			providerOutputRef,
			renderStepId,
			transmissionCount,
			providerAcceptedAt,
			executionDeadlineAt,
			submissionRecoveryDeadlineAt,
			qualityPolicyVersion,
			pricingCatalogVersion,
			estimatedUnits,
			unitPrice,
			estimatedCostCeiling,
			actualCost,
			costStatus,
			null,
			null,
			null,
			null,
			0);
	}

	/**
	 * Compatibility overload for the pre-retry-strategy persisted attempt
	 * shape. The error-mapping contract version (Q48) defaults to null and
	 * the evaluator retry count (Q11) defaults to zero.
	 */
	public GenerationAttempt(
		GenerationAttemptId id,
		GenerationJobId generationJobId,
		String provider,
		String model,
		String providerJobId,
		int attemptNumber,
		Instant startedAt,
		Instant completedAt,
		Integer latencyMs,
		BigDecimal creditsUsed,
		BigDecimal estimatedCost,
		String currency,
		String failureCode,
		String technicalOutcome,
		SubmissionKey submissionKey,
		Instant submitOwnedAt,
		boolean submissionIndeterminate,
		StorageKey tempStorageKey,
		ProviderOutputRef providerOutputRef,
		UUID renderStepId,
		int transmissionCount,
		Instant providerAcceptedAt,
		Instant executionDeadlineAt,
		Instant submissionRecoveryDeadlineAt,
		String qualityPolicyVersion,
		String pricingCatalogVersion,
		BigDecimal estimatedUnits,
		BigDecimal unitPrice,
		BigDecimal estimatedCostCeiling,
		BigDecimal actualCost,
		String costStatus,
		BigDecimal actualBilledUnits,
		Instant pricingObservedAt,
		String billingUnit) {
		this(
			id,
			generationJobId,
			provider,
			model,
			providerJobId,
			attemptNumber,
			startedAt,
			completedAt,
			latencyMs,
			creditsUsed,
			estimatedCost,
			currency,
			failureCode,
			technicalOutcome,
			submissionKey,
			submitOwnedAt,
			submissionIndeterminate,
			tempStorageKey,
			providerOutputRef,
			renderStepId,
			transmissionCount,
			providerAcceptedAt,
			executionDeadlineAt,
			submissionRecoveryDeadlineAt,
			qualityPolicyVersion,
			pricingCatalogVersion,
			estimatedUnits,
			unitPrice,
			estimatedCostCeiling,
			actualCost,
			costStatus,
			actualBilledUnits,
			pricingObservedAt,
			billingUnit,
			null,
			0);
	}

	public GenerationAttempt {
		if (id == null) {
			throw new IllegalArgumentException("id is required");
		}
		if (generationJobId == null) {
			throw new IllegalArgumentException("generationJobId is required");
		}
		if (provider == null || provider.isBlank()) {
			throw new IllegalArgumentException("provider is required");
		}
		if (attemptNumber < 1) {
			throw new IllegalArgumentException("attemptNumber must be >= 1");
		}
		if (startedAt == null) {
			throw new IllegalArgumentException("startedAt is required");
		}
		if (technicalOutcome != null
			&& !"SUCCESS".equals(technicalOutcome)
			&& !"FAILED".equals(technicalOutcome)) {
			throw new IllegalArgumentException("technicalOutcome must be SUCCESS, FAILED, or null");
		}
		if (transmissionCount < 0) {
			throw new IllegalArgumentException("transmissionCount must be >= 0");
		}
		if (transmissionCount > RetryPolicySnapshot.MAX_TRANSMISSIONS_PER_ATTEMPT) {
			throw new IllegalArgumentException(
				"transmissionCount must be <= " + RetryPolicySnapshot.MAX_TRANSMISSIONS_PER_ATTEMPT);
		}
		if (evaluatorRetryCount < 0
			|| evaluatorRetryCount > GenerationRetryBudget.MAX_EVALUATOR_RETRIES_PER_CANDIDATE) {
			throw new IllegalArgumentException(
				"evaluatorRetryCount must be between 0 and "
					+ GenerationRetryBudget.MAX_EVALUATOR_RETRIES_PER_CANDIDATE);
		}
		if (errorMappingContractVersion != null) {
			errorMappingContractVersion = errorMappingContractVersion.strip();
			if (errorMappingContractVersion.isEmpty()) {
				errorMappingContractVersion = null;
			} else if (!errorMappingContractVersion.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")) {
				throw new IllegalArgumentException(
					"errorMappingContractVersion must be a normalized contract token");
			}
		}
		if (!PricingSnapshot.isCostStatus(costStatus)) {
			throw new IllegalArgumentException("costStatus must be ESTIMATED, FINAL, UNKNOWN, or null");
		}
	}

	public boolean hasDurableProviderJobId() {
		return providerJobId != null && !providerJobId.isBlank();
	}

	public GenerationAttempt bindPricing(PricingSnapshot snapshot) {
		if (snapshot == null) {
			throw new IllegalArgumentException("snapshot is required");
		}
		BigDecimal estimate = snapshot.estimatedCost();
		if (estimate == null) {
			estimate = snapshot.estimatedCostCeiling();
		}
		return withEconomics(
			snapshot.pricingCatalogVersion(),
			snapshot.estimatedUnits(),
			snapshot.unitPrice(),
			snapshot.estimatedCostCeiling(),
			estimate,
			snapshot.currency(),
			actualCost,
			PricingSnapshot.COST_ESTIMATED,
			snapshot.observedAt(),
			snapshot.billingUnit());
	}

	public GenerationAttempt settleFinal(BigDecimal settledActualCost) {
		if (settledActualCost == null) {
			throw new IllegalArgumentException("actualCost is required");
		}
		return withEconomics(
			pricingCatalogVersion,
			estimatedUnits,
			unitPrice,
			estimatedCostCeiling,
			estimatedCost,
			currency,
			settledActualCost,
			PricingSnapshot.COST_FINAL,
			pricingObservedAt,
			billingUnit);
	}

	public GenerationAttempt markCostUnknown() {
		return withEconomics(
			pricingCatalogVersion,
			estimatedUnits,
			unitPrice,
			estimatedCostCeiling,
			estimatedCost,
			currency,
			actualCost,
			PricingSnapshot.COST_UNKNOWN,
			pricingObservedAt,
			billingUnit);
	}

	private GenerationAttempt withEconomics(
		String boundCatalogVersion,
		BigDecimal boundEstimatedUnits,
		BigDecimal boundUnitPrice,
		BigDecimal boundCeiling,
		BigDecimal boundEstimatedCost,
		String boundCurrency,
		BigDecimal boundActualCost,
		String boundCostStatus,
		Instant boundPricingObservedAt,
		String boundBillingUnit
	) {
		return new GenerationAttempt(
			id,
			generationJobId,
			provider,
			model,
			providerJobId,
			attemptNumber,
			startedAt,
			completedAt,
			latencyMs,
			creditsUsed,
			boundEstimatedCost,
			boundCurrency,
			failureCode,
			technicalOutcome,
			submissionKey,
			submitOwnedAt,
			submissionIndeterminate,
			tempStorageKey,
			providerOutputRef,
			renderStepId,
			transmissionCount,
			providerAcceptedAt,
			executionDeadlineAt,
			submissionRecoveryDeadlineAt,
			qualityPolicyVersion,
			boundCatalogVersion,
			boundEstimatedUnits,
			boundUnitPrice,
			boundCeiling,
			boundActualCost,
			boundCostStatus,
			actualBilledUnits,
			boundPricingObservedAt,
			boundBillingUnit,
			errorMappingContractVersion,
			evaluatorRetryCount);
	}
}
