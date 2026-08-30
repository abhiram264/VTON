package com.aifashionvault.generation;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryPolicySnapshotTest {

	@Test
	void factoryResolvesFinitePolicyForOneAndManySteps() {
		RetryPolicySnapshot oneStep = RetryPolicySnapshot.forStepCount(1);
		RetryPolicySnapshot fourSteps = RetryPolicySnapshot.forStepCount(4);

		assertThat(oneStep.maxPaidAttempts()).isEqualTo(3);
		assertThat(fourSteps.maxPaidAttempts()).isEqualTo(6);
		assertThat(oneStep.additionalLogicalAttempts()).isEqualTo(2);
		assertThat(oneStep.maxQualityRetriesPerStep()).isEqualTo(1);
		assertThat(oneStep.maxReplaySafeRetransmits()).isEqualTo(2);
		assertThat(oneStep.maxTransmissionsPerAttempt()).isEqualTo(3);
		assertThat(oneStep.backoffBase()).isEqualTo(Duration.ofSeconds(1));
		assertThat(oneStep.computedBackoffCap()).isEqualTo(Duration.ofSeconds(30));
		assertThat(oneStep.maxAutonomousWait()).isEqualTo(Duration.ofMinutes(5));
		assertThat(oneStep.orchestrationDeadline()).isEqualTo(Duration.ofMinutes(30));
		assertThat(oneStep.technicalRetryEnabled()).isTrue();
		assertThat(oneStep.fallbackEnabled()).isFalse();
		assertThat(oneStep.qualityRegenerationEnabled()).isTrue();
		assertThat(oneStep.providerTimingPolicyId()).isEqualTo("default");
	}

	@Test
	void rolloutFlagsMayDisableFeaturesWithoutChangingCeilings() {
		RetryPolicySnapshot disabled = RetryPolicySnapshot.forStepCount(2, false, true, false);

		assertThat(disabled.maxPaidAttempts()).isEqualTo(4);
		assertThat(disabled.additionalLogicalAttempts()).isEqualTo(2);
		assertThat(disabled.maxQualityRetriesPerStep()).isEqualTo(1);
		assertThat(disabled.maxReplaySafeRetransmits()).isEqualTo(2);
		assertThat(disabled.maxTransmissionsPerAttempt()).isEqualTo(3);
		assertThat(disabled.technicalRetryEnabled()).isFalse();
		assertThat(disabled.fallbackEnabled()).isTrue();
		assertThat(disabled.qualityRegenerationEnabled()).isFalse();
	}

	@Test
	void rejectsInvalidStepCountsAndInconsistentLogicalBudget() {
		assertThatThrownBy(() -> RetryPolicySnapshot.forStepCount(0))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> RetryPolicySnapshot.forStepCount(Integer.MAX_VALUE))
			.isInstanceOf(IllegalArgumentException.class);

		assertThatThrownBy(() -> new RetryPolicySnapshot(
			2,
			2,
			1,
			2,
			3,
			Duration.ofSeconds(1),
			Duration.ofSeconds(30),
			Duration.ofMinutes(5),
			Duration.ofMinutes(30),
			true,
			false,
			true,
			"default"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsValuesAboveSettledCeilings() {
		RetryPolicySnapshot policy = RetryPolicySnapshot.forStepCount(1);

		assertThatThrownBy(() -> new RetryPolicySnapshot(
			policy.maxPaidAttempts(),
			3,
			policy.maxQualityRetriesPerStep(),
			policy.maxReplaySafeRetransmits(),
			policy.maxTransmissionsPerAttempt(),
			policy.backoffBase(),
			policy.computedBackoffCap(),
			policy.maxAutonomousWait(),
			policy.orchestrationDeadline(),
			true,
			false,
			true,
			"default"))
			.isInstanceOf(IllegalArgumentException.class);

		assertThatThrownBy(() -> new RetryPolicySnapshot(
			policy.maxPaidAttempts(),
			policy.additionalLogicalAttempts(),
			2,
			policy.maxReplaySafeRetransmits(),
			policy.maxTransmissionsPerAttempt(),
			policy.backoffBase(),
			policy.computedBackoffCap(),
			policy.maxAutonomousWait(),
			policy.orchestrationDeadline(),
			true,
			false,
			true,
			"default"))
			.isInstanceOf(IllegalArgumentException.class);

		assertThatThrownBy(() -> new RetryPolicySnapshot(
			policy.maxPaidAttempts(),
			policy.additionalLogicalAttempts(),
			policy.maxQualityRetriesPerStep(),
			3,
			policy.maxTransmissionsPerAttempt(),
			policy.backoffBase(),
			policy.computedBackoffCap(),
			policy.maxAutonomousWait(),
			policy.orchestrationDeadline(),
			true,
			false,
			true,
			"default"))
			.isInstanceOf(IllegalArgumentException.class);

		assertThatThrownBy(() -> new RetryPolicySnapshot(
			policy.maxPaidAttempts(),
			policy.additionalLogicalAttempts(),
			policy.maxQualityRetriesPerStep(),
			policy.maxReplaySafeRetransmits(),
			4,
			policy.backoffBase(),
			policy.computedBackoffCap(),
			policy.maxAutonomousWait(),
			policy.orchestrationDeadline(),
			true,
			false,
			true,
			"default"))
			.isInstanceOf(IllegalArgumentException.class);

		assertThatThrownBy(() -> new RetryPolicySnapshot(
			policy.maxPaidAttempts(),
			policy.additionalLogicalAttempts(),
			policy.maxQualityRetriesPerStep(),
			policy.maxReplaySafeRetransmits(),
			policy.maxTransmissionsPerAttempt(),
			Duration.ofSeconds(2),
			policy.computedBackoffCap(),
			policy.maxAutonomousWait(),
			policy.orchestrationDeadline(),
			true,
			false,
			true,
			"default"))
			.isInstanceOf(IllegalArgumentException.class);

		assertThatThrownBy(() -> new RetryPolicySnapshot(
			policy.maxPaidAttempts(),
			policy.additionalLogicalAttempts(),
			policy.maxQualityRetriesPerStep(),
			policy.maxReplaySafeRetransmits(),
			policy.maxTransmissionsPerAttempt(),
			policy.backoffBase(),
			Duration.ofSeconds(31),
			policy.maxAutonomousWait(),
			policy.orchestrationDeadline(),
			true,
			false,
			true,
			"default"))
			.isInstanceOf(IllegalArgumentException.class);

		assertThatThrownBy(() -> new RetryPolicySnapshot(
			policy.maxPaidAttempts(),
			policy.additionalLogicalAttempts(),
			policy.maxQualityRetriesPerStep(),
			policy.maxReplaySafeRetransmits(),
			policy.maxTransmissionsPerAttempt(),
			policy.backoffBase(),
			policy.computedBackoffCap(),
			Duration.ofMinutes(6),
			policy.orchestrationDeadline(),
			true,
			false,
			true,
			"default"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void serializedFormIsDeterministicAndContainsOnlyPolicyData() {
		RetryPolicySnapshot policy = RetryPolicySnapshot.forStepCount(1);

		assertThat(policy.serialize())
			.isEqualTo(policy.serialize())
			.isEqualTo(policy.toString())
			.contains("maxPaidAttempts=3")
			.contains("additionalLogicalAttempts=2")
			.contains("maxReplaySafeRetransmits=2")
			.doesNotContain("http://", "https://", "image", "secret", "providerPayload");
	}

	@Test
	void serializedSnapshotRoundTripsEveryComponent() {
		RetryPolicySnapshot snapshot = new RetryPolicySnapshot(
			7,
			2,
			1,
			2,
			3,
			Duration.ofMillis(750),
			Duration.ofSeconds(29),
			Duration.ofMinutes(4),
			Duration.ofMinutes(29),
			false,
			true,
			false,
			"provider-timing-v2");

		assertThat(RetryPolicySnapshot.parse(snapshot.serialize()))
			.isEqualTo(snapshot);
	}

	@Test
	void rejectsMalformedCapRaisingAndTrailingSerializedData() {
		String serialized = RetryPolicySnapshot.forStepCount(1).serialize();

		assertThatThrownBy(() -> RetryPolicySnapshot.parse(null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> RetryPolicySnapshot.parse(
			serialized.replace("maxPaidAttempts=3", "maxPaidAttempts=not-a-number")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> RetryPolicySnapshot.parse(
			serialized.replace("additionalLogicalAttempts=2", "additionalLogicalAttempts=3")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> RetryPolicySnapshot.parse(
			serialized.replace("computedBackoffCap=PT30S", "computedBackoffCap=PT31S")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> RetryPolicySnapshot.parse(
			serialized.replace(";providerTimingPolicyId=default", ";unknown=value;providerTimingPolicyId=default")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> RetryPolicySnapshot.parse(serialized + " trailing"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void v1SerializedFormRemainsByteIdenticalForHistoricalRows() {
		RetryPolicySnapshot oneStep = RetryPolicySnapshot.forStepCount(1);

		assertThat(oneStep.serialize())
			.isEqualTo("RetryPolicySnapshot[maxPaidAttempts=3;additionalLogicalAttempts=2;maxQualityRetriesPerStep=1;maxReplaySafeRetransmits=2;maxTransmissionsPerAttempt=3;backoffBase=PT1S;computedBackoffCap=PT30S;maxAutonomousWait=PT5M;orchestrationDeadline=PT30M;technicalRetryEnabled=true;fallbackEnabled=false;qualityRegenerationEnabled=true;providerTimingPolicyId=default]");
		assertThat(oneStep.snapshotVersion()).isEqualTo(RetryPolicySnapshot.SNAPSHOT_VERSION_V1);
		assertThat(oneStep.maxTechnicalRetries()).isNull();
		assertThat(oneStep.maxEvaluatorRetriesPerCandidate()).isNull();
		assertThat(oneStep.providerRoutingPolicyId()).isNull();
		assertThat(RetryPolicySnapshot.parse(oneStep.serialize())).isEqualTo(oneStep);
	}

	@Test
	void forStepCountV2ResolvesFixedPaidCapAndExplicitCeilings() {
		RetryPolicySnapshot oneStep = RetryPolicySnapshot.forStepCountV2(1);
		RetryPolicySnapshot threeSteps = RetryPolicySnapshot.forStepCountV2(3);

		assertThat(oneStep.snapshotVersion()).isEqualTo(RetryPolicySnapshot.SNAPSHOT_VERSION_V2);
		assertThat(oneStep.maxPaidAttempts()).isEqualTo(3);
		assertThat(threeSteps.maxPaidAttempts()).isEqualTo(3);
		assertThat(oneStep.additionalLogicalAttempts()).isEqualTo(2);
		assertThat(oneStep.maxTechnicalRetries()).isEqualTo(2);
		assertThat(oneStep.maxQualityRetriesPerStep()).isEqualTo(1);
		assertThat(oneStep.maxEvaluatorRetriesPerCandidate()).isEqualTo(1);
		assertThat(oneStep.maxReplaySafeRetransmits()).isEqualTo(2);
		assertThat(oneStep.maxTransmissionsPerAttempt()).isEqualTo(3);
		assertThat(oneStep.backoffBase()).isEqualTo(Duration.ofSeconds(1));
		assertThat(oneStep.computedBackoffCap()).isEqualTo(Duration.ofSeconds(30));
		assertThat(oneStep.maxAutonomousWait()).isEqualTo(Duration.ofMinutes(5));
		assertThat(oneStep.orchestrationDeadline()).isEqualTo(Duration.ofMinutes(30));
		assertThat(oneStep.technicalRetryEnabled()).isTrue();
		assertThat(oneStep.fallbackEnabled()).isFalse();
		assertThat(oneStep.qualityRegenerationEnabled()).isTrue();
		assertThat(oneStep.providerTimingPolicyId()).isEqualTo("default");
		assertThat(oneStep.providerRoutingPolicyId()).isEqualTo("fashn-only-v1");
	}

	@Test
	void forStepCountV2HonoursRolloutFlagsAndRejectsInvalidStepCounts() {
		RetryPolicySnapshot disabled = RetryPolicySnapshot.forStepCountV2(2, false, true, false);

		assertThat(disabled.maxPaidAttempts()).isEqualTo(3);
		assertThat(disabled.technicalRetryEnabled()).isFalse();
		assertThat(disabled.fallbackEnabled()).isTrue();
		assertThat(disabled.qualityRegenerationEnabled()).isFalse();
		assertThat(disabled.maxTechnicalRetries()).isEqualTo(2);
		assertThat(disabled.maxEvaluatorRetriesPerCandidate()).isEqualTo(1);
		assertThat(disabled.providerRoutingPolicyId()).isEqualTo("fashn-only-v1");

		assertThatThrownBy(() -> RetryPolicySnapshot.forStepCountV2(0))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> RetryPolicySnapshot.forStepCountV2(Integer.MAX_VALUE))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void v2SerializedFormIsDeterministic() {
		RetryPolicySnapshot snapshot = RetryPolicySnapshot.forStepCountV2(2);

		assertThat(snapshot.serialize())
			.isEqualTo("RetryPolicySnapshotV2[version=2;maxPaidAttempts=3;additionalLogicalAttempts=2;maxTechnicalRetries=2;maxQualityRetriesPerStep=1;maxEvaluatorRetriesPerCandidate=1;maxReplaySafeRetransmits=2;maxTransmissionsPerAttempt=3;backoffBase=PT1S;computedBackoffCap=PT30S;maxAutonomousWait=PT5M;orchestrationDeadline=PT30M;technicalRetryEnabled=true;fallbackEnabled=false;qualityRegenerationEnabled=true;providerTimingPolicyId=default;providerRoutingPolicyId=fashn-only-v1]");
		assertThat(snapshot.toString()).isEqualTo(snapshot.serialize());
	}

	@Test
	void serializedV2SnapshotRoundTripsEveryComponent() {
		RetryPolicySnapshot snapshot = new RetryPolicySnapshot(
			3,
			2,
			1,
			2,
			3,
			Duration.ofMillis(750),
			Duration.ofSeconds(29),
			Duration.ofMinutes(4),
			Duration.ofMinutes(29),
			false,
			true,
			false,
			"provider-timing-v2",
			RetryPolicySnapshot.SNAPSHOT_VERSION_V2,
			1,
			0,
			"routing-policy-x");

		String serialized = snapshot.serialize();

		assertThat(RetryPolicySnapshot.parse(serialized)).isEqualTo(snapshot);
		assertThat(RetryPolicySnapshot.parseResolution(serialized))
			.isEqualTo(new RetryPolicyResolution(RetryPolicyResolution.State.VALID, snapshot));
	}

	@Test
	void rejectsInconsistentSnapshotVersionCombinations() {
		RetryPolicySnapshot v1 = RetryPolicySnapshot.forStepCount(1);
		RetryPolicySnapshot v2 = RetryPolicySnapshot.forStepCountV2(1);

		assertThatThrownBy(() -> new RetryPolicySnapshot(
			v1.maxPaidAttempts(),
			v1.additionalLogicalAttempts(),
			v1.maxQualityRetriesPerStep(),
			v1.maxReplaySafeRetransmits(),
			v1.maxTransmissionsPerAttempt(),
			v1.backoffBase(),
			v1.computedBackoffCap(),
			v1.maxAutonomousWait(),
			v1.orchestrationDeadline(),
			true,
			false,
			true,
			v1.providerTimingPolicyId(),
			RetryPolicySnapshot.SNAPSHOT_VERSION_V1,
			2,
			null,
			null))
			.isInstanceOf(IllegalArgumentException.class);

		assertThatThrownBy(() -> new RetryPolicySnapshot(
			v1.maxPaidAttempts(),
			v1.additionalLogicalAttempts(),
			v1.maxQualityRetriesPerStep(),
			v1.maxReplaySafeRetransmits(),
			v1.maxTransmissionsPerAttempt(),
			v1.backoffBase(),
			v1.computedBackoffCap(),
			v1.maxAutonomousWait(),
			v1.orchestrationDeadline(),
			true,
			false,
			true,
			v1.providerTimingPolicyId(),
			RetryPolicySnapshot.SNAPSHOT_VERSION_V1,
			null,
			null,
			"fashn-only-v1"))
			.isInstanceOf(IllegalArgumentException.class);

		assertThatThrownBy(() -> new RetryPolicySnapshot(
			v2.maxPaidAttempts(),
			v2.additionalLogicalAttempts(),
			v2.maxQualityRetriesPerStep(),
			v2.maxReplaySafeRetransmits(),
			v2.maxTransmissionsPerAttempt(),
			v2.backoffBase(),
			v2.computedBackoffCap(),
			v2.maxAutonomousWait(),
			v2.orchestrationDeadline(),
			true,
			false,
			true,
			v2.providerTimingPolicyId(),
			RetryPolicySnapshot.SNAPSHOT_VERSION_V2,
			null,
			v2.maxEvaluatorRetriesPerCandidate(),
			v2.providerRoutingPolicyId()))
			.isInstanceOf(IllegalArgumentException.class);

		assertThatThrownBy(() -> new RetryPolicySnapshot(
			v2.maxPaidAttempts(),
			v2.additionalLogicalAttempts(),
			v2.maxQualityRetriesPerStep(),
			v2.maxReplaySafeRetransmits(),
			v2.maxTransmissionsPerAttempt(),
			v2.backoffBase(),
			v2.computedBackoffCap(),
			v2.maxAutonomousWait(),
			v2.orchestrationDeadline(),
			true,
			false,
			true,
			v2.providerTimingPolicyId(),
			RetryPolicySnapshot.SNAPSHOT_VERSION_V2,
			3,
			v2.maxEvaluatorRetriesPerCandidate(),
			v2.providerRoutingPolicyId()))
			.isInstanceOf(IllegalArgumentException.class);

		assertThatThrownBy(() -> new RetryPolicySnapshot(
			v2.maxPaidAttempts(),
			v2.additionalLogicalAttempts(),
			v2.maxQualityRetriesPerStep(),
			v2.maxReplaySafeRetransmits(),
			v2.maxTransmissionsPerAttempt(),
			v2.backoffBase(),
			v2.computedBackoffCap(),
			v2.maxAutonomousWait(),
			v2.orchestrationDeadline(),
			true,
			false,
			true,
			v2.providerTimingPolicyId(),
			RetryPolicySnapshot.SNAPSHOT_VERSION_V2,
			v2.maxTechnicalRetries(),
			2,
			v2.providerRoutingPolicyId()))
			.isInstanceOf(IllegalArgumentException.class);

		assertThatThrownBy(() -> new RetryPolicySnapshot(
			v2.maxPaidAttempts(),
			v2.additionalLogicalAttempts(),
			v2.maxQualityRetriesPerStep(),
			v2.maxReplaySafeRetransmits(),
			v2.maxTransmissionsPerAttempt(),
			v2.backoffBase(),
			v2.computedBackoffCap(),
			v2.maxAutonomousWait(),
			v2.orchestrationDeadline(),
			true,
			false,
			true,
			v2.providerTimingPolicyId(),
			RetryPolicySnapshot.SNAPSHOT_VERSION_V2,
			v2.maxTechnicalRetries(),
			v2.maxEvaluatorRetriesPerCandidate(),
			" "))
			.isInstanceOf(IllegalArgumentException.class);

		assertThatThrownBy(() -> new RetryPolicySnapshot(
			v1.maxPaidAttempts(),
			v1.additionalLogicalAttempts(),
			v1.maxQualityRetriesPerStep(),
			v1.maxReplaySafeRetransmits(),
			v1.maxTransmissionsPerAttempt(),
			v1.backoffBase(),
			v1.computedBackoffCap(),
			v1.maxAutonomousWait(),
			v1.orchestrationDeadline(),
			true,
			false,
			true,
			v1.providerTimingPolicyId(),
			3,
			null,
			null,
			null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void parseResolutionMatrixFailsClosedWithoutSubstitutingDefaults() {
		RetryPolicySnapshot v1 = RetryPolicySnapshot.forStepCount(1);
		RetryPolicySnapshot v2 = RetryPolicySnapshot.forStepCountV2(1);

		assertThat(RetryPolicySnapshot.parseResolution(null))
			.isEqualTo(new RetryPolicyResolution(RetryPolicyResolution.State.MISSING, null));
		assertThat(RetryPolicySnapshot.parseResolution(""))
			.isEqualTo(new RetryPolicyResolution(RetryPolicyResolution.State.EMPTY, null));
		assertThat(RetryPolicySnapshot.parseResolution("   "))
			.isEqualTo(new RetryPolicyResolution(RetryPolicyResolution.State.EMPTY, null));

		assertThat(RetryPolicySnapshot.parseResolution(v1.serialize()))
			.isEqualTo(new RetryPolicyResolution(RetryPolicyResolution.State.VALID, v1));
		assertThat(RetryPolicySnapshot.parseResolution(v2.serialize()))
			.isEqualTo(new RetryPolicyResolution(RetryPolicyResolution.State.VALID, v2));

		assertThat(RetryPolicySnapshot.parseResolution(v2.serialize().replace("version=2", "version=3")))
			.isEqualTo(new RetryPolicyResolution(RetryPolicyResolution.State.UNSUPPORTED_VERSION, null));
		assertThat(RetryPolicySnapshot.parseResolution("RetryPolicySnapshotV3[version=3;maxPaidAttempts=3]"))
			.isEqualTo(new RetryPolicyResolution(RetryPolicyResolution.State.UNSUPPORTED_VERSION, null));
		assertThat(RetryPolicySnapshot.parseResolution("RetryPolicySnapshotVX[version=9;maxPaidAttempts=3]"))
			.isEqualTo(new RetryPolicyResolution(RetryPolicyResolution.State.UNSUPPORTED_VERSION, null));

		RetryPolicyResolution fieldCountMismatch = RetryPolicySnapshot.parseResolution(
			v1.serialize().replace("]", ";extra=1]"));
		assertThat(fieldCountMismatch.state()).isEqualTo(RetryPolicyResolution.State.INVALID);
		assertThat(fieldCountMismatch.snapshot()).isNull();
		RetryPolicyResolution v2FieldCountMismatch = RetryPolicySnapshot.parseResolution(
			v2.serialize().replace(";providerRoutingPolicyId=fashn-only-v1", ""));
		assertThat(v2FieldCountMismatch.state()).isEqualTo(RetryPolicyResolution.State.INVALID);
		assertThat(v2FieldCountMismatch.snapshot()).isNull();

		RetryPolicyResolution invalidInteger = RetryPolicySnapshot.parseResolution(
			v1.serialize().replace("maxPaidAttempts=3", "maxPaidAttempts=not-a-number"));
		assertThat(invalidInteger.state()).isEqualTo(RetryPolicyResolution.State.INVALID);
		assertThat(invalidInteger.snapshot()).isNull();

		RetryPolicyResolution capRaising = RetryPolicySnapshot.parseResolution(
			v1.serialize().replace("computedBackoffCap=PT30S", "computedBackoffCap=PT31S"));
		assertThat(capRaising.state()).isEqualTo(RetryPolicyResolution.State.INVALID);
		assertThat(capRaising.snapshot()).isNull();
		RetryPolicyResolution v2CapRaising = RetryPolicySnapshot.parseResolution(
			v2.serialize().replace("maxTechnicalRetries=2", "maxTechnicalRetries=3"));
		assertThat(v2CapRaising.state()).isEqualTo(RetryPolicyResolution.State.INVALID);
		assertThat(v2CapRaising.snapshot()).isNull();

		RetryPolicyResolution garbage = RetryPolicySnapshot.parseResolution("not-a-snapshot");
		assertThat(garbage.state()).isEqualTo(RetryPolicyResolution.State.INVALID);
		assertThat(garbage.snapshot()).isNull();

		RetryPolicyResolution trailingData = RetryPolicySnapshot.parseResolution(v1.serialize() + " trailing");
		assertThat(trailingData.state()).isEqualTo(RetryPolicyResolution.State.INVALID);
		assertThat(trailingData.snapshot()).isNull();
	}

	@Test
	void resolutionOnlyPairsValidStateWithSnapshot() {
		assertThatThrownBy(() -> new RetryPolicyResolution(RetryPolicyResolution.State.VALID, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RetryPolicyResolution(
			RetryPolicyResolution.State.MISSING, RetryPolicySnapshot.forStepCount(1)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RetryPolicyResolution(
			RetryPolicyResolution.State.INVALID, RetryPolicySnapshot.forStepCountV2(1)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RetryPolicyResolution(null, null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsMalformedCapRaisingAndUnsafeV2SerializedData() {
		String serialized = RetryPolicySnapshot.forStepCountV2(1).serialize();

		assertThatThrownBy(() -> RetryPolicySnapshot.parse(serialized + " trailing"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> RetryPolicySnapshot.parse(
			serialized.replace("maxPaidAttempts=3", "maxPaidAttempts=not-a-number")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> RetryPolicySnapshot.parse(
			serialized.replace("maxTechnicalRetries=2", "maxTechnicalRetries=3")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> RetryPolicySnapshot.parse(
			serialized.replace("maxEvaluatorRetriesPerCandidate=1", "maxEvaluatorRetriesPerCandidate=2")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> RetryPolicySnapshot.parse(
			serialized.replace(";providerRoutingPolicyId=fashn-only-v1", "")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> RetryPolicySnapshot.parse(
			serialized.replace("providerRoutingPolicyId=fashn-only-v1", "providerRoutingPolicyId=fashn]only")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> RetryPolicySnapshot.parse(
			serialized.replace("version=2", "version=1")))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
