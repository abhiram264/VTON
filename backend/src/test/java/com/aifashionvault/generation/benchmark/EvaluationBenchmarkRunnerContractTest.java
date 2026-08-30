package com.aifashionvault.generation.benchmark;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluationBenchmarkRunnerContractTest {

	@Test
	void runReceivesBenchmarkRunSpecAndReturnsBenchmarkReport() throws NoSuchMethodException {
		Method run = EvaluationBenchmarkRunner.class.getMethod("run", BenchmarkRunSpec.class);
		assertThat(run.getReturnType()).isEqualTo(BenchmarkReport.class);
		assertThat(Modifier.isAbstract(run.getModifiers())).isTrue();
		assertThat(Modifier.isPublic(run.getModifiers())).isTrue();
	}

	@Test
	void benchmarkReportIsCompositionalAroundCaseResultsAndMatrixRows() {
		assertThat(BenchmarkReport.class.getRecordComponents())
			.extracting(java.lang.reflect.RecordComponent::getName)
			.containsExactly("caseResults", "matrixRows");
		assertThat(BenchmarkReport.class.getRecordComponents())
			.extracting(java.lang.reflect.RecordComponent::getType)
			.containsExactly(List.class, List.class);
	}

	@Test
	void benchmarkReportListsAreDefensivelyCopiedAndNullRejected() {
		assertThatThrownBy(() -> new BenchmarkReport(null, List.of()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("caseResults");
		assertThatThrownBy(() -> new BenchmarkReport(List.of(), null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("matrixRows");
		BenchmarkReport report = new BenchmarkReport(
			new java.util.ArrayList<>(List.of()), new java.util.ArrayList<>(List.of()));
		assertThatThrownBy(() -> report.caseResults().add(result())).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> report.matrixRows().add(row())).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void contractSurfaceExposesOnlyScalarEnumAndVersionData() {
		// No component of any benchmark record carries media, bytes, URLs, credentials, or exception bodies.
		for (Class<?> record : List.of(BenchmarkCase.class, BenchmarkRunSpec.class, BenchmarkCaseResult.class,
				BenchmarkMatrixRow.class, BenchmarkReport.class)) {
			for (java.lang.reflect.RecordComponent component : record.getRecordComponents()) {
				Class<?> type = component.getType();
				assertThat(type.isPrimitive() || type == String.class || type == java.math.BigDecimal.class
					|| type == List.class || type.isEnum()
					|| type == com.aifashionvault.shared.QualityDecision.class)
					.as("%s.%s must be scalar/enum/version-shaped, was %s", record.getSimpleName(),
						component.getName(), type)
					.isTrue();
				if (type == List.class) {
					java.lang.reflect.Type generic = component.getGenericType();
					assertThat(generic).isInstanceOf(ParameterizedType.class);
					assertThat(((ParameterizedType) generic).getActualTypeArguments()[0])
						.isIn(String.class, BenchmarkCaseResult.class, BenchmarkMatrixRow.class);
				}
			}
		}
	}

	private static BenchmarkCaseResult result() {
		return new BenchmarkCaseResult("run-1", "case-1", "fashn", "mode-x", "tops", 1500L, Outcome.SUCCESS, null,
			null, null, null, null, null, false, com.aifashionvault.shared.QualityDecision.PASS);
	}

	private static BenchmarkMatrixRow row() {
		return new BenchmarkMatrixRow("fashn", "mode-x", "tops", null, 2000L, null, null, 100L, 95L);
	}
}
