package com.aifashionvault.platform.environment;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "vault")
public record VaultEnvironmentProperties(
	AppEnv env,
	Db db,
	Aws aws,
	Sqs sqs,
	S3 s3,
	Fashn fashn,
	Inferhub inferhub,
	Firebase firebase,
	Otel otel
) {

	public record Db(String url, String username, String password) {
		@Override
		public String toString() {
			return "Db[username=" + username + "]";
		}
	}

	public record Aws(
		String region,
		String endpointUrlS3,
		String endpointUrlSqs,
		String accessKeyId,
		String secretAccessKey
	) {
		@Override
		public String toString() {
			return "Aws[region=" + region + "]";
		}
	}

	public record Sqs(String generationQueueUrl) {
	}

	public record S3(String avatarBucket, String vaultBucket, String lookBucket, String tempBucket) {
	}

	public record Fashn(String apiKey) {
		@Override
		public String toString() {
			return "Fashn[]";
		}
	}

	public record Inferhub(String apiKey) {
		@Override
		public String toString() {
			return "Inferhub[]";
		}
	}

	public record Firebase(String projectId) {
	}

	public record Otel(
		String serviceName,
		String resourceAttributes,
		String exporterOtlpEndpoint,
		String exporterOtlpHeaders
	) {
		@Override
		public String toString() {
			return "Otel[serviceName=" + serviceName + "]";
		}
	}

	@Override
	public String toString() {
		return "VaultEnvironmentProperties[env=" + env + "]";
	}
}
