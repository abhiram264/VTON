package com.aifashionvault.generation.vton;

import java.time.Instant;

public interface ProviderPerformanceStore {
	ProviderPerformanceSnapshot snapshot(ProviderPerformanceQuery query);
	void recompute(Instant fromInclusive, Instant toExclusive);
}
