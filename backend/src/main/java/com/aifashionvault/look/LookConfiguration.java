package com.aifashionvault.look;

import javax.sql.DataSource;

import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class LookConfiguration {

	@Bean
	FactoryBean<JdbcLookStore> jdbcLookStore(ObjectProvider<DataSource> dataSources) {
		return new JdbcLookStoreFactoryBean(dataSources);
	}

	static final class JdbcLookStoreFactoryBean implements FactoryBean<JdbcLookStore> {

		private final ObjectProvider<DataSource> dataSources;

		JdbcLookStoreFactoryBean(ObjectProvider<DataSource> dataSources) {
			this.dataSources = dataSources;
		}

		@Override
		public JdbcLookStore getObject() {
			DataSource dataSource = dataSources.getIfAvailable();
			return dataSource == null ? null : new JdbcLookStore(dataSource);
		}

		@Override
		public Class<?> getObjectType() {
			return JdbcLookStore.class;
		}

		@Override
		public boolean isSingleton() {
			return true;
		}
	}
}
