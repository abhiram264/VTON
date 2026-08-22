package com.aifashionvault.generation;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifashionvault.shared.GenerationAttemptId;
import com.aifashionvault.shared.GenerationJobId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenerationAttemptTest {

	private static final GenerationAttemptId ID = new GenerationAttemptId(
		UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
	private static final GenerationJobId JOB_ID = new GenerationJobId(
		UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
	private static final Instant STARTED_AT = Instant.parse("2026-08-22T00:00:00Z");

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
			"SUCCESS");

		assertThat(attempt.id()).isEqualTo(ID);
		assertThat(attempt.generationJobId()).isEqualTo(JOB_ID);
		assertThat(attempt.provider()).isEqualTo("example-provider");
		assertThat(attempt.attemptNumber()).isEqualTo(1);
		assertThat(attempt.startedAt()).isEqualTo(STARTED_AT);
		assertThat(attempt.technicalOutcome()).isEqualTo("SUCCESS");
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
				"technicalOutcome");
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
				String.class);
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
	void requiresIdJobProviderAndStartedAt() {
		assertThatThrownBy(() -> new GenerationAttempt(
			null, JOB_ID, "example-provider", null, null, 1, STARTED_AT, null, null, null, null, null, null, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new GenerationAttempt(
			ID, null, "example-provider", null, null, 1, STARTED_AT, null, null, null, null, null, null, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new GenerationAttempt(
			ID, JOB_ID, null, null, null, 1, STARTED_AT, null, null, null, null, null, null, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new GenerationAttempt(
			ID, JOB_ID, "   ", null, null, 1, STARTED_AT, null, null, null, null, null, null, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new GenerationAttempt(
			ID, JOB_ID, "example-provider", null, null, 1, null, null, null, null, null, null, null, null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void optionalFieldsMayBePopulated() {
		Instant completedAt = Instant.parse("2026-08-22T00:00:01Z");
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
			"SUCCESS");

		assertThat(attempt.model()).isEqualTo("example-model");
		assertThat(attempt.providerJobId()).isEqualTo("provider-job-1");
		assertThat(attempt.completedAt()).isEqualTo(completedAt);
		assertThat(attempt.latencyMs()).isEqualTo(1000);
		assertThat(attempt.creditsUsed()).isEqualByComparingTo("1.5");
		assertThat(attempt.estimatedCost()).isEqualByComparingTo("0.02");
		assertThat(attempt.currency()).isEqualTo("USD");
		assertThat(attempt.failureCode()).isNull();
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
			technicalOutcome);
	}
}
