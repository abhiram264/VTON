package com.aifashionvault.featureflag;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeatureFlagServiceTest {

	private FeatureFlagProperties properties;
	private FeatureFlagService service;

	@BeforeEach
	void setUp() {
		this.properties = new FeatureFlagProperties();
		this.service = new FeatureFlagService(this.properties);
	}

	@Test
	void freshServiceReportsOnlySupportedCapabilitiesEnabled() {
		assertThat(this.service.isEnabled(FeatureFlagName.MULTI_ITEM)).isFalse();
		assertThat(this.service.isEnabled(FeatureFlagName.NATURAL_LANGUAGE_STUDIO)).isFalse();
		assertThat(this.service.isEnabled(FeatureFlagName.TRYON_MAX)).isTrue();
		assertThat(this.service.isEnabled(FeatureFlagName.HD_GENERATION)).isFalse();
		assertThat(this.service.isEnabled(FeatureFlagName.SHARE_IMPORT_ANDROID)).isTrue();
		assertThat(this.service.isEnabled(FeatureFlagName.SHARE_IMPORT_IOS)).isTrue();
		assertThat(this.service.isEnabled(FeatureFlagName.NEW_QUALITY_GATE)).isFalse();
	}

	@Test
	void featureFlagNameEnumContainsExactSevenLiteralsWithSupportPosture() {
		assertThat(FeatureFlagName.values()).containsExactly(
			FeatureFlagName.MULTI_ITEM,
			FeatureFlagName.NATURAL_LANGUAGE_STUDIO,
			FeatureFlagName.TRYON_MAX,
			FeatureFlagName.HD_GENERATION,
			FeatureFlagName.SHARE_IMPORT_ANDROID,
			FeatureFlagName.SHARE_IMPORT_IOS,
			FeatureFlagName.NEW_QUALITY_GATE
		);

		for (FeatureFlagName name : FeatureFlagName.values()) {
			boolean expectedSupported =
				name == FeatureFlagName.TRYON_MAX
					|| name == FeatureFlagName.SHARE_IMPORT_ANDROID
					|| name == FeatureFlagName.SHARE_IMPORT_IOS;
			assertThat(name.isSupported()).isEqualTo(expectedSupported);
			assertThat(this.service.isEnabled(name)).isEqualTo(expectedSupported);
		}
	}

	@Test
	void supportedFlagDisabledByConfigurationIsOverriddenOnAndRestoredAfterClear() {
		this.properties.setEnabled(FeatureFlagName.TRYON_MAX, false);
		assertThat(this.service.isEnabled(FeatureFlagName.TRYON_MAX)).isFalse();

		this.service.override(FeatureFlagName.TRYON_MAX, true);
		assertThat(this.service.isEnabled(FeatureFlagName.TRYON_MAX)).isTrue();

		this.service.clearOverride(FeatureFlagName.TRYON_MAX);
		assertThat(this.service.isEnabled(FeatureFlagName.TRYON_MAX)).isFalse();
	}

	@Test
	void supportedFlagIsDisabledByOverrideAndRestoredAfterClear() {
		assertThat(this.service.isEnabled(FeatureFlagName.SHARE_IMPORT_ANDROID)).isTrue();

		this.service.override(FeatureFlagName.SHARE_IMPORT_ANDROID, false);
		assertThat(this.service.isEnabled(FeatureFlagName.SHARE_IMPORT_ANDROID)).isFalse();

		this.service.clearOverride(FeatureFlagName.SHARE_IMPORT_ANDROID);
		assertThat(this.service.isEnabled(FeatureFlagName.SHARE_IMPORT_ANDROID)).isTrue();
	}

	@Test
	void returnedSnapshotCannotBeMutatedAndLaterReadsAreUnaffected() {
		Map<FeatureFlagName, Boolean> snapshot = this.service.snapshot();
		assertThat(snapshot).hasSize(7);
		assertThat(snapshot.get(FeatureFlagName.NEW_QUALITY_GATE)).isFalse();
		assertThat(snapshot.get(FeatureFlagName.TRYON_MAX)).isTrue();

		assertThatThrownBy(() -> snapshot.put(FeatureFlagName.TRYON_MAX, false))
			.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(snapshot::clear)
			.isInstanceOf(UnsupportedOperationException.class);

		// Subsequent reads unaffected
		assertThat(this.service.isEnabled(FeatureFlagName.TRYON_MAX)).isTrue();

		// Programmatic override after snapshot does not mutate previous snapshot
		this.service.override(FeatureFlagName.TRYON_MAX, false);
		assertThat(this.service.isEnabled(FeatureFlagName.TRYON_MAX)).isFalse();
		assertThat(snapshot.get(FeatureFlagName.TRYON_MAX)).isTrue();

		// Fresh snapshot reflects new override
		Map<FeatureFlagName, Boolean> updatedSnapshot = this.service.snapshot();
		assertThat(updatedSnapshot.get(FeatureFlagName.TRYON_MAX)).isFalse();
	}

	@Test
	void overridesMapContainsOnlyActiveExplicitOverridesAndIsImmutable() {
		assertThat(this.service.overrides()).isEmpty();
		assertThat(this.service.getOverrides()).isEmpty();

		this.service.override(FeatureFlagName.TRYON_MAX, false);
		assertThat(this.service.overrides()).containsExactlyEntriesOf(Map.of(FeatureFlagName.TRYON_MAX, false));

		this.service.override(FeatureFlagName.SHARE_IMPORT_ANDROID, false);
		assertThat(this.service.overrides()).containsExactlyInAnyOrderEntriesOf(Map.of(
			FeatureFlagName.TRYON_MAX, false,
			FeatureFlagName.SHARE_IMPORT_ANDROID, false
		));

		assertThatThrownBy(() -> this.service.overrides().clear())
			.isInstanceOf(UnsupportedOperationException.class);

		this.service.clearOverride(FeatureFlagName.TRYON_MAX);
		assertThat(this.service.overrides()).containsExactlyEntriesOf(Map.of(FeatureFlagName.SHARE_IMPORT_ANDROID, false));

		this.service.clearOverride(FeatureFlagName.SHARE_IMPORT_ANDROID);
		assertThat(this.service.overrides()).isEmpty();
	}

	@Test
	void nullArgumentsThrowNullPointerException() {
		assertThatThrownBy(() -> this.service.isEnabled(null))
			.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> this.service.override(null, true))
			.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> this.service.clearOverride(null))
			.isInstanceOf(NullPointerException.class);
	}

	// C22: the fail-closed authority refuses to enable an unsupported capability at
	// the API boundary (override / binding surface) rather than silently accepting it.
	@Test
	void enablingAnUnsupportedCapabilityIsRefusedAtTheApiBoundary() {
		assertThatThrownBy(() -> this.service.override(FeatureFlagName.MULTI_ITEM, true))
			.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> this.service.override(FeatureFlagName.HD_GENERATION, true))
			.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> this.properties.setEnabled(FeatureFlagName.MULTI_ITEM, true))
			.isInstanceOf(UnsupportedOperationException.class);

		// Disabling an unsupported capability is always allowed and stays off.
		this.service.override(FeatureFlagName.MULTI_ITEM, false);
		assertThat(this.service.isEnabled(FeatureFlagName.MULTI_ITEM)).isFalse();
	}
}
