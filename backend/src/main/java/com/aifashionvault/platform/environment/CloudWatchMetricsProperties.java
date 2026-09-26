package com.aifashionvault.platform.environment;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the management.cloudwatch2.metrics.export block from application.yaml.
 * Spring Boot 4.1 ships no CloudWatch export auto-configuration or properties
 * class (spring-boot-micrometer-metrics-4.1.0.jar contains no cloudwatch
 * package, verified), so this small record is the single reader of that block;
 * CloudWatchMetricsConfiguration consumes it to build the registry.
 */
@ConfigurationProperties(prefix = "management.cloudwatch2.metrics.export")
public record CloudWatchMetricsProperties(
	boolean enabled,
	String namespace,
	Duration step
) {
	public CloudWatchMetricsProperties {
		if (namespace == null || namespace.isBlank()) {
			namespace = "AI-Fashion-Vault";
		}
		if (step == null) {
			step = Duration.ofMinutes(1);
		}
	}
}
