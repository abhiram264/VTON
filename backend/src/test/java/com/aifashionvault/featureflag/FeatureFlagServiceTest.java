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
	void freshServiceReturnsSixTrueDefaultsAndNewQualityGateFalse() {
		assertThat(this.service.isEnabled(FeatureFlagName.MULTI_ITEM)).isTrue();
		assertThat(this.service.isEnabled(FeatureFlagName.NATURAL_LANGUAGE_STUDIO)).isTrue();
		assertThat(this.service.isEnabled(FeatureFlagName.TRYON_MAX)).isTrue();
		assertThat(this.service.isEnabled(FeatureFlagName.HD_GENERATION)).isTrue();
		assertThat(this.service.isEnabled(FeatureFlagName.SHARE_IMPORT_ANDROID)).isTrue();
		assertThat(this.service.isEnabled(FeatureFlagName.SHARE_IMPORT_IOS)).isTrue();
		assertThat(this.service.isEnabled(FeatureFlagName.NEW_QUALITY_GATE)).isFalse();
	}

	@Test
	void featureFlagNameEnumContainsExactSevenLiteralsWithMatchingDefaults() {
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
			boolean expectedDefault = (name != FeatureFlagName.NEW_QUALITY_GATE);
			assertThat(name.defaultEnabled()).isEqualTo(expectedDefault);
			assertThat(name.isDefaultEnabled()).isEqualTo(expectedDefault);
			assertThat(this.service.isEnabled(name)).isEqualTo(expectedDefault);
		}
	}

	@Test
	void propertyFalseIsOverriddenByProgrammaticTrueAndRestoredAfterClearOverride() {
		this.properties.setEnabled(FeatureFlagName.HD_GENERATION, false);
		assertThat(this.service.isEnabled(FeatureFlagName.HD_GENERATION)).isFalse();

		this.service.override(FeatureFlagName.HD_GENERATION, true);
		assertThat(this.service.isEnabled(FeatureFlagName.HD_GENERATION)).isTrue();

		this.service.clearOverride(FeatureFlagName.HD_GENERATION);
		assertThat(this.service.isEnabled(FeatureFlagName.HD_GENERATION)).isFalse();
	}

	@Test
	void propertyTrueIsOverriddenByProgrammaticFalseAndRestoredAfterClearOverride() {
		assertThat(this.service.isEnabled(FeatureFlagName.MULTI_ITEM)).isTrue();

		this.service.override(FeatureFlagName.MULTI_ITEM, false);
		assertThat(this.service.isEnabled(FeatureFlagName.MULTI_ITEM)).isFalse();

		this.service.clearOverride(FeatureFlagName.MULTI_ITEM);
		assertThat(this.service.isEnabled(FeatureFlagName.MULTI_ITEM)).isTrue();
	}

	@Test
	void returnedSnapshotCannotBeMutatedAndLaterReadsAreUnaffected() {
		Map<FeatureFlagName, Boolean> snapshot = this.service.snapshot();
		assertThat(snapshot).hasSize(7);
		assertThat(snapshot.get(FeatureFlagName.NEW_QUALITY_GATE)).isFalse();
		assertThat(snapshot.get(FeatureFlagName.HD_GENERATION)).isTrue();

		assertThatThrownBy(() -> snapshot.put(FeatureFlagName.HD_GENERATION, false))
			.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(snapshot::clear)
			.isInstanceOf(UnsupportedOperationException.class);

		// Subsequent reads unaffected
		assertThat(this.service.isEnabled(FeatureFlagName.HD_GENERATION)).isTrue();

		// Programmatic override after snapshot does not mutate previous snapshot
		this.service.override(FeatureFlagName.HD_GENERATION, false);
		assertThat(this.service.isEnabled(FeatureFlagName.HD_GENERATION)).isFalse();
		assertThat(snapshot.get(FeatureFlagName.HD_GENERATION)).isTrue();

		// Fresh snapshot reflects new override
		Map<FeatureFlagName, Boolean> updatedSnapshot = this.service.snapshot();
		assertThat(updatedSnapshot.get(FeatureFlagName.HD_GENERATION)).isFalse();
	}

	@Test
	void overridesMapContainsOnlyActiveExplicitOverridesAndIsImmutable() {
		assertThat(this.service.overrides()).isEmpty();
		assertThat(this.service.getOverrides()).isEmpty();

		this.service.override(FeatureFlagName.HD_GENERATION, false);
		assertThat(this.service.overrides()).containsExactlyEntriesOf(Map.of(FeatureFlagName.HD_GENERATION, false));

		this.service.override(FeatureFlagName.NEW_QUALITY_GATE, true);
		assertThat(this.service.overrides()).containsExactlyInAnyOrderEntriesOf(Map.of(
			FeatureFlagName.HD_GENERATION, false,
			FeatureFlagName.NEW_QUALITY_GATE, true
		));

		assertThatThrownBy(() -> this.service.overrides().clear())
			.isInstanceOf(UnsupportedOperationException.class);

		this.service.clearOverride(FeatureFlagName.HD_GENERATION);
		assertThat(this.service.overrides()).containsExactlyEntriesOf(Map.of(FeatureFlagName.NEW_QUALITY_GATE, true));

		this.service.clearOverride(FeatureFlagName.NEW_QUALITY_GATE);
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
}
