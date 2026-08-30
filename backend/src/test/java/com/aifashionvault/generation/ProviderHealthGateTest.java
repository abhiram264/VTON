package com.aifashionvault.generation;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.aifashionvault.generation.vton.ProviderKey;
import com.aifashionvault.platform.generation.StaticProviderHealthGate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Deterministic provider-health gate behavior (Q56): the gate only decides
 * new-execution eligibility, an empty healthy set means every provider is
 * allowed, operator marks toggle eligibility, and {@link ProviderKey}
 * normalization is honored.
 */
class ProviderHealthGateTest {

	private static final ProviderKey FASHN = new ProviderKey("fashn");
	private static final ProviderKey OTHER_PROVIDER = new ProviderKey("kolors");

	@Test
	void emptyHealthySetAllowsEveryProviderByDefault() {
		StaticProviderHealthGate gate = new StaticProviderHealthGate(Set.of());

		assertThat(gate.allowsNewExecution(FASHN)).isTrue();
		assertThat(gate.allowsNewExecution(OTHER_PROVIDER)).isTrue();
	}

	@Test
	void emptyHealthySetBlocksOnlyProvidersMarkedUnavailable() {
		StaticProviderHealthGate gate = new StaticProviderHealthGate(Set.of());
		gate.markUnavailable(FASHN);

		assertThat(gate.allowsNewExecution(FASHN)).isFalse();
		assertThat(gate.allowsNewExecution(OTHER_PROVIDER)).isTrue();
	}

	@Test
	void explicitHealthySetAllowsOnlyListedProviders() {
		StaticProviderHealthGate gate = new StaticProviderHealthGate(Set.of(FASHN));

		assertThat(gate.allowsNewExecution(FASHN)).isTrue();
		assertThat(gate.allowsNewExecution(OTHER_PROVIDER)).isFalse();
	}

	@Test
	void markUnavailableAndAvailableToggleEligibility() {
		StaticProviderHealthGate gate = new StaticProviderHealthGate(Set.of(FASHN));
		gate.markUnavailable(FASHN);

		assertThat(gate.allowsNewExecution(FASHN)).isFalse();

		gate.markAvailable(FASHN);

		assertThat(gate.allowsNewExecution(FASHN)).isTrue();
	}

	@Test
	void markAvailableRestoresProvidersAfterDefaultAllowAll() {
		StaticProviderHealthGate gate = new StaticProviderHealthGate(Set.of());
		gate.markUnavailable(FASHN);

		assertThat(gate.allowsNewExecution(FASHN)).isFalse();

		gate.markAvailable(FASHN);

		assertThat(gate.allowsNewExecution(FASHN)).isTrue();
		assertThat(gate.allowsNewExecution(OTHER_PROVIDER)).isTrue();
	}

	@Test
	void providerKeyNormalizationIsRespected() {
		StaticProviderHealthGate gate = new StaticProviderHealthGate(Set.of(new ProviderKey("  FASHN ")));

		assertThat(gate.allowsNewExecution(new ProviderKey("fashn"))).isTrue();

		gate.markUnavailable(new ProviderKey("FASHN"));

		assertThat(gate.allowsNewExecution(new ProviderKey("fashn"))).isFalse();
	}

	@Test
	void constructorRejectsNullSetAndNullElements() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new StaticProviderHealthGate(null));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new StaticProviderHealthGate(java.util.Collections.singleton(null)));
	}

	@Test
	void operationsRejectNullProvider() {
		StaticProviderHealthGate gate = new StaticProviderHealthGate(Set.of());

		assertThatIllegalArgumentException()
			.isThrownBy(() -> gate.allowsNewExecution(null));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> gate.markUnavailable(null));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> gate.markAvailable(null));
	}
}
