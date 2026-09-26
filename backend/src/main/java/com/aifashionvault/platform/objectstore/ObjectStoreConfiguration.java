package com.aifashionvault.platform.objectstore;

import java.net.URI;
import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.TimeUnit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import com.aifashionvault.avatar.AvatarBytesInspector;
import com.aifashionvault.avatar.AvatarMediaAccess;
import com.aifashionvault.importer.ImportMediaAccess;
import com.aifashionvault.platform.environment.AppEnv;
import com.aifashionvault.platform.environment.VaultEnvironmentProperties;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class ObjectStoreConfiguration {

	private static final Logger log = LoggerFactory.getLogger(ObjectStoreConfiguration.class);

	static final String NO_SCANNING_ENGINE_REASON = "no malware scanning engine configured; upload cannot be inspected";

	@Bean(destroyMethod = "close")
	S3Client s3Client(VaultEnvironmentProperties properties) {
		return createS3Client(wiring(properties));
	}

	@Bean(destroyMethod = "close")
	S3Presigner s3Presigner(VaultEnvironmentProperties properties) {
		return createS3Presigner(wiring(properties));
	}

	@Bean
	LoggingTempObjectAgeAlert loggingTempObjectAgeAlert() {
		return new LoggingTempObjectAgeAlert();
	}

	@Bean
	ObjectStore objectStore(
		S3Client s3Client,
		S3Presigner s3Presigner,
		VaultEnvironmentProperties properties,
		LoggingTempObjectAgeAlert loggingAlert
	) {
		return new S3ObjectStore(s3Client, s3Presigner, properties.s3(), Clock.systemUTC(), loggingAlert);
	}

	@Bean
	ImageInspector imageInspector() {
		return new ImageInspector();
	}
	@Bean
	MalwareScanner malwareScanner() {
		return (bytes, contentType) -> {
			log.warn("malware scan unavailable: {}", NO_SCANNING_ENGINE_REASON);
			return new MalwareScanResult(MalwareVerdict.UNAVAILABLE, NO_SCANNING_ENGINE_REASON);
		};
	}


	@Bean
	UploadWorkflow uploadWorkflow(
		ObjectStore objectStore,
		ImageInspector imageInspector,
		MalwareScanner malwareScanner
	) {
		return new UploadWorkflow(objectStore, imageInspector, malwareScanner);
	}

	@Bean
	AvatarMediaAccess avatarMediaAccess(ObjectStore objectStore) {
		return new ObjectStoreAvatarMediaAccess(objectStore);
	}

	@Bean
	ImportMediaAccess importMediaAccess(ObjectStore objectStore, ImageInspector imageInspector) {
		return new ObjectStoreImportMediaAccess(objectStore, imageInspector);
	}

	@Bean
	AvatarBytesInspector avatarBytesInspector(ImageInspector imageInspector) {
		return new ImageInspectorAvatarBytesInspector(imageInspector);
	}

	@Bean
	TempCleanup tempCleanup(ObjectStore objectStore) {
		return new TempCleanup(objectStore);
	}

	static S3ClientWiring wiring(VaultEnvironmentProperties properties) {
		VaultEnvironmentProperties.Aws aws = properties.aws();
		AppEnv env = properties.env();
		URI endpointOverride = null;
		boolean pathStyleAccess = false;
		if (env == AppEnv.development && !isBlank(aws.endpointUrlS3())) {
			endpointOverride = URI.create(aws.endpointUrlS3());
			pathStyleAccess = true;
		}
		return new S3ClientWiring(
			Region.of(aws.region()),
			endpointOverride,
			pathStyleAccess,
			credentialsProvider(env, aws));
	}

	static S3Client createS3Client(S3ClientWiring wiring) {
		S3ClientBuilder builder = S3Client.builder()
			.region(wiring.region())
			.credentialsProvider(wiring.credentialsProvider());
		if (wiring.endpointOverride() != null) {
			builder.endpointOverride(wiring.endpointOverride());
		}
		if (wiring.pathStyleAccess()) {
			builder.forcePathStyle(true);
		}
		return builder.build();
	}

	static S3Presigner createS3Presigner(S3ClientWiring wiring) {
		S3Presigner.Builder builder = S3Presigner.builder()
			.region(wiring.region())
			.credentialsProvider(wiring.credentialsProvider());
		if (wiring.endpointOverride() != null) {
			builder.endpointOverride(wiring.endpointOverride());
		}
		if (wiring.pathStyleAccess()) {
			builder.serviceConfiguration(S3Configuration.builder()
				.pathStyleAccessEnabled(true)
				.build());
		}
		return builder.build();
	}

	static void cleanupTempQuietly(ObjectStore store) {
		if (store == null) {
			return;
		}
		try {
			store.cleanupTemp();
		}
		catch (RuntimeException ex) {
			log.warn("temp object cleanup failed");
		}
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

	record S3ClientWiring(
		Region region,
		URI endpointOverride,
		boolean pathStyleAccess,
		AwsCredentialsProvider credentialsProvider
	) {
	}

	static final class TempCleanup {

		private final ObjectStore store;

		TempCleanup(ObjectStore store) {
			this.store = store;
		}

		@Scheduled(initialDelay = 1, fixedDelay = 1, timeUnit = TimeUnit.HOURS)
		void cleanupTemp() {
			cleanupTempQuietly(store);
		}
	}
}
