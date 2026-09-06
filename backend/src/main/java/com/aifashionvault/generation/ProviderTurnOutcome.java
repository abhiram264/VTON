package com.aifashionvault.generation;

import java.util.Optional;

import com.aifashionvault.generation.vton.ProviderFailure;
import com.aifashionvault.generation.vton.ProviderJobId;
import com.aifashionvault.generation.vton.ProviderUsage;

public sealed interface ProviderTurnOutcome
	permits ProviderTurnOutcome.Accepted, ProviderTurnOutcome.Rejected, ProviderTurnOutcome.Indeterminate {

	record Accepted(ProviderJobId providerJobId, Optional<ProviderUsage> usage) implements ProviderTurnOutcome {
		public Accepted {
			if (providerJobId == null) throw new IllegalArgumentException("providerJobId is required");
			if (usage == null) throw new IllegalArgumentException("usage is required");
		}
		public Accepted(ProviderJobId providerJobId) {
			this(providerJobId, Optional.empty());
		}
	}

	record Rejected(ProviderFailure failure) implements ProviderTurnOutcome {
		public Rejected {
			if (failure == null) throw new IllegalArgumentException("failure is required");
		}
	}

	record Indeterminate(ProviderFailure failure) implements ProviderTurnOutcome {
		public Indeterminate {
			if (failure == null) throw new IllegalArgumentException("failure is required");
		}
	}
}
