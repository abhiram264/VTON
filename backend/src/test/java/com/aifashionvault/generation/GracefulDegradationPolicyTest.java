package com.aifashionvault.generation;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import com.aifashionvault.generation.vton.InputSizeLimit;
import com.aifashionvault.generation.vton.InputTransferMethod;
import com.aifashionvault.generation.vton.ItemCategory;
import com.aifashionvault.generation.vton.ModeCapabilities;
import com.aifashionvault.generation.vton.OutputDeliveryMethod;
import com.aifashionvault.generation.vton.ProviderCapabilities;
import com.aifashionvault.generation.vton.ProviderKey;
import com.aifashionvault.generation.vton.SubmissionReplaySafety;
import com.aifashionvault.generation.vton.VtonMode;
import com.aifashionvault.shared.TerminalReason;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Contract tests for {@link GracefulDegradationPolicy}:
 * verifies §86 order (mode first, then provider fallback, then terminal),
 * fallback-capable provider calculation, null/duplicate validation invariants,
 * and deterministic evaluation.
 */
class GracefulDegradationPolicyTest {

	private static final ProviderKey PREFERRED = new ProviderKey("fashn");
	private static final ProviderKey FALLBACK = new ProviderKey("kolors");
	private static final ProviderKey TERTIARY = new ProviderKey("mock-vton");

	private static final VtonMode MODE_HD = new VtonMode("hd");
	private static final VtonMode MODE_STANDARD = new VtonMode("standard");

	private final GracefulDegradationPolicy policy = GracefulDegradationPolicy.defaultPolicy();

	private static ProviderCapabilities hdAndStandardCapabilities() {
		Map<VtonMode, ModeCapabilities> modes = Map.of(
			MODE_HD, new ModeCapabilities(Set.of(new ItemCategory("clothing")), 1, true, InputSizeLimit.unknown()),
			MODE_STANDARD, new ModeCapabilities(Set.of(new ItemCategory("clothing")), 1, true, InputSizeLimit.unknown())
		);
		return new ProviderCapabilities(
			modes,
			Set.of(InputTransferMethod.BOUNDED_INLINE_DATA),
			Set.of(OutputDeliveryMethod.EPHEMERAL_PROVIDER_READ),
			SubmissionReplaySafety.SAME_KEY_SAFE
		);
	}

	private static ProviderCapabilities standardOnlyCapabilities() {
		Map<VtonMode, ModeCapabilities> modes = Map.of(
			MODE_STANDARD, new ModeCapabilities(Set.of(new ItemCategory("clothing")), 1, true, InputSizeLimit.unknown())
		);
		return new ProviderCapabilities(
			modes,
			Set.of(InputTransferMethod.BOUNDED_INLINE_DATA),
			Set.of(OutputDeliveryMethod.EPHEMERAL_PROVIDER_READ),
			SubmissionReplaySafety.SAME_KEY_SAFE
		);
	}

	private static ProviderCapabilities emptyCapabilities() {
		// Minimum valid ProviderCapabilities has at least one mode
		Map<VtonMode, ModeCapabilities> modes = Map.of(
			new VtonMode("preview"), new ModeCapabilities(Set.of(new ItemCategory("clothing")), 1, true, InputSizeLimit.unknown())
		);
		return new ProviderCapabilities(
			modes,
			Set.of(InputTransferMethod.BOUNDED_INLINE_DATA),
			Set.of(OutputDeliveryMethod.EPHEMERAL_PROVIDER_READ),
			SubmissionReplaySafety.SAME_KEY_SAFE
		);
	}

	@Test
	void hdRequestedFlagOnPreferredCapableReturnsProviderSelectedWithModeNotDegraded() {
		DegradationRequest request = new DegradationRequest(
			"HD",
			true,
			List.of(PREFERRED, FALLBACK),
			key -> true,
			key -> hdAndStandardCapabilities()
		);

		DegradationAction action = policy.resolve(request);

		assertThat(action).isEqualTo(new DegradationAction.ProviderSelected(PREFERRED, false));
	}

	@Test
	void hdRequestedFlagOffReturnsModeDegradedBeforeProviderSwitch() {
		DegradationRequest request = new DegradationRequest(
			"HD",
			false,
			List.of(PREFERRED, FALLBACK),
			key -> true,
			key -> hdAndStandardCapabilities()
		);

		DegradationAction action = policy.resolve(request);

		assertThat(action).isEqualTo(new DegradationAction.ModeDegraded("HD", "STANDARD"));
	}

	@Test
	void hdRequestedFlagOnPreferredLacksHdCapabilityReturnsModeDegradedBeforeProviderSwitch() {
		// Preferred supports only standard; Fallback supports HD and standard.
		// Per §86 order: mode degradation happens before provider switching!
		DegradationRequest request = new DegradationRequest(
			"HD",
			true,
			List.of(PREFERRED, FALLBACK),
			key -> true,
			key -> key.equals(PREFERRED) ? standardOnlyCapabilities() : hdAndStandardCapabilities()
		);

		DegradationAction action = policy.resolve(request);

		assertThat(action).isEqualTo(new DegradationAction.ModeDegraded("HD", "STANDARD"));
	}

	@Test
	void preferredUnhealthyFallbackHealthyAndCapableSelectsFallback() {
		DegradationRequest request = new DegradationRequest(
			"STANDARD",
			true,
			List.of(PREFERRED, FALLBACK),
			key -> key.equals(FALLBACK),
			key -> standardOnlyCapabilities()
		);

		DegradationAction action = policy.resolve(request);

		assertThat(action).isEqualTo(new DegradationAction.ProviderSelected(FALLBACK, false));
	}

	@Test
	void preferredHealthyButIncapableFallbackCapableSelectsFallback() {
		// Preferred lacks standard capability, fallback has standard capability
		DegradationRequest request = new DegradationRequest(
			"STANDARD",
			true,
			List.of(PREFERRED, FALLBACK),
			key -> true,
			key -> key.equals(PREFERRED) ? emptyCapabilities() : standardOnlyCapabilities()
		);

		DegradationAction action = policy.resolve(request);

		assertThat(action).isEqualTo(new DegradationAction.ProviderSelected(FALLBACK, false));
	}

	@Test
	void allCandidatesUnhealthyReturnsNoEligibleProvider() {
		DegradationRequest request = new DegradationRequest(
			"STANDARD",
			true,
			List.of(PREFERRED, FALLBACK),
			key -> false,
			key -> standardOnlyCapabilities()
		);

		DegradationAction action = policy.resolve(request);

		assertThat(action).isEqualTo(new DegradationAction.NoEligibleProvider(TerminalReason.NO_ELIGIBLE_PROVIDER));
	}

	@Test
	void allCandidatesIncapableReturnsNoEligibleProvider() {
		DegradationRequest request = new DegradationRequest(
			"STANDARD",
			true,
			List.of(PREFERRED, FALLBACK),
			key -> true,
			key -> emptyCapabilities()
		);

		DegradationAction action = policy.resolve(request);

		assertThat(action).isEqualTo(new DegradationAction.NoEligibleProvider(TerminalReason.NO_ELIGIBLE_PROVIDER));
	}

	@Test
	void blankOrNullRequestedModeDefaultsToStandard() {
		DegradationRequest requestNull = new DegradationRequest(
			null,
			true,
			List.of(PREFERRED),
			key -> true,
			key -> standardOnlyCapabilities()
		);
		DegradationRequest requestBlank = new DegradationRequest(
			"   ",
			true,
			List.of(PREFERRED),
			key -> true,
			key -> standardOnlyCapabilities()
		);

		assertThat(policy.resolve(requestNull))
			.isEqualTo(new DegradationAction.ProviderSelected(PREFERRED, false));
		assertThat(policy.resolve(requestBlank))
			.isEqualTo(new DegradationAction.ProviderSelected(PREFERRED, false));
	}

	@Test
	void fallbackCapableProviderAvailableReturnsTrueWhenEligibleFallbackExists() {
		DegradationRequest request = new DegradationRequest(
			"STANDARD",
			true,
			List.of(PREFERRED, FALLBACK, TERTIARY),
			key -> !key.equals(PREFERRED), // preferred unhealthy, fallback healthy
			key -> standardOnlyCapabilities()
		);

		assertThat(policy.fallbackCapableProviderAvailable(request)).isTrue();
	}

	@Test
	void fallbackCapableProviderAvailableReturnsFalseWhenNoEligibleFallbackExists() {
		DegradationRequest requestOnlyPreferred = new DegradationRequest(
			"STANDARD",
			true,
			List.of(PREFERRED),
			key -> true,
			key -> standardOnlyCapabilities()
		);
		DegradationRequest requestFallbackUnhealthy = new DegradationRequest(
			"STANDARD",
			true,
			List.of(PREFERRED, FALLBACK),
			key -> key.equals(PREFERRED), // fallback unhealthy
			key -> standardOnlyCapabilities()
		);
		DegradationRequest requestFallbackIncapable = new DegradationRequest(
			"STANDARD",
			true,
			List.of(PREFERRED, FALLBACK),
			key -> true,
			key -> key.equals(PREFERRED) ? standardOnlyCapabilities() : emptyCapabilities()
		);

		assertThat(policy.fallbackCapableProviderAvailable(requestOnlyPreferred)).isFalse();
		assertThat(policy.fallbackCapableProviderAvailable(requestFallbackUnhealthy)).isFalse();
		assertThat(policy.fallbackCapableProviderAvailable(requestFallbackIncapable)).isFalse();
	}

	@Test
	void fallbackCapableProviderAvailableRespectsEffectiveModeDegradation() {
		// HD requested but flag off -> effective mode is STANDARD
		// Fallback supports STANDARD, does not support HD -> fallback is capable of effective mode!
		DegradationRequest request = new DegradationRequest(
			"HD",
			false,
			List.of(PREFERRED, FALLBACK),
			key -> true,
			key -> standardOnlyCapabilities()
		);

		assertThat(policy.fallbackCapableProviderAvailable(request)).isTrue();
	}

	@Test
	void deterministicResolutionProducesIdenticalOutcomesAcrossInvocations() {
		DegradationRequest request = new DegradationRequest(
			"STANDARD",
			true,
			List.of(PREFERRED, FALLBACK),
			key -> true,
			key -> standardOnlyCapabilities()
		);

		DegradationAction first = policy.resolve(request);
		DegradationAction second = policy.resolve(request);

		assertThat(first).isEqualTo(second);
		assertThat(policy.fallbackCapableProviderAvailable(request))
			.isEqualTo(policy.fallbackCapableProviderAvailable(request));
	}

	@Test
	void validationEnforcesNonNullAndNoDuplicateCandidates() {
		Predicate<ProviderKey> healthy = key -> true;
		Function<ProviderKey, ProviderCapabilities> caps = key -> standardOnlyCapabilities();

		assertThatIllegalArgumentException()
			.isThrownBy(() -> new DegradationRequest("STANDARD", true, null, healthy, caps))
			.withMessageContaining("orderedCandidates is required");

		assertThatIllegalArgumentException()
			.isThrownBy(() -> {
				List<ProviderKey> listWithNull = new java.util.ArrayList<>();
				listWithNull.add(PREFERRED);
				listWithNull.add(null);
				new DegradationRequest("STANDARD", true, listWithNull, healthy, caps);
			})
			.withMessageContaining("candidate must not be null");

		assertThatIllegalArgumentException()
			.isThrownBy(() -> new DegradationRequest("STANDARD", true, List.of(PREFERRED, PREFERRED), healthy, caps))
			.withMessageContaining("duplicate candidate");

		assertThatIllegalArgumentException()
			.isThrownBy(() -> new DegradationRequest("STANDARD", true, List.of(PREFERRED), null, caps))
			.withMessageContaining("providerHealthy predicate is required");

		assertThatIllegalArgumentException()
			.isThrownBy(() -> new DegradationRequest("STANDARD", true, List.of(PREFERRED), healthy, null))
			.withMessageContaining("capabilities function is required");

		assertThatIllegalArgumentException()
			.isThrownBy(() -> policy.resolve(null))
			.withMessageContaining("request is required");

		assertThatIllegalArgumentException()
			.isThrownBy(() -> policy.fallbackCapableProviderAvailable(null))
			.withMessageContaining("request is required");
	}

	@Test
	void degradationOrderEnumMatchesDeclarationOrder() {
		assertThat(DegradationOrder.values())
			.containsExactly(
				DegradationOrder.DEGRADE_MODE_TO_STANDARD,
				DegradationOrder.USE_FALLBACK_PROVIDER,
				DegradationOrder.SAVE_VAULT_ITEM
			);
	}
}
