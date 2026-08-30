package com.aifashionvault.generation;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifashionvault.shared.GenerationAttemptId;
import com.aifashionvault.shared.QualityDecision;
import com.aifashionvault.shared.QualityResultId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QualityResultTest {

	private static final QualityResultId ID = new QualityResultId(
		UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
	private static final GenerationAttemptId ATTEMPT_ID = new GenerationAttemptId(
		UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
	private static final Instant CREATED_AT = Instant.parse("2026-08-22T00:00:00Z");

	@Test
	void componentShapeMatchesQualityResultTable() {
		assertThat(QualityResult.class.getRecordComponents())
			.extracting(RecordComponent::getName)
			.containsExactly(
				"id",
				"generationAttemptId",
				"decision",
				"identityPreservation",
				"garmentFidelity",
				"colorFidelity",
				"anatomyValid",
				"artifactDetected",
				"createdAt",
				"qualityPolicyVersion",
				"reasonCode");
		assertThat(QualityResult.class.getRecordComponents())
			.extracting(RecordComponent::getType)
			.containsExactly(
				QualityResultId.class,
				GenerationAttemptId.class,
				QualityDecision.class,
				BigDecimal.class,
				BigDecimal.class,
				BigDecimal.class,
				Boolean.class,
				Boolean.class,
				Instant.class,
				String.class,
				String.class);
	}

	@Test
	void constructsPassRetryAndFailWhenDecisionIsExplicit() {
		assertThat(result(QualityDecision.PASS).decision()).isEqualTo(QualityDecision.PASS);
		assertThat(result(QualityDecision.RETRY).decision()).isEqualTo(QualityDecision.RETRY);
		assertThat(result(QualityDecision.FAIL).decision()).isEqualTo(QualityDecision.FAIL);
	}


	@Test
	void hasNoConstructorOrFactoryThatAcceptsTechnicalOutcome() {
		assertThat(QualityResult.class.getRecordComponents())
			.extracting(RecordComponent::getName)
			.doesNotContain("technicalOutcome", "technical_outcome");
		assertThat(QualityResult.class.getDeclaredConstructors())
			.allSatisfy(constructor -> {
				Class<?>[] types = constructor.getParameterTypes();
				int firstTrailingString = types[types.length - 1] == boolean.class
					? types.length - 3
					: types.length - 2;
				int strings = 0;
				for (int i = 0; i < types.length; i++) {
					if (types[i] == String.class) {
						strings++;
						assertThat(i).isGreaterThanOrEqualTo(firstTrailingString);
					}
				}
				assertThat(strings).isLessThanOrEqualTo(2);
			});
		assertThat(Arrays.stream(QualityResult.class.getDeclaredMethods())
			.filter(method -> Modifier.isStatic(method.getModifiers())))
			.noneMatch(QualityResultTest::acceptsString);
	}

	@Test
	void doesNotReferenceLook() {
		assertThat(QualityResult.class.getRecordComponents())
			.extracting(RecordComponent::getName)
			.doesNotContain("look", "lookId");
		assertThat(QualityResult.class.getRecordComponents())
			.extracting(component -> component.getType().getSimpleName())
			.noneMatch(name -> name.contains("Look"));
		assertThat(QualityResult.class.getPackageName()).isEqualTo("com.aifashionvault.generation");
	}

	@Test
	void requiredFieldsRejectNull() {
		assertThatThrownBy(() -> new QualityResult(
			null, ATTEMPT_ID, QualityDecision.PASS, null, null, null, null, null, CREATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new QualityResult(
			ID, null, QualityDecision.PASS, null, null, null, null, null, CREATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new QualityResult(
			ID, ATTEMPT_ID, null, null, null, null, null, null, CREATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new QualityResult(
			ID, ATTEMPT_ID, QualityDecision.PASS, null, null, null, null, null, null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void scoresAndFlagsMayBeNull() {
		QualityResult qualityResult = result(QualityDecision.RETRY);

		assertThat(qualityResult.id()).isEqualTo(ID);
		assertThat(qualityResult.generationAttemptId()).isEqualTo(ATTEMPT_ID);
		assertThat(qualityResult.identityPreservation()).isNull();
		assertThat(qualityResult.garmentFidelity()).isNull();
		assertThat(qualityResult.colorFidelity()).isNull();
		assertThat(qualityResult.anatomyValid()).isNull();
		assertThat(qualityResult.artifactDetected()).isNull();
		assertThat(qualityResult.createdAt()).isEqualTo(CREATED_AT);
		assertThat(qualityResult.qualityPolicyVersion()).isEqualTo(QualityGate.BASELINE_POLICY_VERSION);
		assertThat(qualityResult.reasonCode()).isNull();
	}

	@Test
	void scoresAndFlagsMayBeSet() {
		QualityResult qualityResult = new QualityResult(
			ID,
			ATTEMPT_ID,
			QualityDecision.PASS,
			new BigDecimal("0.94"),
			new BigDecimal("0.88"),
			new BigDecimal("0.91"),
			Boolean.TRUE,
			Boolean.FALSE,
			CREATED_AT);

		assertThat(qualityResult.identityPreservation()).isEqualByComparingTo("0.94");
		assertThat(qualityResult.garmentFidelity()).isEqualByComparingTo("0.88");
		assertThat(qualityResult.colorFidelity()).isEqualByComparingTo("0.91");
		assertThat(qualityResult.anatomyValid()).isTrue();
		assertThat(qualityResult.artifactDetected()).isFalse();
	}

	@Test
	void compatibilityConstructorBindsBaselinePolicyVersion() {
		QualityResult qualityResult = result(QualityDecision.PASS);

		assertThat(qualityResult.qualityPolicyVersion()).isEqualTo(QualityGate.BASELINE_POLICY_VERSION);
		assertThat(qualityResult.qualityPolicyVersion()).isEqualTo("baseline-v1");
		assertThat(qualityResult.bindPolicyVersion(null).qualityPolicyVersion()).isEqualTo("baseline-v1");
		assertThat(qualityResult.bindPolicyVersion(" ").qualityPolicyVersion()).isEqualTo("baseline-v1");
		assertThat(qualityResult.bindPolicyVersion("baseline-v1")).isSameAs(qualityResult);
	}

	@Test
	void redeliveryReusesAlreadyBoundPolicyVersion() {
		QualityResult bound = new QualityResult(
			ID,
			ATTEMPT_ID,
			QualityDecision.PASS,
			null,
			null,
			null,
			null,
			null,
			CREATED_AT,
			"baseline-v1");

		assertThat(bound.bindPolicyVersion("baseline-v1").qualityPolicyVersion()).isEqualTo("baseline-v1");
		assertThatThrownBy(() -> bound.bindPolicyVersion("future-v2"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("QualityPolicyVersion");
	}

	@Test
	void blankPolicyVersionDefaultsToBaseline() {
		QualityResult qualityResult = new QualityResult(
			ID, ATTEMPT_ID, QualityDecision.FAIL, null, null, null, null, null, CREATED_AT, null);

		assertThat(qualityResult.qualityPolicyVersion()).isEqualTo("baseline-v1");
	}

	@Test
	void canonicalConstructorRetainsMissingPolicyVersionAndNormalizesReasonCode() {
		QualityResult qualityResult = new QualityResult(
			ID,
			ATTEMPT_ID,
			QualityDecision.FAIL,
			null,
			null,
			null,
			null,
			null,
			CREATED_AT,
			null,
			"  low_identity_similarity  ");

		assertThat(qualityResult.qualityPolicyVersion()).isNull();
		assertThat(qualityResult.reasonCode()).isEqualTo("LOW_IDENTITY_SIMILARITY");
	}

	@Test
	void reasonCodeIsOptionalAndBlankValuesNormalizeToNull() {
		QualityResult blank = new QualityResult(
			ID,
			ATTEMPT_ID,
			QualityDecision.RETRY,
			null,
			null,
			null,
			null,
			null,
			CREATED_AT,
			"quality-v2",
			" \t ");

		assertThat(blank.reasonCode()).isNull();
		assertThat(blank.qualityPolicyVersion()).isEqualTo("quality-v2");
	}

	@Test
	void reasonCodeRejectsUnboundedOrFreeFormText() {
		assertThatThrownBy(() -> new QualityResult(
			ID,
			ATTEMPT_ID,
			QualityDecision.FAIL,
			null,
			null,
			null,
			null,
			null,
			CREATED_AT,
			"quality-v2",
			"provider response: https://provider.example/result"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("reasonCode");
	}

	private static QualityResult result(QualityDecision decision) {
		return new QualityResult(ID, ATTEMPT_ID, decision, null, null, null, null, null, CREATED_AT);
	}

	private static boolean acceptsString(Method method) {
		return Arrays.asList(method.getParameterTypes()).contains(String.class);
	}
}
