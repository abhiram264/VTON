package com.aifashionvault.generation;

import com.aifashionvault.generation.vton.ProviderKey;
import com.aifashionvault.shared.TerminalReason;

/**
 * Outcomes of the pure graceful degradation policy evaluation.
 */
public sealed interface DegradationAction {

	record ModeDegraded(String fromMode, String toMode) implements DegradationAction {
		public ModeDegraded {
			if (fromMode == null || fromMode.isBlank()) {
				throw new IllegalArgumentException("fromMode is required");
			}
			if (toMode == null || toMode.isBlank()) {
				throw new IllegalArgumentException("toMode is required");
			}
		}
	}

	record ProviderSelected(ProviderKey provider, boolean modeDegraded) implements DegradationAction {
		public ProviderSelected {
			if (provider == null) {
				throw new IllegalArgumentException("provider is required");
			}
		}
	}

	record NoEligibleProvider(TerminalReason reason) implements DegradationAction {
		public NoEligibleProvider {
			if (reason == null) {
				throw new IllegalArgumentException("reason is required");
			}
		}
	}
}
