package com.aifashionvault.platform.generation;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.aifashionvault.generation.GenerationJob;
import com.aifashionvault.generation.PricingSnapshot;
import com.aifashionvault.shared.QualityDecision;
import com.aifashionvault.shared.UserId;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenerationMetricsRedactionTest {

	private static final UUID USER_ID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
	private static final String SIGNED_URL =
		"https://example.invalid/look.jpg?X-Amz-Algorithm=AWS4&X-Amz-Signature=abc&Signature=abc";
	private static final String PROVIDER_BODY = "{\"image_base64\":\"/9j/secret-bytes\"}";
	private static final String CALLBACK_TOKEN = "callback-capability-token-secret";

	@Test
	void forbiddenFieldsAbsentFromMetersAndMethodSurface() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		GenerationMetrics metrics = new GenerationMetrics(registry);

		metrics.recordTransition(GenerationJob.QUEUED, GenerationJob.PREFLIGHT);
		metrics.recordDeadlineExpired();
		metrics.recordOutboxLag(Duration.ofSeconds(12));
		metrics.recordAmbiguity();
		metrics.recordQuality("baseline-v1", QualityDecision.PASS);
		metrics.recordQuality("baseline-v1", QualityDecision.RETRY);
		metrics.recordQuality("quality-v2", QualityDecision.FAIL, "IMAGE_INVALID");
		metrics.recordQuality("quality-v2", QualityDecision.PASS, null);
		metrics.recordEvaluatorUnavailable("EVALUATOR_UNAVAILABLE");
		metrics.recordCostStatus(PricingSnapshot.COST_ESTIMATED);
		metrics.recordCostStatus(PricingSnapshot.COST_FINAL);
		metrics.recordCostStatus(PricingSnapshot.COST_UNKNOWN);
		metrics.recordOrphanCleanup("temp", 1);
		metrics.recordOrphanCleanup("final", 1);
		metrics.recordDisposition(com.aifashionvault.generation.RetryDisposition.NEW_TECHNICAL_ATTEMPT);
		metrics.recordDisposition(com.aifashionvault.generation.RetryDisposition.NO_RETRY);
		metrics.recordExhaustionReason(com.aifashionvault.shared.TerminalReason.RETRY_BUDGET_EXHAUSTED);
		metrics.recordExhaustionReason(com.aifashionvault.shared.TerminalReason.COST_BUDGET_BLOCKED);
		metrics.recordCasConflict();
		metrics.recordEvaluatorRetry(false);
		metrics.recordEvaluatorRetry(true);
		metrics.recordStorageUnavailable();

		String dump = registry.getMeters().stream().map(this::render).collect(Collectors.joining("\n"));
		List<String> tagKeys = registry.getMeters().stream()
			.flatMap(meter -> meter.getId().getTags().stream())
			.map(Tag::getKey)
			.toList();

		assertThat(dump)
			.doesNotContain(USER_ID.toString())
			.doesNotContain(new UserId(USER_ID).value().toString())
			.doesNotContain(SIGNED_URL)
			.doesNotContain("http://")
			.doesNotContain("https://")
			.doesNotContain("X-Amz-")
			.doesNotContain("Signature=")
			.doesNotContain("data:image")
			.doesNotContain("/9j/")
			.doesNotContain("iVBORw0KGgo")
			.doesNotContain(PROVIDER_BODY)
			.doesNotContain(CALLBACK_TOKEN)
			.doesNotContain("secret-bytes");
		assertThat(tagKeys)
			.doesNotContain("user", "userId", "user_id", "url", "body", "token", "secret", "media");
		assertThat(GenerationMetrics.class.getDeclaredMethods())
			.extracting(Method::getParameterTypes)
			.flatExtracting(Arrays::asList)
			.doesNotContain(UserId.class, byte[].class);

		assertThat(registry.find(GenerationMetrics.TRANSITION).tag("from", GenerationJob.QUEUED)
			.tag("to", GenerationJob.PREFLIGHT).counter()).isNotNull();
		assertThat(registry.find(GenerationMetrics.DEADLINE_EXPIRED).counter()).isNotNull();
		assertThat(registry.find(GenerationMetrics.OUTBOX_LAG).gauge()).isNotNull();
		assertThat(registry.find(GenerationMetrics.AMBIGUITY).counter()).isNotNull();
		assertThat(registry.find(GenerationMetrics.QUALITY).tag("policy_version", "baseline-v1")
			.tag("decision", QualityDecision.PASS.name()).tag("category", "UNKNOWN").counter()).isNotNull();
		assertThat(registry.find(GenerationMetrics.QUALITY).tag("policy_version", "quality-v2")
			.tag("decision", QualityDecision.FAIL.name()).tag("reason_code", "IMAGE_INVALID")
			.tag("category", "UNKNOWN").counter()).isNotNull();
		assertThat(registry.find(GenerationMetrics.QUALITY).tag("policy_version", "quality-v2")
			.tag("decision", QualityDecision.PASS.name()).tag("reason_code", "none")
			.tag("category", "UNKNOWN").counter()).isNotNull();
		assertThat(registry.find(GenerationMetrics.QUALITY_EVALUATOR_UNAVAILABLE)
			.tag("reason_code", "EVALUATOR_UNAVAILABLE").counter()).isNotNull();
		assertThat(registry.find(GenerationMetrics.COST).tag("status", PricingSnapshot.COST_UNKNOWN).counter())
			.isNotNull();
		assertThat(registry.find(GenerationMetrics.ORPHAN_CLEANUP).tag("kind", "temp").counter()).isNotNull();
	}

	@Test
	void qualityMetricsExposeBoundedReasonsAndSeparateUnavailableCounter() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		GenerationMetrics metrics = new GenerationMetrics(registry);

		metrics.recordQuality("quality-v2", QualityDecision.RETRY, " image_invalid ");
		metrics.recordQuality("quality-v2", QualityDecision.PASS, null);
		metrics.recordQuality("quality-v2", QualityDecision.PASS, " ");
		for (String reasonCode : List.of(
			"IMAGE_INVALID",
			"IMAGE_TOO_SMALL",
			"PERSON_NOT_PRESERVED",
			"BODY_NOT_PRESERVED",
			"GARMENT_FIDELITY_LOW",
			"COLOR_FIDELITY_LOW",
			"ANATOMY_INVALID",
			"IDENTITY_NOT_PRESERVED",
			"PREVIOUS_ITEMS_NOT_PRESERVED",
			"ARTIFACT_DETECTED",
			"INPUT_MEDIA_MISSING",
			"none")) {
			metrics.recordQuality("quality-v2", QualityDecision.FAIL, reasonCode);
		}
		metrics.recordEvaluatorUnavailable("EVALUATOR_UNAVAILABLE");
		metrics.recordEvaluatorUnavailable("QUALITY_POLICY_UNSUPPORTED");

		assertThat(registry.find(GenerationMetrics.QUALITY)
			.tag("policy_version", "quality-v2")
			.tag("decision", QualityDecision.RETRY.name())
			.tag("reason_code", "IMAGE_INVALID").counter()).isNotNull();
		assertThat(registry.find(GenerationMetrics.QUALITY)
			.tag("policy_version", "quality-v2")
			.tag("decision", QualityDecision.PASS.name())
			.tag("reason_code", "none").counter().count()).isEqualTo(2);
		assertThat(registry.find(GenerationMetrics.QUALITY_EVALUATOR_UNAVAILABLE)
			.tag("reason_code", "EVALUATOR_UNAVAILABLE").counter().count()).isEqualTo(1);
		assertThat(registry.find(GenerationMetrics.QUALITY_EVALUATOR_UNAVAILABLE)
			.tag("reason_code", "QUALITY_POLICY_UNSUPPORTED").counter().count()).isEqualTo(1);
		assertThat(registry.getMeters().stream()
			.filter(meter -> GenerationMetrics.QUALITY_EVALUATOR_UNAVAILABLE.equals(meter.getId().getName()))
			.flatMap(meter -> meter.getId().getTags().stream())
			.map(Tag::getKey)
			.toList()).doesNotContain("decision");

		assertThatThrownBy(() -> metrics.recordQuality("quality-v2", QualityDecision.FAIL, "UNKNOWN_REASON"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> metrics.recordEvaluatorUnavailable("UNKNOWN_REASON"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void qualityMetricsExposeBoundedCategoriesAndRejectUnbounded() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		GenerationMetrics metrics = new GenerationMetrics(registry);

		for (String category : List.of(
			"TOP",
			"BOTTOM",
			"ONE_PIECE",
			"ETHNIC_FULL_BODY",
			"SAREE",
			"LEHENGA",
			"KURTA",
			"SHERWANI",
			"FOOTWEAR",
			"HEADWEAR",
			"BAG",
			"JEWELLERY",
			"ACCESSORY",
			"UNKNOWN")) {
			metrics.recordQuality("quality-v2", QualityDecision.PASS, "none", category);
		}
		metrics.recordQuality("quality-v2", QualityDecision.PASS, "none", " top ");
		metrics.recordQuality("quality-v2", QualityDecision.PASS, "none", (String) null);
		metrics.recordQuality("quality-v2", QualityDecision.PASS, "none", "   ");

		assertThat(registry.find(GenerationMetrics.QUALITY)
			.tag("policy_version", "quality-v2")
			.tag("decision", QualityDecision.PASS.name())
			.tag("category", "TOP").counter().count()).isEqualTo(2.0);
		assertThat(registry.find(GenerationMetrics.QUALITY)
			.tag("policy_version", "quality-v2")
			.tag("decision", QualityDecision.PASS.name())
			.tag("category", "UNKNOWN").counter().count()).isEqualTo(3.0);
		assertThat(registry.getMeters().stream()
			.filter(meter -> GenerationMetrics.QUALITY.equals(meter.getId().getName()))
			.allMatch(meter -> meter.getId().getTag("category") != null)).isTrue();

		assertThatThrownBy(() -> metrics.recordQuality("quality-v2", QualityDecision.FAIL, "IMAGE_INVALID", "unattributed"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> metrics.recordQuality("quality-v2", QualityDecision.FAIL, "IMAGE_INVALID", "UNKNOWN_CATEGORY"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> metrics.recordQuality("quality-v2", QualityDecision.FAIL, "IMAGE_INVALID", SIGNED_URL))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> metrics.recordQuality("quality-v2", QualityDecision.FAIL, "IMAGE_INVALID", USER_ID.toString()))
			.isInstanceOf(IllegalArgumentException.class);
	}


	@Test
	void retryMetricsExposeRequiredBoundedCategories() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		GenerationMetrics metrics = new GenerationMetrics(registry);

		metrics.recordLogicalAttempt(false);
		metrics.recordLogicalAttempt(true);
		metrics.recordAdditionalBudget(false);
		metrics.recordAdditionalBudget(true);
		metrics.recordQualityRetry(false);
		metrics.recordQualityRetry(true);
		metrics.recordReplaySafeRetransmission(false);
		metrics.recordReplaySafeRetransmission(true);
		metrics.recordProviderOutcome(GenerationMetrics.PROVIDER_TIMEOUT);
		metrics.recordProviderOutcome(GenerationMetrics.PROVIDER_RATE_LIMIT);
		metrics.recordProviderOutcome(GenerationMetrics.PROVIDER_INTERNAL);
		metrics.recordProviderOutcome(GenerationMetrics.PROVIDER_NON_RETRYABLE);
		metrics.recordProviderOutcome(GenerationMetrics.PROVIDER_UNKNOWN);
		metrics.recordFallback(false);
		metrics.recordFallback(true);
		metrics.recordRetryDelay(Duration.ofMillis(250));
		metrics.recordRetryAfterRejected(GenerationMetrics.RETRY_AFTER_CEILING);
		metrics.recordRetryAfterRejected(GenerationMetrics.RETRY_AFTER_DEADLINE);
		metrics.recordRecovery(GenerationMetrics.RECOVERY_SUBMISSION);
		metrics.recordRecovery(GenerationMetrics.RECOVERY_DEADLINE);
		metrics.recordRecovery(GenerationMetrics.EXECUTION_DEADLINE);
		metrics.recordRecovery(GenerationMetrics.ORCHESTRATION_DEADLINE);
		metrics.recordSuppression(GenerationMetrics.SUPPRESSION_STALE_WORKER);
		metrics.recordSuppression(GenerationMetrics.SUPPRESSION_DUPLICATE_CALLBACK_OR_POLL);
		metrics.recordSuppression(GenerationMetrics.SUPPRESSION_CANCELLED_OR_DELETED);
		metrics.recordAdmissionRejected(GenerationMetrics.ADMISSION_QUOTA);
		metrics.recordAdmissionRejected(GenerationMetrics.ADMISSION_CREDIT);
		metrics.recordAdmissionRejected(GenerationMetrics.ADMISSION_COST);
		metrics.recordAdmissionRejected(GenerationMetrics.ADMISSION_CIRCUIT);
		metrics.recordTerminal(GenerationMetrics.TERMINAL_COMPLETED);
		for (com.aifashionvault.generation.RetryDisposition disposition
			: com.aifashionvault.generation.RetryDisposition.values()) {
			metrics.recordDisposition(disposition);
		}
		metrics.recordExhaustionReason(com.aifashionvault.shared.TerminalReason.RETRY_BUDGET_EXHAUSTED);
		metrics.recordExhaustionReason(com.aifashionvault.shared.TerminalReason.COST_BUDGET_BLOCKED);
		metrics.recordExhaustionReason(com.aifashionvault.shared.TerminalReason.QUALITY_EVALUATOR_UNAVAILABLE);
		metrics.recordExhaustionReason(com.aifashionvault.shared.TerminalReason.STORAGE_UNAVAILABLE);
		metrics.recordCasConflict();
		metrics.recordEvaluatorRetry(false);
		metrics.recordEvaluatorRetry(true);
		metrics.recordStorageUnavailable();

		assertThat(registry.find(GenerationMetrics.LOGICAL_ATTEMPTS).tag("retry", "false").counter()).isNotNull();
		assertThat(registry.find(GenerationMetrics.LOGICAL_ATTEMPTS).tag("retry", "true").counter()).isNotNull();
		assertThat(registry.find(GenerationMetrics.ADDITIONAL_BUDGET).tag("exhausted", "true").counter())
			.isNotNull();
		assertThat(registry.find(GenerationMetrics.QUALITY_RETRIES).tag("exhausted", "false").counter())
			.isNotNull();
		assertThat(registry.find(GenerationMetrics.REPLAY_RETRANSMISSIONS).tag("cap_exhausted", "true").counter())
			.isNotNull();
		assertThat(registry.find(GenerationMetrics.PROVIDER_OUTCOMES)
			.tag("category", GenerationMetrics.PROVIDER_RATE_LIMIT).counter()).isNotNull();
		assertThat(registry.find(GenerationMetrics.FALLBACK).tag("success", "true").counter()).isNotNull();
		assertThat(registry.find(GenerationMetrics.RETRY_DELAY).timer()).isNotNull();
		assertThat(registry.find(GenerationMetrics.RETRY_AFTER_REJECTED)
			.tag("reason", GenerationMetrics.RETRY_AFTER_DEADLINE).counter()).isNotNull();
		assertThat(registry.find(GenerationMetrics.RECOVERY)
			.tag("kind", GenerationMetrics.EXECUTION_DEADLINE).counter()).isNotNull();
		assertThat(registry.find(GenerationMetrics.SUPPRESSION)
			.tag("kind", GenerationMetrics.SUPPRESSION_DUPLICATE_CALLBACK_OR_POLL).counter()).isNotNull();
		assertThat(registry.find(GenerationMetrics.ADMISSION_REJECTIONS)
			.tag("kind", GenerationMetrics.ADMISSION_CIRCUIT).counter()).isNotNull();
		assertThat(registry.find(GenerationMetrics.TERMINAL)
			.tag("category", GenerationMetrics.TERMINAL_COMPLETED).counter()).isNotNull();
		assertThat(registry.find(GenerationMetrics.RETRY_DELAY).timer().count()).isEqualTo(1);
		assertThat(registry.find(GenerationMetrics.DISPOSITIONS)
			.tag("disposition", "NEW_TECHNICAL_ATTEMPT").counter()).isNotNull();
		assertThat(registry.find(GenerationMetrics.DISPOSITIONS)
			.tag("disposition", "NO_RETRY").counter()).isNotNull();
		assertThat(registry.find(GenerationMetrics.DISPOSITIONS)
			.tag("disposition", "EVALUATOR_RETRY").counter()).isNotNull();
		assertThat(registry.find(GenerationMetrics.EXHAUSTION)
			.tag("reason", "RETRY_BUDGET_EXHAUSTED").counter()).isNotNull();
		assertThat(registry.find(GenerationMetrics.EXHAUSTION)
			.tag("reason", "COST_BUDGET_BLOCKED").counter()).isNotNull();
		assertThat(registry.find(GenerationMetrics.EXHAUSTION)
			.tag("reason", "QUALITY_EVALUATOR_UNAVAILABLE").counter()).isNotNull();
		assertThat(registry.find(GenerationMetrics.EXHAUSTION)
			.tag("reason", "STORAGE_UNAVAILABLE").counter()).isNotNull();
		assertThat(registry.find(GenerationMetrics.CAS_CONFLICTS).counter()).isNotNull();
		assertThat(registry.find(GenerationMetrics.EVALUATOR_RETRIES)
			.tag("exhausted", "false").counter()).isNotNull();
		assertThat(registry.find(GenerationMetrics.EVALUATOR_RETRIES)
			.tag("exhausted", "true").counter()).isNotNull();
		assertThat(registry.find(GenerationMetrics.STORAGE_UNAVAILABLE).counter()).isNotNull();
	}

	@Test
	void retryMetricLabelsRejectRawOrUnknownValues() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		GenerationMetrics metrics = new GenerationMetrics(registry);

		assertThatThrownBy(() -> metrics.recordProviderOutcome(SIGNED_URL))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> metrics.recordRetryAfterRejected(CALLBACK_TOKEN))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> metrics.recordRecovery(PROVIDER_BODY))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> metrics.recordSuppression(USER_ID.toString()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> metrics.recordTerminal(USER_ID.toString()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> metrics.recordRetryDelay(Duration.ofMillis(-1)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> metrics.recordQuality("quality-v2", QualityDecision.PASS, "none", SIGNED_URL))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> metrics.recordQuality("quality-v2", QualityDecision.PASS, "none", USER_ID.toString()))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void standaloneAliasMetersAbsentAndExistingTerminalQualityMetersRetained() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		GenerationMetrics metrics = new GenerationMetrics(registry);

		metrics.recordTerminal(GenerationMetrics.TERMINAL_COMPLETED);
		metrics.recordTerminal(GenerationMetrics.TERMINAL_FAILED);
		metrics.recordQuality("quality-v2", QualityDecision.RETRY, "IMAGE_INVALID");
		metrics.recordQuality("quality-v2", QualityDecision.FAIL, "IMAGE_TOO_SMALL");
		metrics.recordProviderAttempt("recording");
		metrics.recordGenerationCount();

		assertThat(registry.find("generation.success").meter()).isNull();
		assertThat(registry.find("generation.failure").meter()).isNull();
		assertThat(registry.find("generation.quality_reject").meter()).isNull();

		assertThat(registry.find(GenerationMetrics.TERMINAL).tag("category", GenerationMetrics.TERMINAL_COMPLETED).counter()).isNotNull();
		assertThat(registry.find(GenerationMetrics.TERMINAL).tag("category", GenerationMetrics.TERMINAL_FAILED).counter()).isNotNull();
		assertThat(registry.find(GenerationMetrics.QUALITY).tag("decision", "RETRY").counter()).isNotNull();
		assertThat(registry.find(GenerationMetrics.QUALITY).tag("decision", "FAIL").counter()).isNotNull();
		assertThat(registry.find(GenerationMetrics.PROVIDER_ATTEMPTS).tag("provider", "recording").counter()).isNotNull();
		assertThat(registry.find(GenerationMetrics.GENERATION_COUNT).counter()).isNotNull();

		assertThat(Arrays.stream(GenerationMetrics.class.getDeclaredFields()).map(java.lang.reflect.Field::getName))
			.doesNotContain("GENERATION_SUCCESS", "GENERATION_FAILURE", "GENERATION_QUALITY_REJECT");
		assertThat(Arrays.stream(GenerationMetrics.class.getDeclaredMethods()).map(Method::getName))
			.doesNotContain("recordGenerationSuccess", "recordGenerationFailure", "recordQualityReject");
	}

	private String render(Meter meter) {
		Meter.Id id = meter.getId();
		StringBuilder text = new StringBuilder(id.getName());
		id.getTags().forEach(tag -> text.append(' ').append(tag.getKey()).append('=').append(tag.getValue()));
		if (id.getDescription() != null) {
			text.append(' ').append(id.getDescription());
		}
		if (id.getBaseUnit() != null) {
			text.append(' ').append(id.getBaseUnit());
		}
		return text.toString();
	}

}

