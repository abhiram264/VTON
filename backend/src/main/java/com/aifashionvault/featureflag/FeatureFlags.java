package com.aifashionvault.featureflag;

import java.util.Map;

public interface FeatureFlags {

	boolean isEnabled(FeatureFlagName name);

	void override(FeatureFlagName name, boolean enabled);

	void clearOverride(FeatureFlagName name);

	Map<FeatureFlagName, Boolean> snapshot();

}
