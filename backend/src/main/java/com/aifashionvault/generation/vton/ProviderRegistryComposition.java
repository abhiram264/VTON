package com.aifashionvault.generation.vton;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The ONE composed {@link ProviderRegistry} selection seam: collects every registered
 * {@link VtonProvider} and selects deterministically, in this order, against the
 * REGISTERED providers only:
 * <ol>
 * <li>{@link ProviderRoutingProperties#getForcedProvider() forced-provider} when it
 * names a registered provider;</li>
 * <li>otherwise {@code inferhub} when registered;</li>
 * <li>otherwise {@code fashn} when registered;</li>
 * <li>otherwise the alphabetically-first registered key (third-party providers only —
 * deterministic, never bean construction order).</li>
 * </ol>
 *
 * <p>When no provider bean exists the FactoryBean yields a NullBean, so NO
 * ProviderRegistry bean exists at all — the honest-unavailable posture. A
 * {@code @ConditionalOnBean(VtonProvider.class)} class-level gate would be evaluated
 * before the provider bean definitions are guaranteed to be registered (ordering-
 * sensitive for user configurations), so the null-FactoryBean precedent from
 * {@code GenerationProcessorConfiguration#factory} is used instead; the absence
 * assertion is proven by {@code InferHubConfigTest}.
 */
@Configuration(proxyBeanMethods = false)
public class ProviderRegistryComposition {

	@Bean
	FactoryBean<ProviderRegistry> providerRegistry(
		ObjectProvider<VtonProvider> providers,
		ObjectProvider<ProviderRoutingProperties> routingProperties
	) {
		return new FactoryBean<>() {
			@Override
			public ProviderRegistry getObject() {
				List<VtonProvider> registered = providers.orderedStream().toList();
				if (registered.isEmpty()) {
					return null;
				}
				return new DefaultProviderRegistry(
					registered,
					selectedKey(registered, routingProperties.getIfAvailable()),
					Map.of());
			}

			@Override
			public Class<?> getObjectType() {
				return ProviderRegistry.class;
			}

			@Override
			public boolean isSingleton() {
				return true;
			}
		};
	}

	static ProviderKey selectedKey(List<VtonProvider> registered, ProviderRoutingProperties properties) {
		ProviderKey forced = properties == null ? null : properties.forcedProvider().orElse(null);
		if (forced != null && registered.stream().anyMatch(provider -> provider.providerKey().equals(forced))) {
			return forced;
		}
		for (String key : List.of("inferhub", "fashn")) {
			ProviderKey candidate = new ProviderKey(key);
			if (registered.stream().anyMatch(provider -> provider.providerKey().equals(candidate))) {
				return candidate;
			}
		}
		return registered.stream()
			.map(VtonProvider::providerKey)
			.min((left, right) -> left.value().compareTo(right.value()))
			.orElseThrow();
	}
}
