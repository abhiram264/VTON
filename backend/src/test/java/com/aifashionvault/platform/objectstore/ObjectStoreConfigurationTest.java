package com.aifashionvault.platform.objectstore;

import java.lang.reflect.Method;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import com.aifashionvault.platform.environment.AppEnv;
import com.aifashionvault.platform.environment.VaultEnvironmentConfiguration;
import com.aifashionvault.platform.environment.VaultEnvironmentProperties;

import com.aifashionvault.importer.ImportMediaAccess;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ObjectStoreConfigurationTest {

	private static final URI LOCALSTACK_S3 = URI.create("http://127.0.0.1:4566");

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(VaultEnvironmentConfiguration.class, ObjectStoreConfiguration.class);

	@Test
	void configurationUsesProxyBeanMethodsFalseAndEnablesScheduling() {
		Configuration configuration = ObjectStoreConfiguration.class.getAnnotation(Configuration.class);
		assertThat(configuration).isNotNull();
		assertThat(configuration.proxyBeanMethods()).isFalse();
		assertThat(ObjectStoreConfiguration.class.getAnnotation(EnableScheduling.class)).isNotNull();
	}

	@Test
	void defaultMalwareScannerFailsClosed() {
		MalwareScanResult result = new ObjectStoreConfiguration().malwareScanner()
			.scan(new byte[] { 1 }, "image/jpeg");

		assertThat(result.verdict()).isEqualTo(MalwareVerdict.UNAVAILABLE);
	}

	@Nested
	class Development {

		@Test
		void fixtureCreatesObjectStoreWorkflowAndS3Beans() {
			runner.withPropertyValues(development()).run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(ObjectStore.class);
				assertThat(context).hasSingleBean(UploadWorkflow.class);
				assertThat(context).hasSingleBean(S3Client.class);
				assertThat(context).hasSingleBean(S3Presigner.class);
				assertThat(context).hasSingleBean(LoggingTempObjectAgeAlert.class);
				assertThat(context).hasSingleBean(ImportMediaAccess.class);
				assertThat(context.getBean(ObjectStore.class)).isInstanceOf(S3ObjectStore.class);
				assertThat(context.getBeanNamesForType(Clock.class)).isEmpty();
				assertThat(context.getBean(S3Client.class).serviceClientConfiguration().endpointOverride())
					.contains(LOCALSTACK_S3);
			});
		}

		@Test
		void presignerBuiltTheSameWayEmitsLocalStackHost() {
			try (S3Presigner presigner = ObjectStoreConfiguration.createS3Presigner(wiring(developmentProperties()))) {
				URI uri = URI.create(presigner.presignGetObject(request -> request
					.signatureDuration(Duration.ofMinutes(5))
					.getObjectRequest(get -> get
						.bucket("ai-fashion-vault-local-avatar-private")
						.key("users/u/avatars/a.jpg")))
					.url()
					.toString());
				assertThat(uri.toString()).contains("127.0.0.1:4566");
				assertThat(uri.getHost()).isEqualTo("127.0.0.1");
				assertThat(uri.getPort()).isEqualTo(4566);
			}
		}

		@Test
		void wiringAppliesEndpointPathStyleAndStaticKeys() {
			ObjectStoreConfiguration.S3ClientWiring wiring = wiring(developmentProperties());
			assertThat(wiring.region()).isEqualTo(Region.US_EAST_1);
			assertThat(wiring.endpointOverride()).isEqualTo(LOCALSTACK_S3);
			assertThat(wiring.pathStyleAccess()).isTrue();
			assertThat(wiring.credentialsProvider()).isInstanceOf(StaticCredentialsProvider.class);
		}

		@Test
		void missingDevKeysStillOverrideEndpointWithoutStaticCredentials() {
			VaultEnvironmentProperties properties = properties(
				AppEnv.development,
				"us-east-1",
				"http://127.0.0.1:4566",
				null,
				null);
			ObjectStoreConfiguration.S3ClientWiring wiring = wiring(properties);
			assertThat(wiring.endpointOverride()).isEqualTo(LOCALSTACK_S3);
			assertThat(wiring.pathStyleAccess()).isTrue();
			assertThat(wiring.credentialsProvider()).isInstanceOf(DefaultCredentialsProvider.class);
		}
	}

	@Nested
	class StagingAndProduction {

		@Test
		void stagingCreatesContextWithoutEndpointOverride() {
			runner.withPropertyValues(staging()).run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(ObjectStore.class);
				assertThat(context).hasSingleBean(UploadWorkflow.class);
				assertThat(context.getBean(S3Client.class).serviceClientConfiguration().endpointOverride()).isEmpty();
			});
			ObjectStoreConfiguration.S3ClientWiring wiring = wiring(stagingProperties());
			assertThat(wiring.endpointOverride()).isNull();
			assertThat(wiring.pathStyleAccess()).isFalse();
			assertThat(wiring.credentialsProvider()).isInstanceOf(DefaultCredentialsProvider.class);
		}

		@Test
		void productionCreatesContextWithoutEndpointOverride() {
			runner.withPropertyValues(production()).run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(ObjectStore.class);
				assertThat(context).hasSingleBean(S3Presigner.class);
				assertThat(context.getBean(S3Client.class).serviceClientConfiguration().endpointOverride()).isEmpty();
			});
			ObjectStoreConfiguration.S3ClientWiring wiring = wiring(productionProperties());
			assertThat(wiring.endpointOverride()).isNull();
			assertThat(wiring.pathStyleAccess()).isFalse();
			assertThat(wiring.credentialsProvider()).isInstanceOf(DefaultCredentialsProvider.class);
		}

		@Test
		void neverBuildsStaticKeysOrEndpointOverrideEvenIfPropertiesContainThem() {
			for (AppEnv env : List.of(AppEnv.staging, AppEnv.production)) {
				ObjectStoreConfiguration.S3ClientWiring wiring = wiring(properties(
					env,
					"us-east-1",
					"http://127.0.0.1:4566",
					"test",
					"test"));
				assertThat(wiring.endpointOverride()).isNull();
				assertThat(wiring.pathStyleAccess()).isFalse();
				assertThat(wiring.credentialsProvider()).isInstanceOf(DefaultCredentialsProvider.class);
			}
		}
	}

	@Nested
	class Scheduling {

		@Test
		void hourlyCleanupHasAtLeastOneHourInitialAndRepeatDelay() {
			List<Method> scheduled = scheduledMethods(ObjectStoreConfiguration.class);
			assertThat(scheduled).isNotEmpty();
			for (Method method : scheduled) {
				Scheduled annotation = method.getAnnotation(Scheduled.class);
				assertThat(delay(annotation.initialDelay(), annotation.initialDelayString(), annotation.timeUnit()))
					.isGreaterThanOrEqualTo(Duration.ofHours(1));
				Duration repeat = delay(annotation.fixedDelay(), annotation.fixedDelayString(), annotation.timeUnit());
				if (repeat.isZero() || repeat.isNegative()) {
					repeat = delay(annotation.fixedRate(), annotation.fixedRateString(), annotation.timeUnit());
				}
				assertThat(repeat).isGreaterThanOrEqualTo(Duration.ofHours(1));
			}
		}

		@Test
		void cleanupIsolatesStoreExceptions() {
			ObjectStore store = mock(ObjectStore.class);
			doThrow(new RuntimeException("connection refused")).when(store).cleanupTemp();
			assertThatCode(() -> ObjectStoreConfiguration.cleanupTempQuietly(store)).doesNotThrowAnyException();
			verify(store).cleanupTemp();
		}
	}

	private static ObjectStoreConfiguration.S3ClientWiring wiring(VaultEnvironmentProperties properties) {
		return ObjectStoreConfiguration.wiring(properties);
	}

	private static List<Method> scheduledMethods(Class<?> type) {
		List<Method> methods = new ArrayList<>();
		for (Method method : type.getDeclaredMethods()) {
			if (method.isAnnotationPresent(Scheduled.class)) {
				methods.add(method);
			}
		}
		for (Class<?> nested : type.getDeclaredClasses()) {
			methods.addAll(scheduledMethods(nested));
		}
		return methods;
	}

	private static Duration delay(long amount, String amountString, TimeUnit unit) {
		if (amount >= 0) {
			return Duration.of(amount, unit.toChronoUnit());
		}
		if (amountString != null && !amountString.isBlank()) {
			if (amountString.startsWith("P")) {
				return Duration.parse(amountString);
			}
			return Duration.of(Long.parseLong(amountString), unit.toChronoUnit());
		}
		return Duration.ofMillis(-1);
	}

	private static VaultEnvironmentProperties developmentProperties() {
		return properties(AppEnv.development, "us-east-1", "http://127.0.0.1:4566", "test", "test");
	}

	private static VaultEnvironmentProperties stagingProperties() {
		return properties(AppEnv.staging, "us-east-1", null, null, null);
	}

	private static VaultEnvironmentProperties productionProperties() {
		return properties(AppEnv.production, "us-east-1", null, null, null);
	}

	private static VaultEnvironmentProperties properties(
		AppEnv env,
		String region,
		String endpointUrlS3,
		String accessKeyId,
		String secretAccessKey
	) {
		String queue = env == AppEnv.development
			? "http://127.0.0.1:4566/000000000000/ai-fashion-vault-local-generation-work"
			: "https://sqs.us-east-1.amazonaws.com/123456789012/ai-fashion-vault-" + env + "-generation-work";
		String prefix = "ai-fashion-vault-" + env;
		return new VaultEnvironmentProperties(
			env,
			new VaultEnvironmentProperties.Db("jdbc:postgresql://127.0.0.1:5432/postgres", "postgres", "test"),
			new VaultEnvironmentProperties.Aws(region, endpointUrlS3, endpointUrlS3, accessKeyId, secretAccessKey),
			new VaultEnvironmentProperties.Sqs(queue),
			new VaultEnvironmentProperties.S3(prefix + "-avatar", prefix + "-vault", prefix + "-look", prefix + "-temp"),
			new VaultEnvironmentProperties.Fashn("test-fashn-key"),
			new VaultEnvironmentProperties.Firebase("demo-ai-fashion-vault"),
			new VaultEnvironmentProperties.Otel("ai-fashion-vault-api", "deployment.environment.name=" + env, "http://127.0.0.1:4317", null));
	}

	private static String[] development() {
		return new String[] {
			"vault.env=development",
			"vault.db.url=jdbc:postgresql://127.0.0.1:5432/postgres",
			"vault.db.username=postgres",
			"vault.db.password=test",
			"vault.aws.region=us-east-1",
			"vault.aws.endpoint-url-s3=http://127.0.0.1:4566",
			"vault.aws.endpoint-url-sqs=http://127.0.0.1:4566",
			"vault.aws.access-key-id=test",
			"vault.aws.secret-access-key=test",
			"vault.sqs.generation-queue-url=http://127.0.0.1:4566/000000000000/ai-fashion-vault-local-generation-work",
			"vault.s3.avatar-bucket=ai-fashion-vault-local-avatar-private",
			"vault.s3.vault-bucket=ai-fashion-vault-local-vault-assets",
			"vault.s3.look-bucket=ai-fashion-vault-local-look-private",
			"vault.s3.temp-bucket=ai-fashion-vault-local-generation-temp",
			"vault.fashn.api-key=test-fashn-key",
			"vault.firebase.project-id=demo-ai-fashion-vault",
			"vault.otel.service-name=ai-fashion-vault-api"
		};
	}

	private static String[] staging() {
		return new String[] {
			"vault.env=staging",
			"vault.db.url=jdbc:postgresql://127.0.0.1:5432/postgres",
			"vault.db.username=postgres",
			"vault.db.password=test",
			"vault.aws.region=us-east-1",
			"vault.sqs.generation-queue-url=https://sqs.us-east-1.amazonaws.com/123456789012/ai-fashion-vault-staging-generation-work",
			"vault.s3.avatar-bucket=ai-fashion-vault-staging-avatar",
			"vault.s3.vault-bucket=ai-fashion-vault-staging-vault",
			"vault.s3.look-bucket=ai-fashion-vault-staging-look",
			"vault.s3.temp-bucket=ai-fashion-vault-staging-temp",
			"vault.fashn.api-key=test-fashn-key",
			"vault.firebase.project-id=demo-ai-fashion-vault",
			"vault.otel.service-name=ai-fashion-vault-api",
			"vault.otel.resource-attributes=deployment.environment.name=staging",
			"vault.otel.exporter-otlp-endpoint=http://127.0.0.1:4317"
		};
	}

	private static String[] production() {
		return new String[] {
			"vault.env=production",
			"vault.db.url=jdbc:postgresql://127.0.0.1:5432/postgres",
			"vault.db.username=postgres",
			"vault.db.password=test",
			"vault.aws.region=us-east-1",
			"vault.sqs.generation-queue-url=https://sqs.us-east-1.amazonaws.com/123456789012/ai-fashion-vault-production-generation-work",
			"vault.s3.avatar-bucket=ai-fashion-vault-production-avatar",
			"vault.s3.vault-bucket=ai-fashion-vault-production-vault",
			"vault.s3.look-bucket=ai-fashion-vault-production-look",
			"vault.s3.temp-bucket=ai-fashion-vault-production-temp",
			"vault.fashn.api-key=test-fashn-key",
			"vault.firebase.project-id=demo-ai-fashion-vault",
			"vault.otel.service-name=ai-fashion-vault-api",
			"vault.otel.resource-attributes=deployment.environment.name=production",
			"vault.otel.exporter-otlp-endpoint=http://127.0.0.1:4317"
		};
	}
}
