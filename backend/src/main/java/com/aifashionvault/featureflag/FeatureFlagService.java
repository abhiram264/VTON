package com.aifashionvault.featureflag;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Service;

/**
 * Backend authority for capability flags.
 *
 * <p>This service is the single source of truth for what the release advertises:
 * a capability is reported enabled only when it is supported by the release and
 * not explicitly disabled by configuration. Unsupported capabilities fail closed —
 * they always report disabled and cannot be enabled through {@link #override},
 * so an offline flag fetch or a remote override can never turn one on (C22).
 */
@Service
public class FeatureFlagService implements FeatureFlags {

	private final FeatureFlagProperties properties;
	private final ConcurrentMap<FeatureFlagName, Boolean> overrides = new ConcurrentHashMap<>();

	public FeatureFlagService(FeatureFlagProperties properties) {
		this.properties = Objects.requireNonNull(properties, "properties must not be null");
	}

	@Override
	public boolean isEnabled(FeatureFlagName name) {
		Objects.requireNonNull(name, "name must not be null");
		if (!name.isSupported()) {
			return false;
		}
		Boolean overridden = this.overrides.get(name);
		if (overridden != null) {
			return overridden;
		}
		return this.properties.isEnabled(name);
	}

	@Override
	public void override(FeatureFlagName name, boolean enabled) {
		Objects.requireNonNull(name, "name must not be null");
		if (enabled && !name.isSupported()) {
			throw new UnsupportedOperationException(
				"feature flag " + name + " is not supported by this release and cannot be enabled");
		}
		this.overrides.put(name, enabled);
	}

	@Override
	public void clearOverride(FeatureFlagName name) {
		Objects.requireNonNull(name, "name must not be null");
		this.overrides.remove(name);
	}

	@Override
	public Map<FeatureFlagName, Boolean> snapshot() {
		Map<FeatureFlagName, Boolean> effective = new EnumMap<>(FeatureFlagName.class);
		for (FeatureFlagName name : FeatureFlagName.values()) {
			effective.put(name, isEnabled(name));
		}
		return Collections.unmodifiableMap(effective);
	}

	public Map<FeatureFlagName, Boolean> overrides() {
		if (this.overrides.isEmpty()) {
			return Collections.emptyMap();
		}
		Map<FeatureFlagName, Boolean> explicit = new EnumMap<>(FeatureFlagName.class);
		explicit.putAll(this.overrides);
		return Collections.unmodifiableMap(explicit);
	}

	public Map<FeatureFlagName, Boolean> getOverrides() {
		return overrides();
	}
}
