package com.aifashionvault.user;

import java.time.Clock;

import javax.sql.DataSource;

import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class IdentityProvisioningConfiguration {

	@Bean
	FactoryBean<IdentityProvisioning> identityProvisioning(ObjectProvider<DataSource> dataSources) {
		return new IdentityProvisioningFactoryBean(dataSources);
	}

	static final class IdentityProvisioningFactoryBean implements FactoryBean<IdentityProvisioning> {

		private final ObjectProvider<DataSource> dataSources;

		IdentityProvisioningFactoryBean(ObjectProvider<DataSource> dataSources) {
			this.dataSources = dataSources;
		}

		@Override
		public IdentityProvisioning getObject() {
			DataSource dataSource = this.dataSources.getIfAvailable();
			if (dataSource == null) {
				return null;
			}
			return new JdbcIdentityProvisioning(dataSource, Clock.systemUTC());
		}

		@Override
		public Class<?> getObjectType() {
			return IdentityProvisioning.class;
		}

		@Override
		public boolean isSingleton() {
			return true;
		}
	}
}
