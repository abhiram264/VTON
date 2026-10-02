package com.aifashionvault.featureflag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C22 release-posture regression: an unsupported capability must stay OFF in a
 * release build no matter what a flag source does. This test is written against
 * only the pre-existing API surface ({@code override}, {@code isEnabled},
 * {@code snapshot}, the bean setters) so it compiles and runs against the
 * pre-repair source and fails there for a behavioural reason:
 *
 * <ul>
 *   <li>pre-repair, {@code override(MULTI_ITEM, true)} made {@code isEnabled} true;</li>
 *   <li>pre-repair, a bound property of {@code true} was reported enabled;</li>
 *   <li>pre-repair, the default snapshot advertised MULTI_ITEM / HD_GENERATION ON.</li>
 * </ul>
 *
 * After the repair the flag authority fails closed: unsupported capabilities are
 * reported disabled and cannot be enabled through an override or a bound value,
 * so an offline flag fetch cannot turn on a capability the release cannot serve.
 */
class FeatureFlagCapabilityPostureTest {

	private FeatureFlagProperties properties;
	private FeatureFlagService service;

	@BeforeEach
	void setUp() {
		this.properties = new FeatureFlagProperties();
		this.service = new FeatureFlagService(this.properties);
	}

	@Test
	void unsupportedCapabilitiesDefaultOffInTheAdvertisedSnapshot() {
		assertThat(this.service.isEnabled(FeatureFlagName.MULTI_ITEM)).isFalse();
		assertThat(this.service.isEnabled(FeatureFlagName.NATURAL_LANGUAGE_STUDIO)).isFalse();
		assertThat(this.service.isEnabled(FeatureFlagName.HD_GENERATION)).isFalse();
		assertThat(this.service.isEnabled(FeatureFlagName.NEW_QUALITY_GATE)).isFalse();

		assertThat(this.service.snapshot().get(FeatureFlagName.MULTI_ITEM)).isFalse();
		assertThat(this.service.snapshot().get(FeatureFlagName.HD_GENERATION)).isFalse();
	}

	@Test
	void remoteOverrideCannotEnableAnUnsupportedCapability() {
		// A remote/offline flag payload that tries to switch the capability on must
		// not make it reachable: the authority refuses the enable. Pre-repair this
		// override succeeded and isEnabled reported true.
		assertThatThrownBy(() -> this.service.override(FeatureFlagName.MULTI_ITEM, true))
			.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> this.service.override(FeatureFlagName.HD_GENERATION, true))
			.isInstanceOf(UnsupportedOperationException.class);

		assertThat(this.service.isEnabled(FeatureFlagName.MULTI_ITEM)).isFalse();
		assertThat(this.service.isEnabled(FeatureFlagName.HD_GENERATION)).isFalse();
	}

	@Test
	void boundConfigurationCannotEnableAnUnsupportedCapability() {
		// A properties file / environment value that turns the flag on must not make
		// it reachable. Pre-repair this reported true.
		this.properties.setMultiItem(true);
		this.properties.setHdGeneration(true);
		this.properties.setNewQualityGate(true);
		// NATURAL_LANGUAGE_STUDIO is deliberately absent here: C21 gave it a mounted
		// backing path, so it is supported-but-default-OFF (see FeatureFlagServiceTest)
		// rather than hard-unsupported, and this test covers only the hard-unsupported
		// posture.

		assertThat(this.properties.isEnabled(FeatureFlagName.MULTI_ITEM)).isFalse();
		assertThat(this.service.isEnabled(FeatureFlagName.MULTI_ITEM)).isFalse();
		assertThat(this.service.isEnabled(FeatureFlagName.HD_GENERATION)).isFalse();
		assertThat(this.service.isEnabled(FeatureFlagName.NEW_QUALITY_GATE)).isFalse();
	}

	@Test
	void supportedCapabilitiesStillReportTheirBoundValue() {
		assertThat(this.service.isEnabled(FeatureFlagName.TRYON_MAX)).isTrue();
		assertThat(this.service.isEnabled(FeatureFlagName.SHARE_IMPORT_ANDROID)).isTrue();
		assertThat(this.service.isEnabled(FeatureFlagName.SHARE_IMPORT_IOS)).isTrue();

		this.service.override(FeatureFlagName.TRYON_MAX, false);
		assertThat(this.service.isEnabled(FeatureFlagName.TRYON_MAX)).isFalse();
	}
}
