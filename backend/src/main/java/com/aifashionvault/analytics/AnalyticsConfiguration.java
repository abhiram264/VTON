package com.aifashionvault.analytics;

import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Wires the analytics seam. Both beans are optional by construction (a null
 * {@link FactoryBean#getObject()} becomes a Spring NullBean), so an application
 * context without a {@link MeterRegistry} still starts and simply records
 * nothing — the same fail-safe shape the notification and collection
 * configurations use for their optional collaborators.
 *
 * <p>{@link MicrometerProductMetrics} is deliberately given no identity field:
 * the boundary counts an at-least-once stream and must not be turned into a
 * per-user store by a wiring change.
 */
@Configuration(proxyBeanMethods = false)
public class AnalyticsConfiguration {

	@Bean
	FactoryBean<ProductMetrics> productMetrics(ObjectProvider<MeterRegistry> registries) {
		return new FactoryBean<>() {

			@Override
			public ProductMetrics getObject() {
				MeterRegistry registry = registries.getIfAvailable();
				return registry == null ? null : new MicrometerProductMetrics(registry);
			}

			@Override
			public Class<?> getObjectType() {
				return ProductMetrics.class;
			}
		};
	}

	@Bean
	FactoryBean<AnalyticsEventPublisher> analyticsEventPublisher(ObjectProvider<ProductMetrics> metrics) {
		return new FactoryBean<>() {

			@Override
			public AnalyticsEventPublisher getObject() {
				ProductMetrics productMetrics = metrics.getIfAvailable();
				return productMetrics == null ? null : new MetricsAnalyticsEventPublisher(productMetrics);
			}

			@Override
			public Class<?> getObjectType() {
				return AnalyticsEventPublisher.class;
			}
		};
	}
}
