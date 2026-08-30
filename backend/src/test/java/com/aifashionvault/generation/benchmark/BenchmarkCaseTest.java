package com.aifashionvault.generation.benchmark;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BenchmarkCaseTest {

	@Test
	void componentShapeMatchesFrozenSeam() {
		assertThat(BenchmarkCase.class.getRecordComponents())
			.extracting(java.lang.reflect.RecordComponent::getName)
			.containsExactly("caseId", "benchmarkVersion", "avatarFixtureId", "itemFixtureId", "category");
		assertThat(BenchmarkCase.class.getRecordComponents())
			.extracting(java.lang.reflect.RecordComponent::getType)
			.containsExactly(String.class, String.class, String.class, String.class, String.class);
	}

	@Test
	void constructsWhenAllFieldsPresent() {
		BenchmarkCase benchmarkCase = new BenchmarkCase("case-1", "v1", "avatar-1", "item-1", "tops");
		assertThat(benchmarkCase.caseId()).isEqualTo("case-1");
		assertThat(benchmarkCase.benchmarkVersion()).isEqualTo("v1");
		assertThat(benchmarkCase.avatarFixtureId()).isEqualTo("avatar-1");
		assertThat(benchmarkCase.itemFixtureId()).isEqualTo("item-1");
		assertThat(benchmarkCase.category()).isEqualTo("tops");
	}

	@Test
	void blankOrNullIdsFailClosed() {
		String[] fields = {"caseId", "benchmarkVersion", "avatarFixtureId", "itemFixtureId", "category"};
		for (String field : fields) {
			assertThatThrownBy(() -> benchmarkCase(field, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining(field);
			assertThatThrownBy(() -> benchmarkCase(field, "  "))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining(field);
		}
	}

	private static BenchmarkCase benchmarkCase(String field, String value) {
		return switch (field) {
			case "caseId" -> new BenchmarkCase(value, "v1", "avatar-1", "item-1", "tops");
			case "benchmarkVersion" -> new BenchmarkCase("case-1", value, "avatar-1", "item-1", "tops");
			case "avatarFixtureId" -> new BenchmarkCase("case-1", "v1", value, "item-1", "tops");
			case "itemFixtureId" -> new BenchmarkCase("case-1", "v1", "avatar-1", value, "tops");
			case "category" -> new BenchmarkCase("case-1", "v1", "avatar-1", "item-1", value);
			default -> throw new AssertionError("unknown field " + field);
		};
	}
}
