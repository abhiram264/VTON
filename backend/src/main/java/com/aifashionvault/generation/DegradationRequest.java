package com.aifashionvault.generation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import com.aifashionvault.generation.vton.ProviderCapabilities;
import com.aifashionvault.generation.vton.ProviderKey;

/**
 * Pure degradation decision context carrying requested mode, flag facts,
 * ordered candidates, and capability/health lookup functions.
 */
public record DegradationRequest(
	String requestedMode,
	boolean hdGenerationEnabled,
	List<ProviderKey> orderedCandidates,
	Predicate<ProviderKey> providerHealthy,
	Function<ProviderKey, ProviderCapabilities> capabilities
) {
	public DegradationRequest {
		if (orderedCandidates == null) {
			throw new IllegalArgumentException("orderedCandidates is required");
		}
		Set<ProviderKey> seen = new HashSet<>();
		for (ProviderKey candidate : orderedCandidates) {
			if (candidate == null) {
				throw new IllegalArgumentException("candidate must not be null");
			}
			if (!seen.add(candidate)) {
				throw new IllegalArgumentException("duplicate candidate: " + candidate.value());
			}
		}
		orderedCandidates = List.copyOf(orderedCandidates);
		if (providerHealthy == null) {
			throw new IllegalArgumentException("providerHealthy predicate is required");
		}
		if (capabilities == null) {
			throw new IllegalArgumentException("capabilities function is required");
		}
	}
}
