package com.aifashionvault.platform.generation.benchmark;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ReleaseGateThresholds} validation and defaults (Plan §52).
 */
class BenchmarkReleaseGateThresholdsTest {

	@Test
	void defaultsReturnsDocumentedStartingPolicy() {
		ReleaseGateThresholds thresholds = ReleaseGateThresholds.defaults();

		assertThat(thresholds.fidelityMaxRegressionPercent()).isEqualByComparingTo("0.02");
		assertThat(thresholds.identityMaxRegressionPercent()).isEqualByComparingTo("0.01");
		assertThat(thresholds.p50MaxRegressionPercent()).isEqualByComparingTo("0.05");
		assertThat(thresholds.p95MaxRegressionPercent()).isEqualByComparingTo("0.10");
		assertThat(thresholds.failRateCeiling()).isEqualByComparingTo("0.10");
		assertThat(thresholds.failRateMaxIncreaseAbsolute()).isEqualByComparingTo("0.02");
		assertThat(thresholds.qualityPassRateFloor()).isEqualByComparingTo("0.90");
		assertThat(thresholds.costPerSuccessMaxIncreasePercent()).isEqualByComparingTo("0.10");
		assertThat(thresholds.p50CeilingMs()).isEqualTo(15000L);
		assertThat(thresholds.p95CeilingMs()).isEqualTo(30000L);
		assertThat(thresholds.minSampleSize()).isEqualTo(30L);
	}

	@Test
	void nullCostToleranceDisablesCostRuleWithoutError() {
		ReleaseGateThresholds thresholds = new ReleaseGateThresholds(
				new BigDecimal("0.02"),
				new BigDecimal("0.01"),
				new BigDecimal("0.05"),
				new BigDecimal("0.10"),
				new BigDecimal("0.10"),
				new BigDecimal("0.02"),
				new BigDecimal("0.90"),
				null,
				15000L,
				30000L,
				30L);

		assertThat(thresholds.costPerSuccessMaxIncreasePercent()).isNull();
	}

	@Test
	void nullRequiredFieldsThrowNamingOffendingField() {
		ReleaseGateThresholds def = ReleaseGateThresholds.defaults();

		assertThatThrownBy(() -> new ReleaseGateThresholds(
				null, def.identityMaxRegressionPercent(), def.p50MaxRegressionPercent(),
				def.p95MaxRegressionPercent(), def.failRateCeiling(), def.failRateMaxIncreaseAbsolute(),
				def.qualityPassRateFloor(), def.costPerSuccessMaxIncreasePercent(),
				def.p50CeilingMs(), def.p95CeilingMs(), def.minSampleSize()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("fidelityMaxRegressionPercent");

		assertThatThrownBy(() -> new ReleaseGateThresholds(
				def.fidelityMaxRegressionPercent(), null, def.p50MaxRegressionPercent(),
				def.p95MaxRegressionPercent(), def.failRateCeiling(), def.failRateMaxIncreaseAbsolute(),
				def.qualityPassRateFloor(), def.costPerSuccessMaxIncreasePercent(),
				def.p50CeilingMs(), def.p95CeilingMs(), def.minSampleSize()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("identityMaxRegressionPercent");

		assertThatThrownBy(() -> new ReleaseGateThresholds(
				def.fidelityMaxRegressionPercent(), def.identityMaxRegressionPercent(), null,
				def.p95MaxRegressionPercent(), def.failRateCeiling(), def.failRateMaxIncreaseAbsolute(),
				def.qualityPassRateFloor(), def.costPerSuccessMaxIncreasePercent(),
				def.p50CeilingMs(), def.p95CeilingMs(), def.minSampleSize()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("p50MaxRegressionPercent");

		assertThatThrownBy(() -> new ReleaseGateThresholds(
				def.fidelityMaxRegressionPercent(), def.identityMaxRegressionPercent(), def.p50MaxRegressionPercent(),
				null, def.failRateCeiling(), def.failRateMaxIncreaseAbsolute(),
				def.qualityPassRateFloor(), def.costPerSuccessMaxIncreasePercent(),
				def.p50CeilingMs(), def.p95CeilingMs(), def.minSampleSize()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("p95MaxRegressionPercent");

		assertThatThrownBy(() -> new ReleaseGateThresholds(
				def.fidelityMaxRegressionPercent(), def.identityMaxRegressionPercent(), def.p50MaxRegressionPercent(),
				def.p95MaxRegressionPercent(), null, def.failRateMaxIncreaseAbsolute(),
				def.qualityPassRateFloor(), def.costPerSuccessMaxIncreasePercent(),
				def.p50CeilingMs(), def.p95CeilingMs(), def.minSampleSize()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("failRateCeiling");

		assertThatThrownBy(() -> new ReleaseGateThresholds(
				def.fidelityMaxRegressionPercent(), def.identityMaxRegressionPercent(), def.p50MaxRegressionPercent(),
				def.p95MaxRegressionPercent(), def.failRateCeiling(), null,
				def.qualityPassRateFloor(), def.costPerSuccessMaxIncreasePercent(),
				def.p50CeilingMs(), def.p95CeilingMs(), def.minSampleSize()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("failRateMaxIncreaseAbsolute");

		assertThatThrownBy(() -> new ReleaseGateThresholds(
				def.fidelityMaxRegressionPercent(), def.identityMaxRegressionPercent(), def.p50MaxRegressionPercent(),
				def.p95MaxRegressionPercent(), def.failRateCeiling(), def.failRateMaxIncreaseAbsolute(),
				null, def.costPerSuccessMaxIncreasePercent(),
				def.p50CeilingMs(), def.p95CeilingMs(), def.minSampleSize()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("qualityPassRateFloor");
	}

	@Test
	void outOfBoundsUnitIntervalFieldsThrowNamingOffendingField() {
		ReleaseGateThresholds def = ReleaseGateThresholds.defaults();

		// Negative values
		assertThatThrownBy(() -> new ReleaseGateThresholds(
				new BigDecimal("-0.01"), def.identityMaxRegressionPercent(), def.p50MaxRegressionPercent(),
				def.p95MaxRegressionPercent(), def.failRateCeiling(), def.failRateMaxIncreaseAbsolute(),
				def.qualityPassRateFloor(), def.costPerSuccessMaxIncreasePercent(),
				def.p50CeilingMs(), def.p95CeilingMs(), def.minSampleSize()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("fidelityMaxRegressionPercent");

		assertThatThrownBy(() -> new ReleaseGateThresholds(
				def.fidelityMaxRegressionPercent(), new BigDecimal("-0.01"), def.p50MaxRegressionPercent(),
				def.p95MaxRegressionPercent(), def.failRateCeiling(), def.failRateMaxIncreaseAbsolute(),
				def.qualityPassRateFloor(), def.costPerSuccessMaxIncreasePercent(),
				def.p50CeilingMs(), def.p95CeilingMs(), def.minSampleSize()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("identityMaxRegressionPercent");

		assertThatThrownBy(() -> new ReleaseGateThresholds(
				def.fidelityMaxRegressionPercent(), def.identityMaxRegressionPercent(), new BigDecimal("-0.01"),
				def.p95MaxRegressionPercent(), def.failRateCeiling(), def.failRateMaxIncreaseAbsolute(),
				def.qualityPassRateFloor(), def.costPerSuccessMaxIncreasePercent(),
				def.p50CeilingMs(), def.p95CeilingMs(), def.minSampleSize()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("p50MaxRegressionPercent");

		assertThatThrownBy(() -> new ReleaseGateThresholds(
				def.fidelityMaxRegressionPercent(), def.identityMaxRegressionPercent(), def.p50MaxRegressionPercent(),
				new BigDecimal("-0.01"), def.failRateCeiling(), def.failRateMaxIncreaseAbsolute(),
				def.qualityPassRateFloor(), def.costPerSuccessMaxIncreasePercent(),
				def.p50CeilingMs(), def.p95CeilingMs(), def.minSampleSize()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("p95MaxRegressionPercent");

		assertThatThrownBy(() -> new ReleaseGateThresholds(
				def.fidelityMaxRegressionPercent(), def.identityMaxRegressionPercent(), def.p50MaxRegressionPercent(),
				def.p95MaxRegressionPercent(), new BigDecimal("-0.01"), def.failRateMaxIncreaseAbsolute(),
				def.qualityPassRateFloor(), def.costPerSuccessMaxIncreasePercent(),
				def.p50CeilingMs(), def.p95CeilingMs(), def.minSampleSize()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("failRateCeiling");

		assertThatThrownBy(() -> new ReleaseGateThresholds(
				def.fidelityMaxRegressionPercent(), def.identityMaxRegressionPercent(), def.p50MaxRegressionPercent(),
				def.p95MaxRegressionPercent(), def.failRateCeiling(), new BigDecimal("-0.01"),
				def.qualityPassRateFloor(), def.costPerSuccessMaxIncreasePercent(),
				def.p50CeilingMs(), def.p95CeilingMs(), def.minSampleSize()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("failRateMaxIncreaseAbsolute");

		assertThatThrownBy(() -> new ReleaseGateThresholds(
				def.fidelityMaxRegressionPercent(), def.identityMaxRegressionPercent(), def.p50MaxRegressionPercent(),
				def.p95MaxRegressionPercent(), def.failRateCeiling(), def.failRateMaxIncreaseAbsolute(),
				new BigDecimal("-0.01"), def.costPerSuccessMaxIncreasePercent(),
				def.p50CeilingMs(), def.p95CeilingMs(), def.minSampleSize()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("qualityPassRateFloor");

		assertThatThrownBy(() -> new ReleaseGateThresholds(
				def.fidelityMaxRegressionPercent(), def.identityMaxRegressionPercent(), def.p50MaxRegressionPercent(),
				def.p95MaxRegressionPercent(), def.failRateCeiling(), def.failRateMaxIncreaseAbsolute(),
				def.qualityPassRateFloor(), new BigDecimal("-0.01"),
				def.p50CeilingMs(), def.p95CeilingMs(), def.minSampleSize()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("costPerSuccessMaxIncreasePercent");

		// Values greater than 1
		assertThatThrownBy(() -> new ReleaseGateThresholds(
				new BigDecimal("1.01"), def.identityMaxRegressionPercent(), def.p50MaxRegressionPercent(),
				def.p95MaxRegressionPercent(), def.failRateCeiling(), def.failRateMaxIncreaseAbsolute(),
				def.qualityPassRateFloor(), def.costPerSuccessMaxIncreasePercent(),
				def.p50CeilingMs(), def.p95CeilingMs(), def.minSampleSize()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("fidelityMaxRegressionPercent");

		assertThatThrownBy(() -> new ReleaseGateThresholds(
				def.fidelityMaxRegressionPercent(), new BigDecimal("1.01"), def.p50MaxRegressionPercent(),
				def.p95MaxRegressionPercent(), def.failRateCeiling(), def.failRateMaxIncreaseAbsolute(),
				def.qualityPassRateFloor(), def.costPerSuccessMaxIncreasePercent(),
				def.p50CeilingMs(), def.p95CeilingMs(), def.minSampleSize()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("identityMaxRegressionPercent");

		assertThatThrownBy(() -> new ReleaseGateThresholds(
				def.fidelityMaxRegressionPercent(), def.identityMaxRegressionPercent(), new BigDecimal("1.01"),
				def.p95MaxRegressionPercent(), def.failRateCeiling(), def.failRateMaxIncreaseAbsolute(),
				def.qualityPassRateFloor(), def.costPerSuccessMaxIncreasePercent(),
				def.p50CeilingMs(), def.p95CeilingMs(), def.minSampleSize()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("p50MaxRegressionPercent");

		assertThatThrownBy(() -> new ReleaseGateThresholds(
				def.fidelityMaxRegressionPercent(), def.identityMaxRegressionPercent(), def.p50MaxRegressionPercent(),
				new BigDecimal("1.01"), def.failRateCeiling(), def.failRateMaxIncreaseAbsolute(),
				def.qualityPassRateFloor(), def.costPerSuccessMaxIncreasePercent(),
				def.p50CeilingMs(), def.p95CeilingMs(), def.minSampleSize()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("p95MaxRegressionPercent");

		assertThatThrownBy(() -> new ReleaseGateThresholds(
				def.fidelityMaxRegressionPercent(), def.identityMaxRegressionPercent(), def.p50MaxRegressionPercent(),
				def.p95MaxRegressionPercent(), new BigDecimal("1.01"), def.failRateMaxIncreaseAbsolute(),
				def.qualityPassRateFloor(), def.costPerSuccessMaxIncreasePercent(),
				def.p50CeilingMs(), def.p95CeilingMs(), def.minSampleSize()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("failRateCeiling");

		assertThatThrownBy(() -> new ReleaseGateThresholds(
				def.fidelityMaxRegressionPercent(), def.identityMaxRegressionPercent(), def.p50MaxRegressionPercent(),
				def.p95MaxRegressionPercent(), def.failRateCeiling(), new BigDecimal("1.01"),
				def.qualityPassRateFloor(), def.costPerSuccessMaxIncreasePercent(),
				def.p50CeilingMs(), def.p95CeilingMs(), def.minSampleSize()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("failRateMaxIncreaseAbsolute");

		assertThatThrownBy(() -> new ReleaseGateThresholds(
				def.fidelityMaxRegressionPercent(), def.identityMaxRegressionPercent(), def.p50MaxRegressionPercent(),
				def.p95MaxRegressionPercent(), def.failRateCeiling(), def.failRateMaxIncreaseAbsolute(),
				new BigDecimal("1.01"), def.costPerSuccessMaxIncreasePercent(),
				def.p50CeilingMs(), def.p95CeilingMs(), def.minSampleSize()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("qualityPassRateFloor");

		assertThatThrownBy(() -> new ReleaseGateThresholds(
				def.fidelityMaxRegressionPercent(), def.identityMaxRegressionPercent(), def.p50MaxRegressionPercent(),
				def.p95MaxRegressionPercent(), def.failRateCeiling(), def.failRateMaxIncreaseAbsolute(),
				def.qualityPassRateFloor(), new BigDecimal("1.01"),
				def.p50CeilingMs(), def.p95CeilingMs(), def.minSampleSize()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("costPerSuccessMaxIncreasePercent");
	}

	@Test
	void invalidCeilingsAndSampleSizeThrowNamingOffendingField() {
		ReleaseGateThresholds def = ReleaseGateThresholds.defaults();

		assertThatThrownBy(() -> new ReleaseGateThresholds(
				def.fidelityMaxRegressionPercent(), def.identityMaxRegressionPercent(), def.p50MaxRegressionPercent(),
				def.p95MaxRegressionPercent(), def.failRateCeiling(), def.failRateMaxIncreaseAbsolute(),
				def.qualityPassRateFloor(), def.costPerSuccessMaxIncreasePercent(),
				0L, def.p95CeilingMs(), def.minSampleSize()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("p50CeilingMs");

		assertThatThrownBy(() -> new ReleaseGateThresholds(
				def.fidelityMaxRegressionPercent(), def.identityMaxRegressionPercent(), def.p50MaxRegressionPercent(),
				def.p95MaxRegressionPercent(), def.failRateCeiling(), def.failRateMaxIncreaseAbsolute(),
				def.qualityPassRateFloor(), def.costPerSuccessMaxIncreasePercent(),
				-1L, def.p95CeilingMs(), def.minSampleSize()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("p50CeilingMs");

		assertThatThrownBy(() -> new ReleaseGateThresholds(
				def.fidelityMaxRegressionPercent(), def.identityMaxRegressionPercent(), def.p50MaxRegressionPercent(),
				def.p95MaxRegressionPercent(), def.failRateCeiling(), def.failRateMaxIncreaseAbsolute(),
				def.qualityPassRateFloor(), def.costPerSuccessMaxIncreasePercent(),
				def.p50CeilingMs(), 0L, def.minSampleSize()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("p95CeilingMs");

		assertThatThrownBy(() -> new ReleaseGateThresholds(
				def.fidelityMaxRegressionPercent(), def.identityMaxRegressionPercent(), def.p50MaxRegressionPercent(),
				def.p95MaxRegressionPercent(), def.failRateCeiling(), def.failRateMaxIncreaseAbsolute(),
				def.qualityPassRateFloor(), def.costPerSuccessMaxIncreasePercent(),
				def.p50CeilingMs(), -1L, def.minSampleSize()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("p95CeilingMs");

		assertThatThrownBy(() -> new ReleaseGateThresholds(
				def.fidelityMaxRegressionPercent(), def.identityMaxRegressionPercent(), def.p50MaxRegressionPercent(),
				def.p95MaxRegressionPercent(), def.failRateCeiling(), def.failRateMaxIncreaseAbsolute(),
				def.qualityPassRateFloor(), def.costPerSuccessMaxIncreasePercent(),
				def.p50CeilingMs(), def.p95CeilingMs(), 0L))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("minSampleSize");

		assertThatThrownBy(() -> new ReleaseGateThresholds(
				def.fidelityMaxRegressionPercent(), def.identityMaxRegressionPercent(), def.p50MaxRegressionPercent(),
				def.p95MaxRegressionPercent(), def.failRateCeiling(), def.failRateMaxIncreaseAbsolute(),
				def.qualityPassRateFloor(), def.costPerSuccessMaxIncreasePercent(),
				def.p50CeilingMs(), def.p95CeilingMs(), -5L))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("minSampleSize");
	}
}
