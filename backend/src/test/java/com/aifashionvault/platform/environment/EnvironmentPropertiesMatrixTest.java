package com.aifashionvault.platform.environment;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EnvironmentPropertiesMatrixTest {

	private static final String CANARY_DB_PASSWORD = "canary-db-password-value";
	private static final String CANARY_FASHN_API_KEY = "canary-fashn-api-key-value";
	private static final String CANARY_AWS_SECRET = "canary-aws-secret-access-key-value";
	private static final String CANARY_OTEL_HEADERS = "canary-otel-headers-value";

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(VaultEnvironmentConfiguration.class);

	@Nested
	@SpringBootTest
	class DevelopmentFixtureBinding {

		@Autowired
		private VaultEnvironmentProperties properties;

		@Autowired
		private Environment environment;

		@Test
		void bindsFrozenDevelopmentFixture() {
			assertThat(properties.env()).isEqualTo(AppEnv.development);
			assertThat(properties.db().url()).isEqualTo("jdbc:postgresql://127.0.0.1:5432/postgres");
			assertThat(properties.db().password()).isEqualTo("test");
			assertThat(properties.fashn().apiKey()).isEqualTo("test-fashn-key");
			assertThat(properties.firebase().projectId()).isEqualTo("demo-ai-fashion-vault");
			assertThat(properties.otel().serviceName()).isEqualTo("ai-fashion-vault-api");
			assertThat(properties.s3().avatarBucket()).isEqualTo("ai-fashion-vault-local-avatar-private");
			assertThat(properties.s3().vaultBucket()).isEqualTo("ai-fashion-vault-local-vault-assets");
			assertThat(properties.s3().lookBucket()).isEqualTo("ai-fashion-vault-local-look-private");
			assertThat(properties.s3().tempBucket()).isEqualTo("ai-fashion-vault-local-generation-temp");
			assertThat(properties.sqs().generationQueueUrl())
				.isEqualTo("http://127.0.0.1:4566/000000000000/ai-fashion-vault-local-generation-work");
		}

		@Test
		void actuatorHealthDetailsStayHidden() {
			assertThat(environment.getProperty("management.endpoint.health.show-details")).isEqualTo("never");
		}
	}

	@Test
	void bindsAllThreeCanonicalAppEnvs() {
		runner.withPropertyValues(development()).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(VaultEnvironmentProperties.class).env()).isEqualTo(AppEnv.development);
		});
		runner.withPropertyValues(staging()).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(VaultEnvironmentProperties.class).env()).isEqualTo(AppEnv.staging);
		});
		runner.withPropertyValues(production()).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(VaultEnvironmentProperties.class).env()).isEqualTo(AppEnv.production);
		});
	}

	@Test
	void rejectsInvalidAppEnv() {
		assertFailureIdentifies(overlay(development(), "vault.env=prod"), "APP_ENV");
		assertFailureIdentifies(overlay(development(), "vault.env=dev"), "APP_ENV");
		assertFailureIdentifies(overlay(development(), "vault.env=stage"), "APP_ENV");
	}

	@Test
	void rejectsMissingRequiredVariables() {
		assertFailureIdentifies(without(development(), "vault.env"), "APP_ENV");
		assertFailureIdentifies(without(development(), "vault.db.url"), "DB_URL");
		assertFailureIdentifies(without(development(), "vault.db.username"), "DB_USERNAME");
		assertFailureIdentifies(without(development(), "vault.db.password"), "DB_PASSWORD");
		assertFailureIdentifies(without(development(), "vault.aws.region"), "AWS_REGION");
		assertFailureIdentifies(without(development(), "vault.sqs.generation-queue-url"), "SQS_GENERATION_QUEUE_URL");
		assertFailureIdentifies(without(development(), "vault.s3.avatar-bucket"), "S3_AVATAR_BUCKET");
		assertFailureIdentifies(without(development(), "vault.s3.vault-bucket"), "S3_VAULT_BUCKET");
		assertFailureIdentifies(without(development(), "vault.s3.look-bucket"), "S3_LOOK_BUCKET");
		assertFailureIdentifies(without(development(), "vault.s3.temp-bucket"), "S3_TEMP_BUCKET");
		assertFailureIdentifies(without(development(), "vault.otel.service-name"), "OTEL_SERVICE_NAME");
		assertFailureIdentifies(without(staging(), "vault.firebase.project-id"), "FIREBASE_PROJECT_ID");
		assertFailureIdentifies(without(production(), "vault.fashn.api-key"), "FASHN_API_KEY");
	}

	@Test
	void rejectsNonPostgreSqlDbUrl() {
		assertFailureIdentifies(
			overlay(development(), "vault.db.url=jdbc:mysql://127.0.0.1:3306/postgres"),
			"DB_URL");
	}

	@Test
	void rejectsEmbeddedDbPassword() {
		assertFailureIdentifies(
			overlay(development(), "vault.db.url=jdbc:postgresql://vault:" + CANARY_DB_PASSWORD + "@127.0.0.1:5432/postgres"),
			"DB_URL");
		assertFailureIdentifies(
			overlay(development(), "vault.db.url=jdbc:postgresql://127.0.0.1:5432/postgres?password=" + CANARY_DB_PASSWORD),
			"DB_URL");
	}

	@Test
	void rejectsBlankAwsRegion() {
		assertFailureIdentifies(overlay(development(), "vault.aws.region="), "AWS_REGION");
		assertFailureIdentifies(overlay(development(), "vault.aws.region=   "), "AWS_REGION");
	}

	@Test
	void rejectsDevelopmentEndpointOmission() {
		assertFailureIdentifies(without(development(), "vault.aws.endpoint-url-s3"), "AWS_ENDPOINT_URL_S3");
		assertFailureIdentifies(without(development(), "vault.aws.endpoint-url-sqs"), "AWS_ENDPOINT_URL_SQS");
		assertFailureIdentifies(overlay(development(), "vault.aws.endpoint-url-s3="), "AWS_ENDPOINT_URL_S3");
		assertFailureIdentifies(overlay(development(), "vault.aws.endpoint-url-sqs="), "AWS_ENDPOINT_URL_SQS");
	}

	@Test
	void rejectsStagingAndProductionCustomEndpoints() {
		assertFailureIdentifies(
			overlay(staging(), "vault.aws.endpoint-url-s3=http://127.0.0.1:4566"),
			"AWS_ENDPOINT_URL_S3");
		assertFailureIdentifies(
			overlay(staging(), "vault.aws.endpoint-url-sqs=http://127.0.0.1:4566"),
			"AWS_ENDPOINT_URL_SQS");
		assertFailureIdentifies(
			overlay(production(), "vault.aws.endpoint-url-s3=http://127.0.0.1:4566"),
			"AWS_ENDPOINT_URL_S3");
		assertFailureIdentifies(
			overlay(production(), "vault.aws.endpoint-url-sqs=http://127.0.0.1:4566"),
			"AWS_ENDPOINT_URL_SQS");
	}

	@Test
	void rejectsMissingQueueUrl() {
		assertFailureIdentifies(without(development(), "vault.sqs.generation-queue-url"), "SQS_GENERATION_QUEUE_URL");
		assertFailureIdentifies(overlay(staging(), "vault.sqs.generation-queue-url="), "SQS_GENERATION_QUEUE_URL");
	}

	@Test
	void rejectsQueueUrlInvalidForEnvironment() {
		assertFailureIdentifies(
			overlay(development(), "vault.sqs.generation-queue-url=https://sqs.us-east-1.amazonaws.com/123456789012/ai-fashion-vault-local-generation-work"),
			"SQS_GENERATION_QUEUE_URL");
		assertFailureIdentifies(
			overlay(staging(), "vault.sqs.generation-queue-url=http://127.0.0.1:4566/000000000000/ai-fashion-vault-local-generation-work"),
			"SQS_GENERATION_QUEUE_URL");
		assertFailureIdentifies(
			overlay(production(), "vault.sqs.generation-queue-url=http://localhost:4566/000000000000/ai-fashion-vault-local-generation-work"),
			"SQS_GENERATION_QUEUE_URL");
		assertFailureIdentifies(
			overlay(production(), "vault.sqs.generation-queue-url=not-a-queue-url"),
			"SQS_GENERATION_QUEUE_URL");
	}

	@Test
	void rejectsDuplicateBucketNames() {
		assertFailureIdentifies(
			overlay(development(), "vault.s3.vault-bucket=ai-fashion-vault-local-avatar-private"),
			"S3_AVATAR_BUCKET");
		assertFailureIdentifies(
			overlay(staging(), "vault.s3.look-bucket=ai-fashion-vault-staging-temp"),
			"S3_LOOK_BUCKET");
	}

	@Test
	void rejectsMissingFirebaseProjectId() {
		assertFailureIdentifies(without(development(), "vault.firebase.project-id"), "FIREBASE_PROJECT_ID");
		assertFailureIdentifies(overlay(production(), "vault.firebase.project-id="), "FIREBASE_PROJECT_ID");
	}

	@Test
	void rejectsMissingFashnApiKey() {
		assertFailureIdentifies(without(development(), "vault.fashn.api-key"), "FASHN_API_KEY");
		assertFailureIdentifies(without(staging(), "vault.fashn.api-key"), "FASHN_API_KEY");
		assertFailureIdentifies(without(production(), "vault.fashn.api-key"), "FASHN_API_KEY");
	}

	@Test
	void rejectsFakeAwsCredentialsOutsideDevelopment() {
		assertFailureIdentifies(
			overlay(staging(), "vault.aws.access-key-id=test", "vault.aws.secret-access-key=test"),
			"AWS_ACCESS_KEY_ID");
		assertFailureIdentifies(
			overlay(production(), "vault.aws.access-key-id=test", "vault.aws.secret-access-key=test"),
			"AWS_ACCESS_KEY_ID");
		assertFailureIdentifies(
			overlay(production(), "vault.aws.secret-access-key=test"),
			"AWS_SECRET_ACCESS_KEY");
	}

	@Test
	void validationFailuresDoNotIncludeSecretValues() {
		String[] secretDevelopment = overlay(
			development(),
			"vault.db.password=" + CANARY_DB_PASSWORD,
			"vault.fashn.api-key=" + CANARY_FASHN_API_KEY,
			"vault.aws.secret-access-key=" + CANARY_AWS_SECRET,
			"vault.otel.exporter-otlp-headers=" + CANARY_OTEL_HEADERS,
			"vault.s3.temp-bucket=ai-fashion-vault-local-avatar-private");
		assertNoSecrets(secretDevelopment);

		String[] embeddedPassword = overlay(
			development(),
			"vault.db.password=" + CANARY_DB_PASSWORD,
			"vault.fashn.api-key=" + CANARY_FASHN_API_KEY,
			"vault.aws.secret-access-key=" + CANARY_AWS_SECRET,
			"vault.otel.exporter-otlp-headers=" + CANARY_OTEL_HEADERS,
			"vault.db.url=jdbc:postgresql://vault:" + CANARY_DB_PASSWORD + "@127.0.0.1:5432/postgres");
		assertNoSecrets(embeddedPassword);

		String[] secretStaging = overlay(
			staging(),
			"vault.db.password=" + CANARY_DB_PASSWORD,
			"vault.fashn.api-key=" + CANARY_FASHN_API_KEY,
			"vault.aws.secret-access-key=" + CANARY_AWS_SECRET,
			"vault.otel.exporter-otlp-headers=" + CANARY_OTEL_HEADERS);
		assertNoSecrets(secretStaging);
	}

	private void assertFailureIdentifies(String[] properties, String variable) {
		runner.withPropertyValues(properties).run(context -> {
			assertThat(context).hasFailed();
			assertThat(flatten(context.getStartupFailure())).contains(variable);
			assertNoSecretCanaries(flatten(context.getStartupFailure()));
		});
	}

	private void assertNoSecrets(String[] properties) {
		runner.withPropertyValues(properties).run(context -> {
			assertThat(context).hasFailed();
			assertNoSecretCanaries(flatten(context.getStartupFailure()));
		});
	}

	private static void assertNoSecretCanaries(String text) {
		assertThat(text)
			.doesNotContain(CANARY_DB_PASSWORD)
			.doesNotContain(CANARY_FASHN_API_KEY)
			.doesNotContain(CANARY_AWS_SECRET)
			.doesNotContain(CANARY_OTEL_HEADERS);
	}

	private static String flatten(Throwable failure) {
		StringBuilder text = new StringBuilder();
		for (Throwable current = failure; current != null; current = current.getCause()) {
			text.append(current).append('\n');
			if (current.getMessage() != null) {
				text.append(current.getMessage()).append('\n');
			}
		}
		return text.toString();
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

	private static String[] without(String[] base, String key) {
		Map<String, String> values = entries(base);
		values.remove(key);
		return toProperties(values);
	}

	private static String[] overlay(String[] base, String... replacements) {
		Map<String, String> values = entries(base);
		for (String replacement : replacements) {
			int eq = replacement.indexOf('=');
			values.put(replacement.substring(0, eq), replacement.substring(eq + 1));
		}
		return toProperties(values);
	}

	private static Map<String, String> entries(String[] properties) {
		Map<String, String> values = new LinkedHashMap<>();
		for (String property : properties) {
			int eq = property.indexOf('=');
			values.put(property.substring(0, eq), property.substring(eq + 1));
		}
		return values;
	}

	private static String[] toProperties(Map<String, String> values) {
		return values.entrySet().stream()
			.map(entry -> entry.getKey() + "=" + entry.getValue())
			.toArray(String[]::new);
	}
}
