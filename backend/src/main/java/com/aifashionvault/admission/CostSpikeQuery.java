package com.aifashionvault.admission;

import java.time.Instant;

public record CostSpikeQuery(String providerKey, String currency, Instant now) {

	public CostSpikeQuery {
		if (providerKey == null || providerKey.isBlank()) {
			throw new IllegalArgumentException("providerKey must be non-blank");
		}
		if (currency == null || currency.isBlank()) {
			throw new IllegalArgumentException("currency must be non-blank");
		}
		if (now == null) {
			throw new IllegalArgumentException("now must be non-null");
		}
	}
}
