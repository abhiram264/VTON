package com.aifashionvault.featureflag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/feature-flags")
public class FeatureFlagController {

	private final FeatureFlagService featureFlagService;

	public FeatureFlagController(FeatureFlagService featureFlagService) {
		this.featureFlagService = featureFlagService;
	}

	@GetMapping
	public FeatureFlagResponse getFeatureFlags() {
		return new FeatureFlagResponse(
			this.featureFlagService.snapshot(),
			this.featureFlagService.overrides()
		);
	}
}
