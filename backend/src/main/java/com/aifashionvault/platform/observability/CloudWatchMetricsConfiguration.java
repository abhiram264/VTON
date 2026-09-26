package com.aifashionvault.platform.observability;

import com.aifashionvault.platform.environment.AppEnv;
import com.aifashionvault.platform.environment.CloudWatchMetricsProperties;
import com.aifashionvault.platform.environment.VaultEnvironmentProperties;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.cloudwatch2.CloudWatchConfig;
import io.micrometer.cloudwatch2.CloudWatchMeterRegistry;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;

/**
 * Constructs the Micrometer CloudWatch2 step registry so application metrics
 * actually reach CloudWatch. Spring Boot 4.1 ships NO CloudWatch export
 * auto-configuration (spring-boot-micrometer-metrics-4.1.0.jar's
 * AutoConfiguration.imports has no cloudwatch entry, verified), so the
 * management.cloudwatch2.metrics.export.* block in application.yaml has no
 * built-in reader — CloudWatchMetricsProperties is that reader and this bean
 * consumes it. AWS wiring mirrors the existing idiom in
 * ObjectStoreConfiguration/GenerationQueueConfiguration; it introduces no
 * vendor selection and no second credential mechanism.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "management.cloudwatch2.metrics.export", name = "enabled", havingValue = "true")
public class CloudWatchMetricsConfiguration {

	@Bean(destroyMethod = "close")
	CloudWatchMeterRegistry cloudWatchMeterRegistry(
		CloudWatchMetricsProperties export,
		VaultEnvironmentProperties properties
	) {
		CloudWatchConfig config = new CloudWatchConfig() {
			@Override
			public String namespace() {
				return export.namespace();
			}

			@Override
			public java.time.Duration step() {
				return export.step();
			}

			@Override
			public String get(String key) {
				return null;
			}
		};
		return new CloudWatchMeterRegistry(
			config,
			io.micrometer.core.instrument.Clock.SYSTEM,
			createCloudWatchAsyncClient(properties.env(), properties.aws())
		);
	}

	static CloudWatchAsyncClient createCloudWatchAsyncClient(AppEnv env, VaultEnvironmentProperties.Aws aws) {
		return CloudWatchAsyncClient.builder()
			.region(Region.of(aws.region()))
			.credentialsProvider(credentialsProvider(env, aws))
			.build();
	}

	private static AwsCredentialsProvider credentialsProvider(AppEnv env, VaultEnvironmentProperties.Aws aws) {
		if (env == AppEnv.development && !isBlank(aws.accessKeyId()) && !isBlank(aws.secretAccessKey())) {
			return StaticCredentialsProvider.create(AwsBasicCredentials.create(aws.accessKeyId(), aws.secretAccessKey()));
		}
		return DefaultCredentialsProvider.create();
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}
}
