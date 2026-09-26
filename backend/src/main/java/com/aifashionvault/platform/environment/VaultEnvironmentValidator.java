package com.aifashionvault.platform.environment;

import java.net.URI;
import java.util.Locale;

import org.springframework.core.env.Environment;

final class VaultEnvironmentValidator {

	private static final String POSTGRES_JDBC = "jdbc:postgresql://";

	private VaultEnvironmentValidator() {
	}

	static void validate(VaultEnvironmentProperties properties, Environment environment) {
		rejectRetired(environment);
		if (properties == null || properties.env() == null) {
			reject("APP_ENV is required");
		}
		AppEnv env = properties.env();
		validateDb(properties.db());
		validateAws(env, properties.aws());
		validateSqs(env, properties.aws() == null ? null : properties.aws().region(), properties.sqs());
		validateS3(properties.s3());
		// The image provider is deliberately NOT required at boot. A blank or absent
		// FASHN_API_KEY means no ProviderRegistry bean exists and the create path refuses
		// admission with GENERATION_PROVIDER_UNAVAILABLE, which is the honest signal.
		// Requiring it here would make the app unbootable instead of honestly unavailable.
		if (properties.firebase() == null || isBlank(properties.firebase().projectId())) {
			reject("FIREBASE_PROJECT_ID is required");
		}
		validateOtel(env, properties.otel());
	}

	private static void rejectRetired(Environment environment) {
		if (active(environment, "REDIS_URL", "redis.url")) {
			reject("REDIS_URL is not an active V1 configuration");
		}
		if (active(environment, "S3_REGION", "s3.region")) {
			reject("S3_REGION is not an active V1 configuration");
		}
	}

	private static void validateDb(VaultEnvironmentProperties.Db db) {
		if (db == null || isBlank(db.url())) {
			reject("DB_URL is required");
		}
		String url = db.url().strip();
		if (!url.startsWith(POSTGRES_JDBC)) {
			reject("DB_URL must be a PostgreSQL JDBC URL");
		}
		if (embedsPassword(url)) {
			reject("DB_URL must not embed a password");
		}
		if (isBlank(db.username())) {
			reject("DB_USERNAME is required");
		}
		if (isBlank(db.password())) {
			reject("DB_PASSWORD is required");
		}
	}

	private static void validateAws(AppEnv env, VaultEnvironmentProperties.Aws aws) {
		if (aws == null || isBlank(aws.region())) {
			reject("AWS_REGION is required");
		}
		boolean development = env == AppEnv.development;
		if (development) {
			if (isBlank(aws.endpointUrlS3())) {
				reject("AWS_ENDPOINT_URL_S3 is required");
			}
			if (isBlank(aws.endpointUrlSqs())) {
				reject("AWS_ENDPOINT_URL_SQS is required");
			}
			return;
		}
		if (!isBlank(aws.endpointUrlS3())) {
			reject("AWS_ENDPOINT_URL_S3 is forbidden");
		}
		if (!isBlank(aws.endpointUrlSqs())) {
			reject("AWS_ENDPOINT_URL_SQS is forbidden");
		}
		if (!isBlank(aws.accessKeyId())) {
			reject("AWS_ACCESS_KEY_ID is forbidden");
		}
		if (!isBlank(aws.secretAccessKey())) {
			reject("AWS_SECRET_ACCESS_KEY is forbidden");
		}
	}

	private static void validateSqs(AppEnv env, String region, VaultEnvironmentProperties.Sqs sqs) {
		if (sqs == null || isBlank(sqs.generationQueueUrl())) {
			reject("SQS_GENERATION_QUEUE_URL is required");
		}
		if (!queueUrlValidFor(env, region, sqs.generationQueueUrl().strip())) {
			reject("SQS_GENERATION_QUEUE_URL is invalid for the environment");
		}
	}

	private static void validateS3(VaultEnvironmentProperties.S3 s3) {
		if (s3 == null) {
			reject("S3_AVATAR_BUCKET is required");
		}
		String avatar = s3.avatarBucket();
		String vault = s3.vaultBucket();
		String look = s3.lookBucket();
		String temp = s3.tempBucket();
		if (isBlank(avatar)) {
			reject("S3_AVATAR_BUCKET is required");
		}
		if (isBlank(vault)) {
			reject("S3_VAULT_BUCKET is required");
		}
		if (isBlank(look)) {
			reject("S3_LOOK_BUCKET is required");
		}
		if (isBlank(temp)) {
			reject("S3_TEMP_BUCKET is required");
		}
		if (avatar.equals(vault) || avatar.equals(look) || avatar.equals(temp)) {
			reject("S3_AVATAR_BUCKET must be distinct");
		}
		if (vault.equals(look) || vault.equals(temp)) {
			reject("S3_VAULT_BUCKET must be distinct");
		}
		if (look.equals(temp)) {
			reject("S3_LOOK_BUCKET must be distinct");
		}
	}

	private static void validateOtel(AppEnv env, VaultEnvironmentProperties.Otel otel) {
		if (otel == null || isBlank(otel.serviceName())) {
			reject("OTEL_SERVICE_NAME is required");
		}
		if (env == AppEnv.development) {
			return;
		}
		if (isBlank(otel.resourceAttributes())) {
			reject("OTEL_RESOURCE_ATTRIBUTES is required");
		}
		if (isBlank(otel.exporterOtlpEndpoint())) {
			reject("OTEL_EXPORTER_OTLP_ENDPOINT is required");
		}
	}

	private static boolean queueUrlValidFor(AppEnv env, String region, String queueUrl) {
		URI uri;
		try {
			uri = URI.create(queueUrl);
		}
		catch (IllegalArgumentException ex) {
			return false;
		}
		if (uri.getScheme() == null || uri.getHost() == null || !hasAccountAndQueue(uri.getPath())) {
			return false;
		}
		String host = uri.getHost().toLowerCase(Locale.ROOT);
		boolean local = "localhost".equals(host) || "127.0.0.1".equals(host) || host.contains("localstack");
		boolean http = "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
		if (!http) {
			return false;
		}
		if (env == AppEnv.development) {
			return local;
		}
		String expectedHost = "sqs." + region.strip().toLowerCase(Locale.ROOT) + ".amazonaws.com";
		return "https".equalsIgnoreCase(uri.getScheme()) && expectedHost.equals(host);
	}

	private static boolean hasAccountAndQueue(String path) {
		if (path == null) {
			return false;
		}
		int segments = 0;
		for (String segment : path.split("/")) {
			if (!segment.isBlank()) {
				segments++;
			}
		}
		return segments >= 2;
	}

	private static boolean embedsPassword(String jdbcUrl) {
		String rest = jdbcUrl.substring(POSTGRES_JDBC.length());
		int slash = rest.indexOf('/');
		String authority = slash >= 0 ? rest.substring(0, slash) : rest;
		int at = authority.lastIndexOf('@');
		if (at >= 0 && authority.substring(0, at).contains(":")) {
			return true;
		}
		int query = jdbcUrl.indexOf('?');
		if (query < 0) {
			return false;
		}
		for (String pair : jdbcUrl.substring(query + 1).split("&")) {
			int eq = pair.indexOf('=');
			String key = eq >= 0 ? pair.substring(0, eq) : pair;
			if (key.equalsIgnoreCase("password")) {
				return true;
			}
		}
		return false;
	}

	private static boolean active(Environment environment, String... keys) {
		if (environment == null) {
			return false;
		}
		for (String key : keys) {
			if (!isBlank(environment.getProperty(key))) {
				return true;
			}
		}
		return false;
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	private static void reject(String message) {
		throw new IllegalStateException(message);
	}
}
