package com.aifashionvault.user;

import java.time.Clock;

import javax.sql.DataSource;

import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.aifashionvault.shared.AccountMediaPurge;

@Configuration(proxyBeanMethods = false)
public class IdentityProvisioningConfiguration {

	@Bean
	FactoryBean<IdentityProvisioning> identityProvisioning(
		ObjectProvider<DataSource> dataSources,
		ObjectProvider<AccountMediaPurge> mediaPurges) {
		return new IdentityProvisioningFactoryBean(dataSources, mediaPurges);
	}

	static final class IdentityProvisioningFactoryBean implements FactoryBean<IdentityProvisioning> {

		private final ObjectProvider<DataSource> dataSources;
		private final ObjectProvider<AccountMediaPurge> mediaPurges;

		IdentityProvisioningFactoryBean(
			ObjectProvider<DataSource> dataSources,
			ObjectProvider<AccountMediaPurge> mediaPurges) {
			this.dataSources = dataSources;
			this.mediaPurges = mediaPurges;
		}

		@Override
		public IdentityProvisioning getObject() {
			DataSource dataSource = this.dataSources.getIfAvailable();
			if (dataSource == null) {
				return null;
			}
			return new JdbcIdentityProvisioning(dataSource, Clock.systemUTC(), this.mediaPurges);
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
