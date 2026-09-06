package com.aifashionvault.generation.vton;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderPerformanceStoreContractTest {

	private static final Instant START = Instant.parse("2026-03-30T00:00:00Z");
	private static final Instant END = Instant.parse("2026-04-01T00:00:00Z");

	@Test
	void queryRejectsInvalidWindows() {
		assertThatThrownBy(() -> new ProviderPerformanceQuery(END, START, null, null, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ProviderPerformanceQuery(START, START, null, null, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ProviderPerformanceQuery(null, END, null, null, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ProviderPerformanceQuery(START, null, null, null, null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void queryRejectsBlankFilters() {
		assertThatThrownBy(() -> new ProviderPerformanceQuery(START, END, " ", null, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ProviderPerformanceQuery(START, END, null, "", null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ProviderPerformanceQuery(START, END, null, null, "  "))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void queryAcceptsNullAndNonBlankFilters() {
		assertThat(new ProviderPerformanceQuery(START, END, null, null, null)).isNotNull();
		ProviderPerformanceQuery filtered = new ProviderPerformanceQuery(START, END, "provider-a", "tops", "USD");
		assertThat(filtered.providerKey()).isEqualTo("provider-a");
		assertThat(filtered.category()).isEqualTo("tops");
		assertThat(filtered.currency()).isEqualTo("USD");
	}

	@Test
	void rowRejectsUnitIntervalViolations() {
		assertInvalidRow(row -> row.fidelity = new BigDecimal("1.5"));
		assertInvalidRow(row -> row.fidelity = new BigDecimal("-0.1"));
		assertInvalidRow(row -> row.qualityPassRate = new BigDecimal("1.5"));
		assertInvalidRow(row -> row.qualityPassRate = new BigDecimal("-0.1"));
		assertInvalidRow(row -> row.failRate = new BigDecimal("1.5"));
		assertInvalidRow(row -> row.failRate = new BigDecimal("-0.1"));
		assertInvalidRow(row -> row.failRate = null);
	}

	@Test
	void rowRejectsNegativeMetricsAndCounts() {
		assertInvalidRow(row -> row.meanLatencyMs = -1L);
		assertInvalidRow(row -> row.costPerSuccess = new BigDecimal("-0.01"));
		assertInvalidRow(row -> row.sampleCount = -1);
		assertInvalidRow(row -> row.technicalSuccessCount = -1);
		assertInvalidRow(row -> row.qualityApprovedCount = -1);
	}

	@Test
	void rowRejectsCountOrderingViolations() {
		assertInvalidRow(row -> row.technicalSuccessCount = row.sampleCount + 1);
		assertInvalidRow(row -> row.qualityApprovedCount = row.technicalSuccessCount + 1);
		assertInvalidRow(row -> {
			row.qualityApprovedCount = 0;
			row.technicalSuccessCount = 0;
			row.sampleCount = 1;
			row.costPerSuccess = new BigDecimal("0.5");
		});
	}

	@Test
	void rowRejectsBlankIdentifiers() {
		assertInvalidRow(row -> row.providerKey = null);
		assertInvalidRow(row -> row.providerKey = " ");
		assertInvalidRow(row -> row.modelOrMode = "");
		assertInvalidRow(row -> row.category = "  ");
	}

	@Test
	void rowAcceptsValidMetricsAndNullOptionals() {
		ProviderPerformanceRow row = new ProviderPerformanceRow(
			"provider-a", "m1", "tops", new BigDecimal("0.8"), BigDecimal.ONE, 100L,
			new BigDecimal("0.1"), new BigDecimal("0.5"), "USD", 3, 2, 1);
		assertThat(row.providerKey()).isEqualTo("provider-a");
		ProviderPerformanceRow sparse = new ProviderPerformanceRow(
			"provider-a", "default", "unattributed", null, null, null,
			BigDecimal.ZERO, null, null, 1, 1, 0);
		assertThat(sparse.fidelity()).isNull();
		assertThat(sparse.costPerSuccess()).isNull();
		assertThat(sparse.currency()).isNull();
	}

	@Test
	void snapshotRejectsNullRowsAndNullAsOf() {
		assertThatThrownBy(() -> new ProviderPerformanceSnapshot(null, END))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ProviderPerformanceSnapshot(List.of(), null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(new ProviderPerformanceSnapshot(List.of(), END).rows()).isEmpty();
	}

	@Test
	void snapshotRejectsUnsortedRows() {
		ProviderPerformanceRow later = new Row().providerKey("provider-b").build();
		ProviderPerformanceRow earlier = new Row().providerKey("provider-a").build();
		assertThatThrownBy(() -> new ProviderPerformanceSnapshot(List.of(later, earlier), END))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(new ProviderPerformanceSnapshot(List.of(earlier, later), END).rows()).hasSize(2);
	}

	@Test
	void snapshotOrdersNullCurrencyBucketsFirst() {
		ProviderPerformanceRow usd = new Row().currency("USD").build();
		ProviderPerformanceRow noCurrency = new Row().currency(null).build();
		assertThatThrownBy(() -> new ProviderPerformanceSnapshot(List.of(usd, noCurrency), END))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(new ProviderPerformanceSnapshot(List.of(noCurrency, usd), END).rows()).hasSize(2);
	}

	private static void assertInvalidRow(Consumer<Row> mutation) {
		Row row = new Row();
		mutation.accept(row);
		assertThatThrownBy(row::build).isInstanceOf(IllegalArgumentException.class);
	}

	/** Mutable stand-in for the twelve row components, valid by default. */
	private static final class Row {

		String providerKey = "provider-a";
		String modelOrMode = "m1";
		String category = "tops";
		BigDecimal fidelity = new BigDecimal("0.8");
		BigDecimal qualityPassRate = BigDecimal.ONE;
		Long meanLatencyMs = 100L;
		BigDecimal failRate = new BigDecimal("0.1");
		BigDecimal costPerSuccess = new BigDecimal("0.5");
		String currency = "USD";
		long sampleCount = 3;
		long technicalSuccessCount = 2;
		long qualityApprovedCount = 1;

		Row providerKey(String providerKey) {
			this.providerKey = providerKey;
			return this;
		}

		Row currency(String currency) {
			this.currency = currency;
			return this;
		}

		ProviderPerformanceRow build() {
			return new ProviderPerformanceRow(providerKey, modelOrMode, category, fidelity, qualityPassRate,
				meanLatencyMs, failRate, costPerSuccess, currency, sampleCount, technicalSuccessCount,
				qualityApprovedCount);
		}
	}
}
