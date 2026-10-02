package com.aifashionvault.platform.web;

import javax.sql.DataSource;

import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.aifashionvault.look.LookStore;
import com.aifashionvault.platform.studio.StudioDraftCloner;
import com.aifashionvault.studio.JdbcStudioStore;

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

	/** Clone-from-Look needs the Look read seam and the studio DataSource; null without both. */
	@Bean
	FactoryBean<StudioDraftCloner> studioDraftCloner(
		ObjectProvider<LookStore> lookStores,
		ObjectProvider<DataSource> sources
	) {
		return new FactoryBean<>() {
			@Override
			public StudioDraftCloner getObject() {
				LookStore looks = lookStores.getIfAvailable();
				DataSource source = sources.getIfAvailable();
				return looks == null || source == null ? null : new StudioDraftCloner(looks, source);
			}

			@Override
			public Class<?> getObjectType() {
				return StudioDraftCloner.class;
			}

			@Override
			public boolean isSingleton() {
				return true;
			}
		};
	}
}
