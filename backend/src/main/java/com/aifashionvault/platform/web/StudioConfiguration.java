package com.aifashionvault.platform.web;

import java.time.Clock;

import javax.sql.DataSource;

import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.aifashionvault.collection.CollectionStore;
import com.aifashionvault.look.LookStore;
import com.aifashionvault.platform.studio.StudioAgentToolAdapter;
import com.aifashionvault.platform.studio.StudioDraftCloner;
import com.aifashionvault.studio.JdbcStudioStore;
import com.aifashionvault.studio.agent.StudioAgentTools;
import com.aifashionvault.studio.agent.StudioCommandPlanner;
import com.aifashionvault.vault.JdbcVaultStore;

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

	/**
	 * The {@link StudioAgentTools} port over the real stores. Null unless every
	 * store it needs exists, so the natural-language route answers the honest 503
	 * {@code STUDIO_UNAVAILABLE} instead of planning against nothing.
	 */
	@Bean
	FactoryBean<StudioAgentTools> studioAgentTools(
		ObjectProvider<JdbcStudioStore> studioStores,
		ObjectProvider<DataSource> sources,
		ObjectProvider<CollectionStore> collectionStores,
		ObjectProvider<Clock> clocks
	) {
		return new FactoryBean<>() {
			@Override
			public StudioAgentTools getObject() {
				JdbcStudioStore studio = studioStores.getIfAvailable();
				DataSource source = sources.getIfAvailable();
				CollectionStore collections = collectionStores.getIfAvailable();
				if (studio == null || source == null || collections == null) {
					return null;
				}
				return new StudioAgentToolAdapter(
					studio, new JdbcVaultStore(source), collections, clocks.getIfAvailable(Clock::systemUTC));
			}

			@Override
			public Class<?> getObjectType() {
				return StudioAgentTools.class;
			}

			@Override
			public boolean isSingleton() {
				return true;
			}
		};
	}

	/** The deterministic text→tool-call planner; it only exists with the tool port. */
	@Bean
	FactoryBean<StudioCommandPlanner> studioCommandPlanner(ObjectProvider<StudioAgentTools> tools) {
		return new FactoryBean<>() {
			@Override
			public StudioCommandPlanner getObject() {
				StudioAgentTools port = tools.getIfAvailable();
				return port == null ? null : new StudioCommandPlanner(port);
			}

			@Override
			public Class<?> getObjectType() {
				return StudioCommandPlanner.class;
			}

			@Override
			public boolean isSingleton() {
				return true;
			}
		};
	}
}
