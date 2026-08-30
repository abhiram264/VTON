package com.aifashionvault.generation;

import com.aifashionvault.generation.vton.ProviderFailure;
import com.aifashionvault.generation.vton.ProviderJobId;

public sealed interface ProviderTurnOutcome
	permits ProviderTurnOutcome.Accepted, ProviderTurnOutcome.Rejected, ProviderTurnOutcome.Indeterminate {

	record Accepted(ProviderJobId providerJobId) implements ProviderTurnOutcome {
		public Accepted {
			if (providerJobId == null) throw new IllegalArgumentException("providerJobId is required");
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
