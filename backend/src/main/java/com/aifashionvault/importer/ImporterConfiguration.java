package com.aifashionvault.importer;

import javax.sql.DataSource;

import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Production wiring for the importer purge port. Registered through
 * FactoryBeans (vton-test-datasource-beans convention): a scanned
 * {@code @ConditionalOnBean(DataSource.class)} configuration is evaluated
 * before the auto-configured DataSource exists, so the beans would never
 * register. The FactoryBeans resolve the late DataSource (and the platform
 * ImportMediaAccess bean) at creation time and yield no bean without them.
 * The ImportMediaAccess bean itself is registered platform-side, following
 * the ObjectStoreAvatarMediaAccess precedent: no feature module may depend
 * on platform.objectstore.
 */
@Configuration(proxyBeanMethods = false)
public class ImporterConfiguration {

	@Bean
	FactoryBean<JdbcShareImportStore> jdbcShareImportStore(ObjectProvider<DataSource> dataSources) {
		return new JdbcShareImportStoreFactoryBean(dataSources);
	}

	@Bean
	FactoryBean<ShareImportAccountMediaPurge> shareImportAccountMediaPurge(
			ObjectProvider<DataSource> dataSources,
			ObjectProvider<ImportMediaAccess> mediaAccesses) {
		return new ShareImportAccountMediaPurgeFactoryBean(dataSources, mediaAccesses);
	}

	static final class JdbcShareImportStoreFactoryBean implements FactoryBean<JdbcShareImportStore> {

		private final ObjectProvider<DataSource> dataSources;

		JdbcShareImportStoreFactoryBean(ObjectProvider<DataSource> dataSources) {
			this.dataSources = dataSources;
		}

		@Override
		public JdbcShareImportStore getObject() {
			DataSource dataSource = this.dataSources.getIfAvailable();
			if (dataSource == null) {
				return null;
			}
			return new JdbcShareImportStore(dataSource);
		}

		@Override
		public Class<?> getObjectType() {
			return JdbcShareImportStore.class;
		}

		@Override
		public boolean isSingleton() {
			return true;
		}
	}

	static final class ShareImportAccountMediaPurgeFactoryBean implements FactoryBean<ShareImportAccountMediaPurge> {

		private final ObjectProvider<DataSource> dataSources;
		private final ObjectProvider<ImportMediaAccess> mediaAccesses;

		ShareImportAccountMediaPurgeFactoryBean(
				ObjectProvider<DataSource> dataSources,
				ObjectProvider<ImportMediaAccess> mediaAccesses) {
			this.dataSources = dataSources;
			this.mediaAccesses = mediaAccesses;
		}

		@Override
		public ShareImportAccountMediaPurge getObject() {
			DataSource dataSource = this.dataSources.getIfAvailable();
			ImportMediaAccess mediaAccess = this.mediaAccesses.getIfAvailable();
			if (dataSource == null || mediaAccess == null) {
				return null;
			}
			return new ShareImportAccountMediaPurge(new JdbcShareImportStore(dataSource), mediaAccess);
		}

		@Override
		public Class<?> getObjectType() {
			return ShareImportAccountMediaPurge.class;
		}

		@Override
		public boolean isSingleton() {
			return true;
		}
	}
}
