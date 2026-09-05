package com.aifashionvault.featureflag;

import java.util.Map;

public record FeatureFlagResponse(
	Map<FeatureFlagName, Boolean> flags,
	Map<FeatureFlagName, Boolean> overrides
) {
	public FeatureFlagResponse {
		flags = (flags != null) ? flags : Map.of();
		overrides = (overrides != null) ? overrides : Map.of();
	}
}
