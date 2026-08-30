package com.aifashionvault.generation.benchmark;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BenchmarkRunSpecTest {

	@Test
	void componentShapeMatchesFrozenSeam() {
		assertThat(BenchmarkRunSpec.class.getRecordComponents())
			.extracting(java.lang.reflect.RecordComponent::getName)
			.containsExactly(
				"runId",
				"benchmarkVersion",
				"providerKey",
				"modelOrMode",
				"promptVersion",
				"routingVersion",
				"caseIds");
		assertThat(BenchmarkRunSpec.class.getRecordComponents())
			.extracting(java.lang.reflect.RecordComponent::getType)
			.containsExactly(
				String.class,
				String.class,
				String.class,
				String.class,
				String.class,
				String.class,
				List.class);
	}

	@Test
	void caseIdsAreDefensivelyCopiedAndOrderIsPreserved() {
		List<String> mutable = new java.util.ArrayList<>(List.of("case-2", "case-1"));
		BenchmarkRunSpec spec = new BenchmarkRunSpec(
			"run-1", "v1", "fashn", "mode-x", "prompt-1", "routing-1", mutable);
		mutable.set(0, "case-mutated");
		assertThat(spec.caseIds()).containsExactly("case-2", "case-1");
		assertThatThrownBy(() -> spec.caseIds().set(0, "case-forbidden"))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void blankOrNullConfigurationFailsClosed() {
		String[] fields =
			{"runId", "benchmarkVersion", "providerKey", "modelOrMode", "promptVersion", "routingVersion"};
		for (String field : fields) {
			assertThatThrownBy(() -> spec(field, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining(field);
			assertThatThrownBy(() -> spec(field, "  "))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining(field);
		}
	}

	@Test
	void duplicateOrBlankCaseIdsFailClosed() {
		assertThatThrownBy(() -> validSpec(List.of("case-1", "case-1")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("duplicates");
		assertThatThrownBy(() -> validSpec(List.of("case-1", " ")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("blank");
		assertThatThrownBy(() -> validSpec(java.util.Arrays.asList("case-1", null)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("blank");
	}

	@Test
	void emptyOrNullCaseIdsFailClosed() {
		assertThatThrownBy(() -> validSpec(List.of()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("at least one");
		assertThatThrownBy(() -> validSpec(null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("at least one");
	}

	private static BenchmarkRunSpec validSpec(List<String> caseIds) {
		return new BenchmarkRunSpec("run-1", "v1", "fashn", "mode-x", "prompt-1", "routing-1", caseIds);
	}

	private static BenchmarkRunSpec spec(String field, String value) {
		return switch (field) {
			case "runId" -> new BenchmarkRunSpec(value, "v1", "fashn", "mode-x", "prompt-1", "routing-1",
				List.of("case-1"));
			case "benchmarkVersion" -> new BenchmarkRunSpec("run-1", value, "fashn", "mode-x", "prompt-1",
				"routing-1", List.of("case-1"));
			case "providerKey" -> new BenchmarkRunSpec("run-1", "v1", value, "mode-x", "prompt-1", "routing-1",
				List.of("case-1"));
			case "modelOrMode" -> new BenchmarkRunSpec("run-1", "v1", "fashn", value, "prompt-1", "routing-1",
				List.of("case-1"));
			case "promptVersion" -> new BenchmarkRunSpec("run-1", "v1", "fashn", "mode-x", value, "routing-1",
				List.of("case-1"));
			case "routingVersion" -> new BenchmarkRunSpec("run-1", "v1", "fashn", "mode-x", "prompt-1", value,
				List.of("case-1"));
			default -> throw new AssertionError("unknown field " + field);
		};
	}
}
