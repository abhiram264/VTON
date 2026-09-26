package com.aifashionvault.platform.objectstore;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.aifashionvault.shared.StorageDomain;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadWorkflowTest {

	private static final UserId USER_ID = new UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
	private static final UUID FIXED_UPLOAD = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UploadId UPLOAD_ID = new UploadId(FIXED_UPLOAD);
	private static final String VERSION = "etag-1";

	@Nested
	class Create {

		@Test
		void mintsUploadIdAndCallsPresignStagingPutOnly() {
			FakeObjectStore store = new FakeObjectStore();
			UploadWorkflow workflow = UploadWorkflow.forTesting(store, new ImageInspector(), () -> FIXED_UPLOAD);
			MediaDeclaration declaration = new MediaDeclaration("image/jpeg", 2048L);

			UploadCapability capability = workflow.create(USER_ID, declaration);

			assertThat(capability.id()).isEqualTo(UPLOAD_ID);
			assertThat(store.presignCount).isEqualTo(1);
			assertThat(store.presignUser).isEqualTo(USER_ID);
			assertThat(store.presignUpload).isEqualTo(UPLOAD_ID);
			assertThat(store.presignDeclaration).isEqualTo(declaration);
			assertThat(store.presignPurpose).isEqualTo(UploadPurpose.GENERIC);
			assertThat(store.headCount).isZero();
			assertThat(store.readCount).isZero();
			assertThat(store.deleteCount).isZero();
			assertThat(store.claimCount).isZero();
		}

		@Test
		void mapsUnsupportedTypeAndInvalidLength() {
			FakeObjectStore store = new FakeObjectStore();
			UploadWorkflow workflow = UploadWorkflow.forTesting(store, new ImageInspector(), () -> FIXED_UPLOAD);

			assertThatThrownBy(() -> workflow.create(USER_ID, new MediaDeclaration("image/webp", 2048L)))
				.isInstanceOf(ObjectStoreExceptions.UnsupportedMediaTypeException.class);
			assertThatThrownBy(() -> workflow.create(USER_ID, new MediaDeclaration("image/jpeg", 0L)))
				.isInstanceOf(ObjectStoreExceptions.InvalidUploadDeclarationException.class);
			assertThatThrownBy(() -> workflow.create(USER_ID, new MediaDeclaration("image/png", ImageMediaPolicy.MAX_BYTES + 1L)))
				.isInstanceOf(ObjectStoreExceptions.InvalidUploadDeclarationException.class);
			assertThat(store.presignCount).isZero();
		}

		@Test
		void createWithAvatarPurposePresignsAvatarStaging() {
			FakeObjectStore store = new FakeObjectStore();
			UploadWorkflow workflow = UploadWorkflow.forTesting(store, new ImageInspector(), () -> FIXED_UPLOAD);
			MediaDeclaration declaration = new MediaDeclaration("image/jpeg", 2048L);

			UploadCapability capability = workflow.create(USER_ID, declaration, UploadPurpose.AVATAR);

			assertThat(capability.id()).isEqualTo(UPLOAD_ID);
			assertThat(store.presignCount).isEqualTo(1);
			assertThat(store.presignPurpose).isEqualTo(UploadPurpose.AVATAR);
			assertThat(store.presignDeclaration).isEqualTo(declaration);
			assertThat(store.headCount).isZero();
			assertThat(store.claimCount).isZero();
		}
	}

	@Nested
	class Complete {

		@Test
		void happyPathReturnsVerifiedUploadWithoutClaimOrDelete() throws Exception {
			byte[] jpeg = raster("JPEG", 15, 15);
			FakeObjectStore store = staged(jpeg, "image/jpeg");
			UploadWorkflow workflow = workflow(store);

			VerifiedUpload verified = workflow.complete(USER_ID, UPLOAD_ID);

			assertThat(verified).isEqualTo(new VerifiedUpload(
				UPLOAD_ID,
				ImageMediaType.JPEG,
				jpeg.length,
				15,
				15,
				VERSION));
			assertThat(store.lastHead.boundary()).isEqualTo(StorageDomain.TEMP);
			assertThat(store.lastHead.objectKey()).isEqualTo(StorageKeys.stagingKey(USER_ID, UPLOAD_ID).value());
			assertThat(store.lastHead.objectKey()).doesNotContain("/avatars/");
			assertThat(store.lastRead.boundary()).isEqualTo(StorageDomain.TEMP);
			assertThat(store.lastRead.objectKey()).isEqualTo(StorageKeys.stagingKey(USER_ID, UPLOAD_ID).value());
			assertThat(store.lastReadVersion).isEqualTo(VERSION);
			assertThat(store.lastReadMaxBytes).isEqualTo(jpeg.length);
			assertThat(store.headCount).isEqualTo(2);
			assertThat(store.deleteCount).isZero();
			assertThat(store.claimCount).isZero();
		}

		@Test
		void avatarStagingIsPreferredOverGeneric() throws Exception {
			byte[] jpeg = raster("JPEG", 15, 15);
			FakeObjectStore store = new FakeObjectStore();
			store.stage(UploadPurpose.AVATAR, new ObjectMetadata(jpeg.length, "image/jpeg", VERSION), jpeg);
			UploadWorkflow workflow = workflow(store);

			VerifiedUpload verified = workflow.complete(USER_ID, UPLOAD_ID);

			assertThat(verified).isEqualTo(new VerifiedUpload(
				UPLOAD_ID,
				ImageMediaType.JPEG,
				jpeg.length,
				15,
				15,
				VERSION));
			assertThat(store.lastHead.objectKey())
				.isEqualTo(StorageKeys.stagingKey(USER_ID, UPLOAD_ID, UploadPurpose.AVATAR).value());
			assertThat(store.lastRead.objectKey())
				.isEqualTo(StorageKeys.stagingKey(USER_ID, UPLOAD_ID, UploadPurpose.AVATAR).value());
			assertThat(store.lastHead.objectKey()).contains("/avatars/");
			assertThat(store.lastHead.objectKey()).doesNotContain("/uploads/");
			assertThat(store.headCount).isEqualTo(1);
			assertThat(store.deleteCount).isZero();
		}

		@Test
		void missingStagedObjectPropagates() {
			FakeObjectStore store = new FakeObjectStore();
			store.headFailure = new ObjectStoreExceptions.StagedObjectMissingException("missing staged object");
			UploadWorkflow workflow = workflow(store);

			assertThatThrownBy(() -> workflow.complete(USER_ID, UPLOAD_ID))
				.isInstanceOf(ObjectStoreExceptions.StagedObjectMissingException.class)
				.hasMessage("missing staged object");
			assertThat(store.deleteCount).isZero();
		}

		@Test
		void unavailableIsNotTranslatedToMissing() {
			FakeObjectStore store = new FakeObjectStore();
			store.headFailure = new ObjectStoreExceptions.ObjectStoreUnavailableException("store down");
			UploadWorkflow workflow = workflow(store);

			assertThatThrownBy(() -> workflow.complete(USER_ID, UPLOAD_ID))
				.isInstanceOf(ObjectStoreExceptions.ObjectStoreUnavailableException.class)
				.hasMessage("store down")
				.isNotInstanceOf(ObjectStoreExceptions.StagedObjectMissingException.class);
			assertThat(store.deleteCount).isZero();
		}
		@Test
		void readStoreFailureRemainsInfrastructureFailureWithoutDeletion() {
			FakeObjectStore store = staged(new byte[] {1, 2, 3}, "image/jpeg");
			store.readFailure = new ObjectStoreExceptions.ObjectStoreUnavailableException("store read failed");

			assertThatThrownBy(() -> workflow(store).complete(USER_ID, UPLOAD_ID))
				.isInstanceOf(ObjectStoreExceptions.ObjectStoreUnavailableException.class)
				.hasMessage("store read failed");
			assertThat(store.deleteCount).isZero();
		}

		@Test
		void unsafeScannerVerdictsDeleteTempAndDoNotExposeReason() throws Exception {
			byte[] jpeg = raster("JPEG", 15, 15);
			for (MalwareVerdict verdict : new MalwareVerdict[] {MalwareVerdict.DETECTED, MalwareVerdict.UNAVAILABLE}) {
				FakeObjectStore store = staged(jpeg, "image/jpeg");
				String reason = "scanner internal reason";
				UploadWorkflow unsafe = new UploadWorkflow(
					store,
					new ImageInspector(),
					(bytes, type) -> new MalwareScanResult(verdict, reason),
					() -> FIXED_UPLOAD);

				assertThatThrownBy(() -> unsafe.complete(USER_ID, UPLOAD_ID))
					.isInstanceOf(ObjectStoreExceptions.ImageValidationException.class)
					.hasMessage("image failed malware inspection");
				assertDeletedTemp(store);
			}
		}

		@Test
		void lengthTypeAndMagicFailuresDeleteTemp() throws Exception {
			byte[] png = raster("PNG", 15, 15);

			FakeObjectStore oversize = new FakeObjectStore();
			oversize.metadata = new ObjectMetadata(ImageMediaPolicy.MAX_BYTES + 1L, "image/jpeg", VERSION);
			oversize.payload = png;
			assertThatThrownBy(() -> workflow(oversize).complete(USER_ID, UPLOAD_ID))
				.isInstanceOf(ObjectStoreExceptions.ImageValidationException.class);
			assertDeletedTemp(oversize);

			FakeObjectStore badType = new FakeObjectStore();
			badType.metadata = new ObjectMetadata(png.length, "image/webp", VERSION);
			badType.payload = png;
			assertThatThrownBy(() -> workflow(badType).complete(USER_ID, UPLOAD_ID))
				.isInstanceOf(ObjectStoreExceptions.ImageValidationException.class);
			assertDeletedTemp(badType);

			FakeObjectStore magicMismatch = staged(png, "image/jpeg");
			assertThatThrownBy(() -> workflow(magicMismatch).complete(USER_ID, UPLOAD_ID))
				.isInstanceOf(ObjectStoreExceptions.ImageValidationException.class);
			assertDeletedTemp(magicMismatch);
		}

		@Test
		void deleteFailureStillSurfacesValidation() {
			FakeObjectStore store = new FakeObjectStore();
			store.metadata = new ObjectMetadata(12L, "image/jpeg", VERSION);
			store.payload = new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
			store.deleteFailure = new ObjectStoreExceptions.ObjectStoreUnavailableException("delete failed");
			UploadWorkflow workflow = workflow(store);

			assertThatThrownBy(() -> workflow.complete(USER_ID, UPLOAD_ID))
				.isInstanceOf(ObjectStoreExceptions.ImageValidationException.class)
				.isNotInstanceOf(ObjectStoreExceptions.ObjectStoreUnavailableException.class);
			assertThat(store.deleteCount).isEqualTo(1);
		}

		@Test
		void replayReturnsTheSameVerifiedUpload() throws Exception {
			byte[] png = raster("PNG", 18, 15);
			FakeObjectStore store = staged(png, "image/png");
			UploadWorkflow workflow = workflow(store);

			VerifiedUpload first = workflow.complete(USER_ID, UPLOAD_ID);
			VerifiedUpload second = workflow.complete(USER_ID, UPLOAD_ID);

			assertThat(second).isEqualTo(first);
			assertThat(first.mediaType()).isEqualTo(ImageMediaType.PNG);
			assertThat(first.width()).isEqualTo(18);
			assertThat(first.height()).isEqualTo(15);
			assertThat(store.deleteCount).isZero();
			assertThat(store.claimCount).isZero();
			assertThat(store.headCount).isEqualTo(4);
			assertThat(store.readCount).isEqualTo(2);
		}
	}

	@Nested
	class Abort {

		@Test
		void deletesPresentTempStagingObject() {
			FakeObjectStore store = new FakeObjectStore();
			store.metadata = new ObjectMetadata(8L, "image/jpeg", VERSION);
			store.payload = new byte[] {1, 2, 3, 4, 5, 6, 7, 8};

			workflow(store).abort(USER_ID, UPLOAD_ID);

			assertThat(store.deleteCount).isEqualTo(2);
			assertThat(store.deleted.get(0).objectKey())
				.isEqualTo(StorageKeys.stagingKey(USER_ID, UPLOAD_ID, UploadPurpose.AVATAR).value());
			assertThat(store.lastDelete.boundary()).isEqualTo(StorageDomain.TEMP);
			assertThat(store.lastDelete.objectKey()).isEqualTo(StorageKeys.stagingKey(USER_ID, UPLOAD_ID).value());
			assertThat(store.headCount).isZero();
			assertThat(store.readCount).isZero();
			assertThat(store.claimCount).isZero();
		}

		@Test
		void abortDeletesAvatarAndGenericStaging() {
			FakeObjectStore store = new FakeObjectStore();
			store.stage(UploadPurpose.AVATAR, new ObjectMetadata(8L, "image/jpeg", VERSION), new byte[] {1, 2, 3, 4, 5, 6, 7, 8});

			workflow(store).abort(USER_ID, UPLOAD_ID);

			assertThat(store.deleteCount).isEqualTo(2);
			assertThat(store.deleted).extracting(ObjectRef::objectKey).containsExactly(
				StorageKeys.stagingKey(USER_ID, UPLOAD_ID, UploadPurpose.AVATAR).value(),
				StorageKeys.stagingKey(USER_ID, UPLOAD_ID).value());
			assertThat(store.deleted.get(0).objectKey()).contains("/avatars/");
			assertThat(store.deleted.get(1).objectKey()).doesNotContain("/avatars/");
		}

		@Test
		void missingStagedObjectIsSuccess() {
			FakeObjectStore store = new FakeObjectStore();
			store.deleteFailure = new ObjectStoreExceptions.StagedObjectMissingException("already gone");

			workflow(store).abort(USER_ID, UPLOAD_ID);

			assertThat(store.deleteCount).isEqualTo(2);
			assertThat(store.deleted.get(0).objectKey())
				.isEqualTo(StorageKeys.stagingKey(USER_ID, UPLOAD_ID, UploadPurpose.AVATAR).value());
			assertThat(store.lastDelete.boundary()).isEqualTo(StorageDomain.TEMP);
			assertThat(store.lastDelete.objectKey()).isEqualTo(StorageKeys.stagingKey(USER_ID, UPLOAD_ID).value());
		}

		@Test
		void abortObjectRefBoundaryIsTemp() {
			FakeObjectStore store = new FakeObjectStore();

			workflow(store).abort(USER_ID, UPLOAD_ID);

			assertThat(store.lastDelete.boundary()).isEqualTo(StorageDomain.TEMP);
			assertThat(store.lastDelete.objectKey()).isEqualTo(StorageKeys.stagingKey(USER_ID, UPLOAD_ID).value());
			assertThat(store.lastDelete.objectKey()).startsWith("temp/");
			assertThat(store.lastDelete.objectKey()).doesNotContain("avatars");
			assertThat(store.lastDelete.objectKey()).doesNotContain("vault");
			assertThat(store.lastDelete.objectKey()).doesNotContain("looks");
		}
	}

	@Nested
	class ProductionApi {

		@Test
		void noPublicConstructorOrFactoryHandsOutAnImplicitlyCleanScanner() {
			for (Constructor<?> constructor : UploadWorkflow.class.getConstructors()) {
				assertThat(Arrays.stream(constructor.getParameterTypes()).map(Class::getName))
					.as("public constructor %s must require an explicit MalwareScanner", Arrays.toString(constructor.getParameterTypes()))
					.contains(MalwareScanner.class.getName());
			}

			for (Method method : UploadWorkflow.class.getDeclaredMethods()) {
				if (method.isSynthetic() || !Modifier.isStatic(method.getModifiers()) || !Modifier.isPublic(method.getModifiers())) {
					continue;
				}
				assertThat(method.getName())
					.as("public static factory %s may only be the explicitly named test factory", method.getName())
					.isEqualTo("forTesting");
			}
		}
	}

	private static UploadWorkflow workflow(FakeObjectStore store) {
		return UploadWorkflow.forTesting(store, new ImageInspector(), () -> FIXED_UPLOAD);
	}

	private static FakeObjectStore staged(byte[] bytes, String contentType) {
		FakeObjectStore store = new FakeObjectStore();
		store.stage(UploadPurpose.GENERIC, new ObjectMetadata(bytes.length, contentType, VERSION), bytes);
		return store;
	}

	private static void assertDeletedTemp(FakeObjectStore store) {
		assertThat(store.deleteCount).isEqualTo(1);
		assertThat(store.lastDelete.boundary()).isEqualTo(StorageDomain.TEMP);
		assertThat(store.lastDelete.objectKey()).isEqualTo(StorageKeys.stagingKey(USER_ID, UPLOAD_ID).value());
	}

	private static byte[] raster(String format, int width, int height) throws IOException {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = image.createGraphics();
		graphics.setColor(Color.RED);
		graphics.fillRect(0, 0, width, height);
		graphics.dispose();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(format);
		if (!writers.hasNext()) {
			throw new IllegalStateException("no ImageWriter for " + format);
		}
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

	private static final class FakeObjectStore implements ObjectStore {

		int presignCount;
		int headCount;
		int readCount;
		int deleteCount;
		int claimCount;
		UserId presignUser;
		UploadId presignUpload;
		MediaDeclaration presignDeclaration;
		UploadPurpose presignPurpose;
		ObjectRef lastHead;
		ObjectRef lastRead;
		ObjectRef lastDelete;
		String lastReadVersion;
		int lastReadMaxBytes;
		ObjectMetadata metadata;
		byte[] payload;
		RuntimeException headFailure;
		RuntimeException readFailure;
		RuntimeException deleteFailure;
		final List<ObjectRef> deleted = new ArrayList<>();
		private final Map<String, StagedObject> objects = new HashMap<>();

		void stage(UploadPurpose purpose, ObjectMetadata stagedMetadata, byte[] stagedPayload) {
			objects.put(StorageKeys.stagingKey(USER_ID, UPLOAD_ID, purpose).value(),
				new StagedObject(stagedMetadata, stagedPayload));
		}

		@Override
		public UploadCapability presignStagingPut(UserId userId, UploadId uploadId, MediaDeclaration declaration) {
			return presignStagingPut(userId, uploadId, declaration, UploadPurpose.GENERIC);
		}

		@Override
		public UploadCapability presignStagingPut(
			UserId userId,
			UploadId uploadId,
			MediaDeclaration declaration,
			UploadPurpose purpose
		) {
			presignCount++;
			presignUser = userId;
			presignUpload = uploadId;
			presignDeclaration = declaration;
			presignPurpose = purpose;
			return new UploadCapability(
				uploadId,
				"PUT",
				URI.create("https://example.invalid/staging"),
				Map.of("host", "example.invalid"),
				Instant.parse("2026-08-23T12:00:00Z"));
		}

		@Override
		public ObjectMetadata head(ObjectRef object) {
			headCount++;
			lastHead = object;
			if (headFailure != null) {
				throw headFailure;
			}
			ObjectMetadata found = metadataFor(object);
			if (found == null) {
				throw new ObjectStoreExceptions.StagedObjectMissingException("missing staged object");
			}
			return found;
		}

		@Override
		public byte[] readBounded(ObjectRef object, String expectedVersion, int maxBytes) {
			readCount++;
			lastRead = object;
			lastReadVersion = expectedVersion;
			lastReadMaxBytes = maxBytes;
			if (readFailure != null) {
				throw readFailure;
			}
			byte[] found = payloadFor(object);
			if (found == null) {
				throw new ObjectStoreExceptions.StagedObjectMissingException("missing staged object");
			}
			return found;
		}

		@Override
		public void delete(ObjectRef object) {
			deleteCount++;
			lastDelete = object;
			deleted.add(object);
			if (deleteFailure != null) {
				throw deleteFailure;
			}
			if (objects.remove(object.objectKey()) != null) {
				return;
			}
			if (metadata != null && isGenericKey(object)) {
				metadata = null;
				payload = null;
				return;
			}
			throw new ObjectStoreExceptions.StagedObjectMissingException("missing staged object");
		}

		private ObjectMetadata metadataFor(ObjectRef object) {
			StagedObject staged = objects.get(object.objectKey());
			if (staged != null) {
				return staged.metadata;
			}
			if (metadata != null && isGenericKey(object)) {
				return metadata;
			}
			return null;
		}

		private byte[] payloadFor(ObjectRef object) {
			StagedObject staged = objects.get(object.objectKey());
			if (staged != null) {
				return staged.payload;
			}
			if (payload != null && isGenericKey(object)) {
				return payload;
			}
			return null;
		}

		private static boolean isGenericKey(ObjectRef object) {
			return StorageKeys.stagingKey(USER_ID, UPLOAD_ID, UploadPurpose.GENERIC).value()
				.equals(object.objectKey());
		}

		@Override
		public ClaimResult claim(ClaimCommand command) {
			claimCount++;
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

		private record StagedObject(ObjectMetadata metadata, byte[] payload) {
		}
	}
}
