package com.aifashionvault.platform.environment;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers CloudWatchMetricsProperties so the management.cloudwatch2.metrics.export
 * block in application.yaml is bound once and consumed by CloudWatchMetricsConfiguration.
 * Living in platform/environment beside VaultEnvironmentProperties keeps every
 * environment/property binding in one package; the registry consumer in
 * platform/observability reads the record, not the yaml, so there is one source of truth.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CloudWatchMetricsProperties.class)
public class CloudWatchMetricsPropertiesConfiguration {
}
