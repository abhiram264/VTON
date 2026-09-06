package com.aifashionvault.generation.vton;

import java.time.Clock;

import javax.sql.DataSource;

import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Production wiring for the provider performance rollup (slice-73 D8).
 * Registered through a FactoryBean (vton-test-datasource-beans convention,
 * ImporterConfiguration precedent): a scanned
 * {@code @ConditionalOnBean(DataSource.class)} configuration is evaluated
 * before the auto-configured DataSource exists, so the store would never
 * register. The FactoryBean resolves the late DataSource at creation time and
 * yields no bean without it, which keeps DataSource-excluded test contexts
 * clean. The worker is a plain bean that no-ops when the store is absent.
 * {@code @EnableScheduling} already exists platform-side; nothing here adds
 * it.
 */
@Configuration(proxyBeanMethods = false)
public class VtonPerformanceConfiguration {

	@Bean
	FactoryBean<JdbcProviderPerformanceStore> jdbcProviderPerformanceStore(
			ObjectProvider<DataSource> dataSources,
			ObjectProvider<Clock> clocks) {
		return new JdbcProviderPerformanceStoreFactoryBean(dataSources, clocks);
	}

	@Bean
	ProviderPerformanceRecomputeWorker providerPerformanceRecomputeWorker(
			ObjectProvider<JdbcProviderPerformanceStore> stores,
			ObjectProvider<Clock> clocks) {
		return new ProviderPerformanceRecomputeWorker(stores, clocks.getIfAvailable(Clock::systemUTC));
	}

	static final class JdbcProviderPerformanceStoreFactoryBean
			implements FactoryBean<JdbcProviderPerformanceStore> {

		private final ObjectProvider<DataSource> dataSources;
		private final ObjectProvider<Clock> clocks;

		JdbcProviderPerformanceStoreFactoryBean(
				ObjectProvider<DataSource> dataSources,
				ObjectProvider<Clock> clocks) {
			this.dataSources = dataSources;
			this.clocks = clocks;
		}

		@Override
		public JdbcProviderPerformanceStore getObject() {
			DataSource dataSource = this.dataSources.getIfAvailable();
			if (dataSource == null) {
				return null;
			}
			return new JdbcProviderPerformanceStore(dataSource, this.clocks.getIfAvailable(Clock::systemUTC));
		}

		@Override
		public Class<?> getObjectType() {
			return JdbcProviderPerformanceStore.class;
		}

		@Override
		public boolean isSingleton() {
			return true;
		}
	}
}
