package com.aifashionvault.generation;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.aifashionvault.shared.TerminalReason;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CoarseGenerationPhaseTest {

	@Test
	void valuesMatchTheFrozenCoarsePhasesInDeclarationOrder() {
		assertThat(CoarseGenerationPhase.values())
			.extracting(Enum::name)
			.containsExactly(
				"QUEUED",
				"PREPARING",
				"GENERATING",
				"CHECKING_QUALITY",
				"READY",
				"FAILED");
	}

	@ParameterizedTest
	@MethodSource("durableMappings")
	void derivesCoarsePhaseFromDurableStatusAndTerminalReason(
			String durableStatus, String terminalReason, CoarseGenerationPhase expected) {
		assertThat(CoarseGenerationPhase.fromDurable(durableStatus, terminalReason)).isEqualTo(expected);
	}

	static Stream<Arguments> durableMappings() {
		return Stream.of(
			Arguments.of("QUEUED", null, CoarseGenerationPhase.QUEUED),
			Arguments.of("PREFLIGHT", null, CoarseGenerationPhase.PREPARING),
			Arguments.of("SUBMITTED", null, CoarseGenerationPhase.GENERATING),
			Arguments.of("GENERATING", null, CoarseGenerationPhase.GENERATING),
			Arguments.of("QUALITY_CHECK", null, CoarseGenerationPhase.CHECKING_QUALITY),
			Arguments.of("RETRYING", null, CoarseGenerationPhase.GENERATING),
			Arguments.of("COMPLETED", null, CoarseGenerationPhase.READY),
			Arguments.of("FAILED", TerminalReason.CANCELLED_BY_USER.name(), CoarseGenerationPhase.FAILED),
			Arguments.of("FAILED", null, CoarseGenerationPhase.FAILED),
			Arguments.of("FAILED", TerminalReason.PROVIDER_UNAVAILABLE.name(), CoarseGenerationPhase.FAILED),
			Arguments.of("FAILED", TerminalReason.QUALITY_REJECTED.name(), CoarseGenerationPhase.FAILED),
			Arguments.of("FAILED", "cancelled_by_user", CoarseGenerationPhase.FAILED),
			Arguments.of("FAILED", "NOT_A_TERMINAL_REASON", CoarseGenerationPhase.FAILED),
			Arguments.of("COMPLETED", TerminalReason.CANCELLED_BY_USER.name(), CoarseGenerationPhase.READY),
			Arguments.of("QUEUED", TerminalReason.CANCELLED_BY_USER.name(), CoarseGenerationPhase.QUEUED));
	}

	@Test
	void rejectsUnknownOrBlankDurableStatus() {
		assertThatThrownBy(() -> CoarseGenerationPhase.fromDurable(null, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> CoarseGenerationPhase.fromDurable("", null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> CoarseGenerationPhase.fromDurable("   ", null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> CoarseGenerationPhase.fromDurable("COMPLETED ", null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> CoarseGenerationPhase.fromDurable("CANCELLED", null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> CoarseGenerationPhase.fromDurable("RUNNING", null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> CoarseGenerationPhase.fromDurable("SUCCEEDED", null))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
