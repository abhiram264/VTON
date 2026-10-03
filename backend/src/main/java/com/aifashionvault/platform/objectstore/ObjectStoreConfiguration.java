package com.aifashionvault.platform.objectstore;

import java.net.URI;
import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.TimeUnit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
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
	static final String SYNTHETIC_DEVELOPMENT_SCANNER_REASON =
		"synthetic development scanner: no inspection performed; test and local use only";

	/**
	 * Development-only, and it inspects nothing. It exists so a developer can drive the
	 * upload completion path locally without selecting a malware-scanning vendor, which this
	 * run deliberately does not do. It is NOT a real engine: every result names itself
	 * synthetic, and it is unreachable in staging and production.
	 */
	private static final MalwareScanner SYNTHETIC_DEVELOPMENT_SCANNER =
		(bytes, contentType) -> new MalwareScanResult(
			MalwareVerdict.CLEAN,
			SYNTHETIC_DEVELOPMENT_SCANNER_REASON);

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
	/**
	 * F15 profile scoping. There is exactly ONE {@link MalwareScanner} bean, so no profile
	 * can produce a NoUniqueBeanDefinitionException - the failure class that reddens 31
	 * backend test classes at the baseline. A second {@code @Profile} bean of this type would
	 * reproduce it, so the switch lives inside this one method and reads the environment the
	 * rest of this file already switches on ({@code AppEnv.development}, the same predicate
	 * {@link #wiring} and {@link #credentialsProvider} use) rather than a Spring profile that
	 * no configuration in this repository ever sets.
	 *
	 * <p>staging and production: no scanning engine is selected, so the scanner fails CLOSED
	 * with a named reason and {@code UploadWorkflow} rejects every upload. This is the
	 * guarantee. It is not a stub to be deleted.
	 *
	 * <p>development: installs an explicitly SYNTHETIC scanner that performs no inspection, so
	 * the local upload path is reachable without selecting a vendor. It is not a real engine
	 * and says so in every result.
	 *
	 * <p>Neither branch wires a provider or a vendor.
	 */
	@Bean
	@ConditionalOnMissingBean(MalwareScanner.class)
	MalwareScanner malwareScanner(VaultEnvironmentProperties properties) {
		if (properties.env() == AppEnv.development) {
			log.warn("malware scan: installing the SYNTHETIC development scanner; it inspects nothing");
			return SYNTHETIC_DEVELOPMENT_SCANNER;
		}
		return failClosedScanner();
	}

	/**
	 * The fail-closed default, callable with no arguments so the guarantee stays directly
	 * assertable. Every non-development profile resolves to exactly this scanner, and so does
	 * the zero-argument call in the fail-closed tests.
	 */
	MalwareScanner malwareScanner() {
		return failClosedScanner();
	}

	private MalwareScanner failClosedScanner() {
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
