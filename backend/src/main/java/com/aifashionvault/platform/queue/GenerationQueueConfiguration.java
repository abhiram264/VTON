package com.aifashionvault.platform.queue;

import java.net.URI;
import java.time.Clock;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.aifashionvault.platform.environment.AppEnv;
import com.aifashionvault.platform.environment.VaultEnvironmentProperties;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

@Configuration(proxyBeanMethods = false)
public class GenerationQueueConfiguration {

	@Bean(destroyMethod = "close")
	SqsClient sqsClient(VaultEnvironmentProperties properties) {
		return createSqsClient(wiring(properties));
	}

	@Bean
	SqsGenerationWorkQueue sqsGenerationWorkQueue(SqsClient sqsClient, VaultEnvironmentProperties properties) {
		return new SqsGenerationWorkQueue(sqsClient, properties);
	}

	@Bean
	@ConditionalOnBean(DataSource.class)
	JdbcWorkOutboxStore jdbcWorkOutboxStore(DataSource dataSource, ObjectProvider<Clock> clocks) {
		return new JdbcWorkOutboxStore(dataSource, clocks.getIfAvailable(Clock::systemUTC));
	}

	@Bean
	@ConditionalOnBean(WorkOutboxStore.class)
	@ConditionalOnProperty(name = "vault.generation.queue.relay-enabled", havingValue = "true")
	GenerationWorkOutboxRelay generationWorkOutboxRelay(
		WorkOutboxStore store,
		SqsGenerationWorkQueue queue
	) {
		return new GenerationWorkOutboxRelay(store, queue);
	}

	static SqsClientWiring wiring(VaultEnvironmentProperties properties) {
		VaultEnvironmentProperties.Aws aws = properties.aws();
		AppEnv env = properties.env();
		URI endpointOverride = null;
		if (env == AppEnv.development && !isBlank(aws.endpointUrlSqs())) {
			endpointOverride = URI.create(aws.endpointUrlSqs());
		}
		return new SqsClientWiring(
			Region.of(aws.region()),
			endpointOverride,
			credentialsProvider(env, aws));
	}

	static SqsClient createSqsClient(SqsClientWiring wiring) {
		SqsClientBuilder builder = SqsClient.builder()
			.region(wiring.region())
			.credentialsProvider(wiring.credentialsProvider());
		if (wiring.endpointOverride() != null) {
			builder.endpointOverride(wiring.endpointOverride());
		}
		return builder.build();
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

	record SqsClientWiring(
		Region region,
		URI endpointOverride,
		AwsCredentialsProvider credentialsProvider
	) {
	}
}
