package com.aifashionvault.generation;

import java.time.Clock;

import javax.sql.DataSource;

import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class GenerationConfiguration {

	@Bean
	FactoryBean<JdbcGenerationStore> jdbcGenerationStore(ObjectProvider<DataSource> dataSources,
			ObjectProvider<AdmissionCapsProperties> admissionCaps) {
		return new JdbcGenerationStoreFactoryBean(dataSources, admissionCaps);
	}

	static final class JdbcGenerationStoreFactoryBean implements FactoryBean<JdbcGenerationStore> {

		private final ObjectProvider<DataSource> dataSources;
		private final ObjectProvider<AdmissionCapsProperties> admissionCaps;

		JdbcGenerationStoreFactoryBean(ObjectProvider<DataSource> dataSources,
				ObjectProvider<AdmissionCapsProperties> admissionCaps) {
			this.dataSources = dataSources;
			this.admissionCaps = admissionCaps;
		}

		@Override
		public JdbcGenerationStore getObject() {
			DataSource dataSource = dataSources.getIfAvailable();
			if (dataSource == null) {
				return null;
			}
			// C19: the caps come from configuration when it is present; the
			// declared fallback keeps slim contexts (no properties registrar)
			// behaving exactly as before.
			AdmissionCapsProperties caps = admissionCaps.getIfAvailable();
			AdmissionPolicy policy = caps == null ? AdmissionPolicy.DEFAULT : caps.toPolicy();
			return new JdbcGenerationStore(dataSource, Clock.systemUTC(), policy);
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
