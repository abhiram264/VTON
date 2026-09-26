package com.aifashionvault.generation;

import javax.sql.DataSource;

import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class GenerationConfiguration {

	@Bean
	FactoryBean<JdbcGenerationStore> jdbcGenerationStore(ObjectProvider<DataSource> dataSources) {
		return new JdbcGenerationStoreFactoryBean(dataSources);
	}

	static final class JdbcGenerationStoreFactoryBean implements FactoryBean<JdbcGenerationStore> {

		private final ObjectProvider<DataSource> dataSources;

		JdbcGenerationStoreFactoryBean(ObjectProvider<DataSource> dataSources) {
			this.dataSources = dataSources;
		}

		@Override
		public JdbcGenerationStore getObject() {
			DataSource dataSource = dataSources.getIfAvailable();
			return dataSource == null ? null : new JdbcGenerationStore(dataSource);
		}

		@Override
		public Class<?> getObjectType() {
			return JdbcGenerationStore.class;
		}

		@Override
		public boolean isSingleton() {
			return true;
		}
	}
}
