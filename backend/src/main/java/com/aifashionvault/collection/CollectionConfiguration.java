package com.aifashionvault.collection;

import javax.sql.DataSource;

import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CollectionConfiguration {
	@Bean
	FactoryBean<CollectionStore> collectionStore(ObjectProvider<DataSource> dataSources) {
		return new FactoryBean<>() {
			@Override public CollectionStore getObject() {
				DataSource source = dataSources.getIfAvailable();
				return source == null ? null : new JdbcCollectionStore(source);
			}
			@Override public Class<?> getObjectType() { return CollectionStore.class; }
			@Override public boolean isSingleton() { return true; }
		};
	}
}
