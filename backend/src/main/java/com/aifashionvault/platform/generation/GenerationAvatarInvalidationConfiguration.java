package com.aifashionvault.platform.generation;

import javax.sql.DataSource;

import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class GenerationAvatarInvalidationConfiguration {

	@Bean
	FactoryBean<GenerationAvatarInvalidation> generationAvatarInvalidation(
		ObjectProvider<DataSource> dataSources) {
		return new GenerationAvatarInvalidationFactoryBean(dataSources);
	}

	static final class GenerationAvatarInvalidationFactoryBean
		implements FactoryBean<GenerationAvatarInvalidation> {

		private final ObjectProvider<DataSource> dataSources;

		GenerationAvatarInvalidationFactoryBean(ObjectProvider<DataSource> dataSources) {
			this.dataSources = dataSources;
		}

		@Override
		public GenerationAvatarInvalidation getObject() {
			DataSource dataSource = this.dataSources.getIfAvailable();
			if (dataSource == null) {
				return null;
			}
			return new GenerationAvatarInvalidation(dataSource);
		}

		@Override
		public Class<?> getObjectType() {
			return GenerationAvatarInvalidation.class;
		}

		@Override
		public boolean isSingleton() {
			return true;
		}
	}
}
