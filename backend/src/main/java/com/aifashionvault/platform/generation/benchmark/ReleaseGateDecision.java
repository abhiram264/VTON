package com.aifashionvault.platform.generation.benchmark;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Release gate decision containing verdict, matched pairs, and findings (Plan §52).
 */
public record ReleaseGateDecision(
		ReleaseGateVerdict verdict,
		String benchmarkVersion,
		long matchedPairs,
		List<GateFinding> findings) {

	public ReleaseGateDecision {
		if (verdict == null) {
			throw new IllegalArgumentException("verdict must not be null");
		}
		if (benchmarkVersion == null || benchmarkVersion.isBlank()) {
			throw new IllegalArgumentException("benchmarkVersion must not be blank");
		}
		if (matchedPairs < 0) {
			throw new IllegalArgumentException("matchedPairs must be non-negative: " + matchedPairs);
		}
		List<GateFinding> copy = findings == null ? new ArrayList<>() : new ArrayList<>(findings);
		copy.sort(Comparator.comparing(GateFinding::rule)
				.thenComparing(GateFinding::category)
				.thenComparing(GateFinding::dimension));
		findings = Collections.unmodifiableList(copy);
	}
}
