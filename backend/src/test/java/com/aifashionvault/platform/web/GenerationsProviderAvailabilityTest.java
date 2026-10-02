package com.aifashionvault.platform.web;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import com.aifashionvault.generation.vton.DefaultProviderRegistry;
import com.aifashionvault.generation.vton.ProviderRegistry;
import com.aifashionvault.platform.generation.RecordingVtonProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The create-path provider gate must key off the composed {@link ProviderRegistry}
 * bean, not a raw API-key predicate. A keyless registered provider — the offline echo
 * backstop, which has no key by design — must be reachable, and an empty registry
 * must still fail closed.
 */
class GenerationsProviderAvailabilityTest {

	@Test
	void registeredProviderIsAvailableEvenWithoutAnApiKey() {
		DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
		RecordingVtonProvider provider = new RecordingVtonProvider();
		beans.registerSingleton("providerRegistry",
			new DefaultProviderRegistry(List.of(provider), provider.providerKey(), java.util.Map.of()));

		assertThat(GenerationsController.providerAvailable(beans.getBeanProvider(ProviderRegistry.class))).isTrue();
	}

	@Test
	void noRegisteredProviderIsUnavailable() {
		DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
		ObjectProvider<ProviderRegistry> none = beans.getBeanProvider(ProviderRegistry.class);

		assertThat(GenerationsController.providerAvailable(none)).isFalse();
	}
}
