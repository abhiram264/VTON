package com.aifashionvault.generation.vton;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

public record ProviderPerformanceSnapshot(
	List<ProviderPerformanceRow> rows,
	Instant asOf) {

	private static final Comparator<ProviderPerformanceRow> ROW_ORDER = Comparator
		.comparing(ProviderPerformanceRow::providerKey)
		.thenComparing(ProviderPerformanceRow::category)
		.thenComparing(ProviderPerformanceRow::modelOrMode)
		.thenComparing(ProviderPerformanceRow::currency, Comparator.nullsFirst(Comparator.naturalOrder()));

	public ProviderPerformanceSnapshot {
		if (asOf == null) {
			throw new IllegalArgumentException("asOf must not be null");
		}
		if (rows == null) {
			throw new IllegalArgumentException("rows must not be null");
		}
		rows = List.copyOf(rows);
		for (int i = 1; i < rows.size(); i++) {
			if (ROW_ORDER.compare(rows.get(i - 1), rows.get(i)) > 0) {
				throw new IllegalArgumentException(
					"rows must be sorted by (providerKey, category, modelOrMode, currency)");
			}
		}
	}
}
