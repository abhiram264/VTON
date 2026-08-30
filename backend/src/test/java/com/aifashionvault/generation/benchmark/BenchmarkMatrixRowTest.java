package com.aifashionvault.generation.benchmark;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BenchmarkMatrixRowTest {

	@Test
	void componentShapeMatchesFrozenSeam() {
		assertThat(BenchmarkMatrixRow.class.getRecordComponents())
			.extracting(java.lang.reflect.RecordComponent::getName)
			.containsExactly(
				"providerKey",
				"modelOrMode",
				"category",
				"fidelity",
				"p50LatencyMs",
				"failRate",
				"costPerSuccess",
				"sampleCount",
				"successCount");
		assertThat(BenchmarkMatrixRow.class.getRecordComponents())
			.extracting(java.lang.reflect.RecordComponent::getType)
			.containsExactly(
				String.class,
				String.class,
				String.class,
				BigDecimal.class,
				long.class,
				BigDecimal.class,
				BigDecimal.class,
				long.class,
				long.class);
	}

	@Test
	void constructsFullyPopulatedRow() {
		BenchmarkMatrixRow row = row(new BigDecimal("0.9600"), 2000L, new BigDecimal("0.0500"),
			new BigDecimal("0.0850"), 100L, 95L);
		assertThat(row.providerKey()).isEqualTo("fashn");
		assertThat(row.p50LatencyMs()).isEqualTo(2000L);
		assertThat(row.sampleCount()).isEqualTo(100L);
		assertThat(row.successCount()).isEqualTo(95L);
	}

	@Test
	void blankOrNullKeysFailClosed() {
		String[] fields = {"providerKey", "modelOrMode", "category"};
		for (String field : fields) {
			assertThatThrownBy(() -> row(field, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining(field);
		}
	}

	@Test
	void fidelityAndFailRateAreBoundedToUnitInterval() {
		assertThatThrownBy(() -> row(new BigDecimal("-0.01"), 2000L, null, null, 100L, 95L))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("fidelity");
		assertThatThrownBy(() -> row(new BigDecimal("1.01"), 2000L, null, null, 100L, 95L))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("fidelity");
		assertThatThrownBy(() -> row(null, 2000L, new BigDecimal("-0.01"), null, 100L, 95L))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("failRate");
		assertThatThrownBy(() -> row(null, 2000L, new BigDecimal("1.01"), null, 100L, 95L))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("failRate");
	}

	@Test
	void negativeMetricsFailClosed() {
		assertThatThrownBy(() -> row(null, -1L, null, null, 100L, 95L))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("p50LatencyMs");
		assertThatThrownBy(() -> row(null, 2000L, null, new BigDecimal("-0.01"), 100L, 95L))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("costPerSuccess");
		assertThatThrownBy(() -> row(null, 2000L, null, null, -1L, 0L))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("sampleCount");
		assertThatThrownBy(() -> row(null, 2000L, null, null, 100L, -1L))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("successCount");
	}

	@Test
	void costPerSuccessIsUndefinedNotZeroWhenNoSuccesses() {
		assertThatThrownBy(() -> row(null, 2000L, null, BigDecimal.ZERO, 100L, 0L))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("costPerSuccess");
		assertThat(row(null, 2000L, null, null, 100L, 0L).costPerSuccess()).isNull();
	}

	@Test
	void successCountCannotExceedSampleCount() {
		assertThatThrownBy(() -> row(null, 2000L, null, null, 10L, 11L))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("sampleCount");
	}

	private static BenchmarkMatrixRow row(String field, String value) {
		String providerKey = "providerKey".equals(field) ? value : "fashn";
		String modelOrMode = "modelOrMode".equals(field) ? value : "mode-x";
		String category = "category".equals(field) ? value : "tops";
		return new BenchmarkMatrixRow(providerKey, modelOrMode, category, null, 2000L, null, null, 100L, 95L);
	}

	private static BenchmarkMatrixRow row(BigDecimal fidelity, long p50LatencyMs, BigDecimal failRate,
		BigDecimal costPerSuccess, long sampleCount, long successCount) {
		return new BenchmarkMatrixRow("fashn", "mode-x", "tops", fidelity, p50LatencyMs, failRate,
			costPerSuccess, sampleCount, successCount);
	}
}
