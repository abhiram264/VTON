package com.aifashionvault.privacy;

import java.time.Clock;
import javax.sql.DataSource;

import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the consent surface over the existing {@code consent} table. When no
 * {@link DataSource} exists (or the store bean is absent) the FactoryBeans yield
 * NullBeans, so no consent beans exist at all and the API reports the dependency as
 * unavailable instead of pretending consent was recorded.
 */
@Configuration(proxyBeanMethods = false)
class ConsentConfiguration {

	@Bean
	FactoryBean<ConsentStore> consentStore(ObjectProvider<DataSource> dataSources) {
		return new FactoryBean<>() {
			@Override
			public ConsentStore getObject() {
				DataSource source = dataSources.getIfAvailable();
				return source == null ? null : new JdbcConsentStore(source);
			}

			@Override
			public Class<?> getObjectType() {
				return ConsentStore.class;
			}

			@Override
			public boolean isSingleton() {
				return true;
			}
		};
	}

	@Bean
	FactoryBean<ConsentService> consentService(ObjectProvider<ConsentStore> stores) {
		return new FactoryBean<>() {
			@Override
			public ConsentService getObject() {
				ConsentStore store = stores.getIfAvailable();
				return store == null ? null : new ConsentService(store, Clock.systemUTC());
			}

			@Override
			public Class<?> getObjectType() {
				return ConsentService.class;
			}

			@Override
			public boolean isSingleton() {
				return true;
			}
		};
	}
}
