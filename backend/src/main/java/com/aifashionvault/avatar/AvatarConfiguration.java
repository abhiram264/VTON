package com.aifashionvault.avatar;

import java.util.function.Supplier;

import javax.sql.DataSource;

import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AvatarConfiguration {

	@Bean
	FactoryBean<JdbcAvatarStore> jdbcAvatarStore(ObjectProvider<DataSource> dataSources) {
		return supplied(JdbcAvatarStore.class, () -> {
			DataSource dataSource = dataSources.getIfAvailable();
			return dataSource == null ? null : new JdbcAvatarStore(dataSource);
		});
	}

	@Bean
	SemanticInspector semanticInspector() {
		return new OnnxCpuSemanticInspector();
	}

	@Bean
	FactoryBean<AvatarPreflight> avatarPreflight(
			ObjectProvider<AvatarBytesInspector> inspectors,
			SemanticInspector semanticInspector) {
		return supplied(AvatarPreflight.class, () -> {
			AvatarBytesInspector inspector = inspectors.getIfAvailable();
			return inspector == null ? null : new AvatarPreflight(inspector, semanticInspector);
		});
	}

	@Bean
	FactoryBean<AvatarWorkflow> avatarWorkflow(
			ObjectProvider<JdbcAvatarStore> stores,
			ObjectProvider<AvatarMediaAccess> mediaAccesses,
			ObjectProvider<AvatarPreflight> preflights) {
		return supplied(AvatarWorkflow.class, () -> {
			JdbcAvatarStore store = stores.getIfAvailable();
			AvatarMediaAccess mediaAccess = mediaAccesses.getIfAvailable();
			AvatarPreflight preflight = preflights.getIfAvailable();
			return store == null || mediaAccess == null || preflight == null
				? null
				: new AvatarWorkflow(store, mediaAccess, preflight);
		});
	}

	@Bean
	FactoryBean<AvatarAccountMediaPurge> avatarAccountMediaPurge(
			ObjectProvider<JdbcAvatarStore> stores,
			ObjectProvider<AvatarMediaAccess> mediaAccesses) {
		return supplied(AvatarAccountMediaPurge.class, () -> {
			JdbcAvatarStore store = stores.getIfAvailable();
			AvatarMediaAccess mediaAccess = mediaAccesses.getIfAvailable();
			return store == null || mediaAccess == null
				? null
				: new AvatarAccountMediaPurge(store, mediaAccess);
		});
	}

	private static <T> FactoryBean<T> supplied(Class<T> type, Supplier<T> supplier) {
		return new FactoryBean<>() {
			@Override
			public T getObject() {
				return supplier.get();
			}

			@Override
			public Class<?> getObjectType() {
				return type;
			}

			@Override
			public boolean isSingleton() {
				return true;
			}
		};
	}
}
