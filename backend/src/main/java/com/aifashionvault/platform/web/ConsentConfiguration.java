package com.aifashionvault.platform.web;

import java.time.Clock;

import javax.sql.DataSource;

import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.aifashionvault.privacy.ConsentService;
import com.aifashionvault.privacy.ConsentStore;
import com.aifashionvault.privacy.JdbcConsentStore;

/**
 * Wires the C06 consent domain for the HTTP surface.
 *
 * <p>Late-binding FactoryBeans (the {@code GenerationProcessorConfiguration#factory}
 * precedent): the DataSource arrives after this scanned configuration is parsed,
 * so a parse-time {@code @ConditionalOnBean} would skip the whole graph. With no
 * DataSource both products resolve to no bean and {@link ConsentsController}
 * answers 503 CONSENT_UNAVAILABLE — the honest-unavailable posture, never a
 * fabricated grant.</p>
 *
 * <p>The recorded policy version is configuration
 * ({@code vault.privacy.consent-policy-version}) rather than a compiled
 * constant, so publishing a new policy is a deployment change with a visible
 * version string, and the version stored on each grant is the one in force when
 * that grant was made.</p>
 */
@Configuration(proxyBeanMethods = false)
public class ConsentConfiguration {

	@Bean
	FactoryBean<ConsentStore> consentStore(ObjectProvider<DataSource> dataSources) {
		return factory(ConsentStore.class, () -> {
			DataSource dataSource = dataSources.getIfAvailable();
			return dataSource == null ? null : new JdbcConsentStore(dataSource);
		});
	}

	@Bean
	FactoryBean<ConsentService> consentService(
		ObjectProvider<ConsentStore> stores,
		ObjectProvider<Clock> clocks,
		// A default is supplied here as well as in application.yaml: the
		// placeholder must resolve even for a context that loads neither the
		// main nor the test application.yaml, and an unresolved placeholder
		// takes down the whole context rather than just this bean.
		@Value("${vault.privacy.consent-policy-version:2026-10-03}") String policyVersion
	) {
		return factory(ConsentService.class, () -> {
			ConsentStore store = stores.getIfAvailable();
			if (store == null) {
				return null;
			}
			Clock clock = clocks.getIfAvailable(Clock::systemUTC);
			return new ConsentService(store, clock, policyVersion);
		});
	}

	private static <T> FactoryBean<T> factory(Class<T> objectType, java.util.function.Supplier<T> constructor) {
		return new FactoryBean<>() {
			@Override
			public T getObject() {
				return constructor.get();
			}

			@Override
			public Class<?> getObjectType() {
				return objectType;
			}

			@Override
			public boolean isSingleton() {
				return true;
			}
		};
	}
}
