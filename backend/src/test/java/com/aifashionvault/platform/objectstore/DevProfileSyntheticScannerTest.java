package com.aifashionvault.platform.objectstore;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.aifashionvault.platform.environment.VaultEnvironmentConfiguration;
import com.aifashionvault.platform.environment.VaultEnvironmentProperties;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.platform.environment.AppEnv;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F15: the malware scanner must be profile-scoped. Production and staging stay fail-closed
 * UNAVAILABLE; development installs an honest SYNTHETIC scanner that performs no real
 * inspection, so the upload completion path is reachable locally without a vendor.
 */
class DevProfileSyntheticScannerTest {

	private static final UserId USER_ID = new UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
	private static final UUID FIXED_UPLOAD = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UploadId UPLOAD_ID = new UploadId(FIXED_UPLOAD);
	private static final String VERSION = "etag-1";
	private static final String GENERIC_KEY = StorageKeys.stagingKey(USER_ID, UPLOAD_ID, UploadPurpose.GENERIC).value();

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(VaultEnvironmentConfiguration.class, ObjectStoreConfiguration.class);

	@Test
	void developmentProfileInstallsExactlyOneScannerAndItIsHonestlySynthetic() {
		runner.withPropertyValues(development()).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(MalwareScanner.class);

			MalwareScanResult result = context.getBean(MalwareScanner.class).scan(new byte[] { 1 }, "image/jpeg");

			assertThat(result.verdict()).isEqualTo(MalwareVerdict.CLEAN);
			assertThat(result.reason())
				.containsIgnoringCase("synthetic")
				.containsIgnoringCase("development");
		});
	}

	@Test
	void benignJpegReachesCleanThroughTheCompletionPathUnderTheDevelopmentProfile() throws IOException {
		byte[] jpeg = jpeg(20, 20);
		StagingStore store = new StagingStore(jpeg);

		runner.withPropertyValues(development()).run(context -> {
			assertThat(context).hasNotFailed();

			UploadWorkflow workflow = new UploadWorkflow(
				store,
				new ImageInspector(),
				context.getBean(MalwareScanner.class),
				() -> FIXED_UPLOAD);

			VerifiedUpload verified = workflow.complete(USER_ID, UPLOAD_ID);

			assertThat(verified.id()).isEqualTo(UPLOAD_ID);
			assertThat(verified.mediaType()).isEqualTo(ImageMediaType.JPEG);
			assertThat(verified.contentLength()).isEqualTo(jpeg.length);
			assertThat(store.deleted).isEmpty();
		});
	}

	@Test
	void stagingAndProductionKeepTheFailClosedUnavailableScanner() {
		runner.withPropertyValues(staging()).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(MalwareScanner.class);
			assertThat(context.getBean(MalwareScanner.class).scan(new byte[] { 1 }, "image/jpeg").verdict())
				.isEqualTo(MalwareVerdict.UNAVAILABLE);
		});
		runner.withPropertyValues(production()).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(MalwareScanner.class);
			assertThat(context.getBean(MalwareScanner.class).scan(new byte[] { 1 }, "image/jpeg").verdict())
				.isEqualTo(MalwareVerdict.UNAVAILABLE);
		});
	}
	/**
	 * A booting context cannot carry a null or blank env at all: VaultEnvironmentValidator
	 * :17-18 rejects it with "APP_ENV is required" before any bean method runs. So the
	 * fail-open property is asserted against the bean method itself, which is the code that
	 * would regress if someone rewrote the enum-identity comparison as equals() or a name
	 * comparison. A null env must fall through to the fail-closed scanner.
	 */
	@Test
	void nullOrBlankEnvironmentFallsThroughToTheFailClosedScanner() {
		assertThat(scannerFor(null).scan(new byte[] { 1 }, "image/jpeg").verdict())
			.isEqualTo(MalwareVerdict.UNAVAILABLE);

		for (AppEnv env : List.of(AppEnv.staging, AppEnv.production)) {
			assertThat(scannerFor(env).scan(new byte[] { 1 }, "image/jpeg").verdict())
				.isEqualTo(MalwareVerdict.UNAVAILABLE);
		}
		assertThat(scannerFor(AppEnv.development).scan(new byte[] { 1 }, "image/jpeg").verdict())
			.isEqualTo(MalwareVerdict.CLEAN);
	}

	private static MalwareScanner scannerFor(AppEnv env) {
		return new ObjectStoreConfiguration().malwareScanner(new VaultEnvironmentProperties(
			env,
			new VaultEnvironmentProperties.Db("jdbc:postgresql://127.0.0.1:5432/postgres", "postgres", "test"),
			new VaultEnvironmentProperties.Aws("us-east-1", null, null, null, null),
			null,
			null,
			null,
			null,
			null,
			null));
	}

	private static byte[] jpeg(int width, int height) throws IOException {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = image.createGraphics();
		graphics.setColor(Color.RED);
		graphics.fillRect(0, 0, width, height);
		graphics.dispose();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("JPEG");
		ImageWriter writer = writers.next();
		try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
			writer.setOutput(output);
			writer.write(image);
		}
		finally {
			writer.dispose();
		}
		return bytes.toByteArray();
	}

	private static final class StagingStore implements ObjectStore {

		private final Map<String, byte[]> objects = new HashMap<>();
		private final List<String> deleted = new ArrayList<>();

		private StagingStore(byte[] payload) {
			objects.put(GENERIC_KEY, payload);
		}

		@Override
		public UploadCapability presignStagingPut(UserId userId, UploadId uploadId, MediaDeclaration declaration) {
			throw new AssertionError("presignStagingPut must not be called");
		}

		@Override
		public ObjectMetadata head(ObjectRef object) {
			byte[] found = objects.get(object.objectKey());
			if (found == null) {
				throw new ObjectStoreExceptions.StagedObjectMissingException("missing staged object");
			}
			return new ObjectMetadata(found.length, "image/jpeg", VERSION);
		}

		@Override
		public byte[] readBounded(ObjectRef object, String expectedVersion, int maxBytes) {
			byte[] found = objects.get(object.objectKey());
			if (found == null) {
				throw new ObjectStoreExceptions.StagedObjectMissingException("missing staged object");
			}
			return found;
		}

		@Override
		public void delete(ObjectRef object) {
			deleted.add(object.objectKey());
			objects.remove(object.objectKey());
		}

		@Override
		public ClaimResult claim(ClaimCommand command) {
			throw new AssertionError("claim must not be called");
		}

		@Override
		public void purgeUserObjects(UserId userId) {
			throw new AssertionError("purgeUserObjects must not be called");
		}

		@Override
		public void cleanupTemp() {
			throw new AssertionError("cleanupTemp must not be called");
		}

		@Override
		public PresignedAccess presignGet(ObjectRef object, Duration ttl) {
			throw new AssertionError("presignGet must not be called");
		}
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
