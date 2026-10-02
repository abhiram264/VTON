package com.aifashionvault.studio;

import javax.sql.DataSource;

import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Studio persistence wiring. The store is only created when a {@link DataSource}
 * exists; without one the FactoryBean yields a NullBean, so
 * {@link StudioController} answers the honest 503 {@code STUDIO_UNAVAILABLE}
 * instead of a silent in-memory default. Same null-FactoryBean precedent as
 * {@code VaultConfiguration} and {@code ProviderRegistryComposition}.
 */
@Configuration(proxyBeanMethods = false)
class StudioConfiguration {

	@Bean
	FactoryBean<JdbcStudioStore> studioSessionStore(ObjectProvider<DataSource> sources) {
		return new FactoryBean<>() {
			@Override
			public JdbcStudioStore getObject() {
				DataSource source = sources.getIfAvailable();
				return source == null ? null : new JdbcStudioStore(source);
			}

			@Override
			public Class<?> getObjectType() {
				return JdbcStudioStore.class;
			}

			@Override
			public boolean isSingleton() {
				return true;
			}
		};
	}
}
