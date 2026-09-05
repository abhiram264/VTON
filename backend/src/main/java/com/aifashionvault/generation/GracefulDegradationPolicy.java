package com.aifashionvault.generation;

import java.util.List;

import com.aifashionvault.generation.vton.ProviderCapabilities;
import com.aifashionvault.generation.vton.ProviderKey;
import com.aifashionvault.generation.vton.VtonMode;
import com.aifashionvault.shared.TerminalReason;

/**
 * Pure, deterministic graceful degradation policy enforcing the §86 order:
 * HD unavailable -> use standard; preferred provider unavailable -> fallback provider.
 */
public interface GracefulDegradationPolicy {

	String MODE_HD = "HD";
	String MODE_STANDARD = "STANDARD";

	/**
	 * Resolves the degradation action following the exact §86 precedence.
	 *
	 * @param request fully materialized degradation request
	 * @return the resolved DegradationAction
	 */
	DegradationAction resolve(DegradationRequest request);

	/**
	 * Computes whether at least one healthy and capable provider exists
	 * besides the preferred candidate.
	 *
	 * @param request fully materialized degradation request
	 * @return true if a fallback provider capable of the effective mode is available
	 */
	boolean fallbackCapableProviderAvailable(DegradationRequest request);

	/**
	 * Default pure implementation of {@link GracefulDegradationPolicy}.
	 */
	static GracefulDegradationPolicy defaultPolicy() {
		return DefaultGracefulDegradationPolicy.INSTANCE;
	}
}

final class DefaultGracefulDegradationPolicy implements GracefulDegradationPolicy {

	static final DefaultGracefulDegradationPolicy INSTANCE = new DefaultGracefulDegradationPolicy();

	@Override
	public DegradationAction resolve(DegradationRequest request) {
		if (request == null) {
			throw new IllegalArgumentException("request is required");
		}

		String effectiveMode = normalizeMode(request.requestedMode());
		boolean modeDegraded = false;

		// Rung 1: HD degradation
		if (GracefulDegradationPolicy.MODE_HD.equalsIgnoreCase(request.requestedMode())) {
			if (!request.hdGenerationEnabled()) {
				return new DegradationAction.ModeDegraded(GracefulDegradationPolicy.MODE_HD, GracefulDegradationPolicy.MODE_STANDARD);
			}
			// If HD flag is on, check if the effective/preferred provider can render HD.
			// Per §86 order: if preferred provider cannot render HD mode, mode degrades before provider switching.
			if (!request.orderedCandidates().isEmpty()) {
				ProviderKey preferred = request.orderedCandidates().getFirst();
				if (!isProviderCapableOfMode(preferred, GracefulDegradationPolicy.MODE_HD, request)) {
					return new DegradationAction.ModeDegraded(GracefulDegradationPolicy.MODE_HD, GracefulDegradationPolicy.MODE_STANDARD);
				}
			}
		}

		// Rung 2: Provider selection across ordered candidates
		for (ProviderKey candidate : request.orderedCandidates()) {
			if (request.providerHealthy().test(candidate)
					&& isProviderCapableOfMode(candidate, effectiveMode, request)) {
				return new DegradationAction.ProviderSelected(candidate, modeDegraded);
			}
		}

		return new DegradationAction.NoEligibleProvider(TerminalReason.NO_ELIGIBLE_PROVIDER);
	}

	@Override
	public boolean fallbackCapableProviderAvailable(DegradationRequest request) {
		if (request == null) {
			throw new IllegalArgumentException("request is required");
		}

		String effectiveMode = computeEffectiveMode(request);
		List<ProviderKey> candidates = request.orderedCandidates();
		if (candidates.size() <= 1) {
			return false;
		}

		// Check candidates besides the first (preferred)
		for (int i = 1; i < candidates.size(); i++) {
			ProviderKey candidate = candidates.get(i);
			if (request.providerHealthy().test(candidate)
					&& isProviderCapableOfMode(candidate, effectiveMode, request)) {
				return true;
			}
		}

		return false;
	}

	private static String normalizeMode(String mode) {
		if (mode == null || mode.isBlank()) {
			return GracefulDegradationPolicy.MODE_STANDARD;
		}
		String trimmed = mode.strip();
		if (GracefulDegradationPolicy.MODE_HD.equalsIgnoreCase(trimmed)) {
			return GracefulDegradationPolicy.MODE_HD;
		}
		return GracefulDegradationPolicy.MODE_STANDARD;
	}

	private static String computeEffectiveMode(DegradationRequest request) {
		if (!GracefulDegradationPolicy.MODE_HD.equalsIgnoreCase(request.requestedMode())) {
			return GracefulDegradationPolicy.MODE_STANDARD;
		}
		if (!request.hdGenerationEnabled()) {
			return GracefulDegradationPolicy.MODE_STANDARD;
		}
		if (!request.orderedCandidates().isEmpty()) {
			ProviderKey preferred = request.orderedCandidates().getFirst();
			if (!isProviderCapableOfMode(preferred, GracefulDegradationPolicy.MODE_HD, request)) {
				return GracefulDegradationPolicy.MODE_STANDARD;
			}
		}
		return GracefulDegradationPolicy.MODE_HD;
	}

	private static boolean isProviderCapableOfMode(ProviderKey provider, String mode, DegradationRequest request) {
		ProviderCapabilities caps = request.capabilities().apply(provider);
		if (caps == null) {
			return false;
		}
		return caps.forMode(new VtonMode(mode)).isPresent();
	}
}
