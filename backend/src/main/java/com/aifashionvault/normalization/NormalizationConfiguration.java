package com.aifashionvault.normalization;

import javax.sql.DataSource;

import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class NormalizationConfiguration {

	@Bean
	ProductNormalizer productNormalizer() {
		return new DeterministicProductNormalizer();
	}

	@Bean
	FactoryBean<JdbcProductCorrectionStore> jdbcProductCorrectionStore(ObjectProvider<DataSource> dataSources) {
		return new StoreFactoryBean(dataSources);
	}

	static final class StoreFactoryBean implements FactoryBean<JdbcProductCorrectionStore> {
		private final ObjectProvider<DataSource> dataSources;

		StoreFactoryBean(ObjectProvider<DataSource> dataSources) {
			this.dataSources = dataSources;
		}

		@Override
		public JdbcProductCorrectionStore getObject() {
			DataSource dataSource = dataSources.getIfAvailable();
			return dataSource == null ? null : new JdbcProductCorrectionStore(dataSource);
		}

		@Override
		public Class<?> getObjectType() {
			return JdbcProductCorrectionStore.class;
		}

		@Override
		public boolean isSingleton() {
			return true;
		}
	}
}
