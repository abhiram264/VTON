package com.aifashionvault.platform.objectstore;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorageKeysTest {

	private static final UserId USER_ID = new UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
	private static final UserId OTHER_USER_ID = new UserId(UUID.fromString("33333333-3333-3333-3333-333333333333"));
	private static final UploadId UPLOAD_ID = new UploadId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
	private static final UUID STABLE_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
	private static final UUID JOB_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
	private static final UUID OBJECT_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");

	@Test
	void isPublicFinalUtilityWithPrivateConstructor() {
		assertThat(Modifier.isPublic(StorageKeys.class.getModifiers())).isTrue();
		assertThat(Modifier.isFinal(StorageKeys.class.getModifiers())).isTrue();
		Constructor<?>[] constructors = StorageKeys.class.getDeclaredConstructors();
		assertThat(constructors).hasSize(1);
		assertThat(Modifier.isPrivate(constructors[0].getModifiers())).isTrue();
		assertThat(constructors[0].getParameterCount()).isZero();
	}

	@Test
	void doesNotAcceptClientFilenamesOrRawUserIdStrings() {
		assertThatThrownBy(() -> UserId.class.getConstructor(String.class))
			.isInstanceOf(NoSuchMethodException.class);
		assertThat(UserId.class.getRecordComponents()[0].getType()).isEqualTo(UUID.class);
		for (Method method : StorageKeys.class.getDeclaredMethods()) {
			if (!Modifier.isPublic(method.getModifiers())) {
				continue;
			}
			assertThat(method.getReturnType()).isEqualTo(StorageKey.class);
			assertThat(Arrays.asList(method.getParameterTypes())).doesNotContain(String.class);
		}
	}

	@Test
	void wrapsBuiltKeysInStorageKeySoUrlMaterialIsStillRejected() throws Exception {
		String source = Files.readString(storageKeysSource(), StandardCharsets.UTF_8);
		assertThat(source).contains("new StorageKey(");
		assertThat(source).doesNotContain("filename");
		assertThatThrownBy(() -> new StorageKey("https://example.invalid/users/u/avatars/foo.jpg"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new StorageKey("data:image/png;base64,iVBORw0KGgo"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new StorageKey("users/u/avatars/a.jpg?X-Amz-Signature=abcdef"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new StorageKey("/9j/4AAQSkZJRgABAQAAAQABAAD"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Nested
	class StagingKey {

		@Test
		void usesTempUploadsGrammarWithNoExtension() {
			StorageKey key = StorageKeys.stagingKey(USER_ID, UPLOAD_ID);

			assertThat(key.value()).isEqualTo(
				"temp/11111111-1111-1111-1111-111111111111/uploads/22222222-2222-2222-2222-222222222222");
			assertThat(key.value()).contains("/uploads/");
			assertThat(key.value()).doesNotEndWith(".jpg");
			assertThat(key.value()).doesNotEndWith(".png");
			assertThat(key.value()).doesNotContain(".");
			assertThat(key.value()).doesNotContain("foo.jpg");
			assertThat(key.value()).contains(USER_ID.value().toString());
			assertThat(key.value()).doesNotContain(OTHER_USER_ID.value().toString());
		}

		@Test
		void genericPurposeMatchesTwoArgumentStagingKey() {
			StorageKey withPurpose = StorageKeys.stagingKey(USER_ID, UPLOAD_ID, UploadPurpose.GENERIC);
			StorageKey withoutPurpose = StorageKeys.stagingKey(USER_ID, UPLOAD_ID);

			assertThat(withPurpose.value()).isEqualTo(withoutPurpose.value());
			assertThat(withPurpose.value()).isEqualTo(
				"temp/11111111-1111-1111-1111-111111111111/uploads/22222222-2222-2222-2222-222222222222");
		}

		@Test
		void avatarPurposeUsesTempAvatarsGrammarWithNoExtension() {
			StorageKey key = StorageKeys.stagingKey(USER_ID, UPLOAD_ID, UploadPurpose.AVATAR);

			assertThat(key.value()).isEqualTo(
				"temp/11111111-1111-1111-1111-111111111111/avatars/22222222-2222-2222-2222-222222222222");
			assertThat(key.value()).contains("/avatars/");
			assertThat(key.value()).doesNotContain("/uploads/");
			assertThat(key.value()).doesNotEndWith(".jpg");
			assertThat(key.value()).doesNotEndWith(".png");
			assertThat(key.value()).doesNotContain(".");
			assertThat(key.value()).contains(USER_ID.value().toString());
			assertThat(key.value()).doesNotContain(OTHER_USER_ID.value().toString());
		}

		@Test
		void purposeEnumIsExactlyGenericAndAvatar() {
			assertThat(UploadPurpose.values()).containsExactly(UploadPurpose.GENERIC, UploadPurpose.AVATAR);
		}

		@Test
		void rejectsNullArguments() {
			assertThatThrownBy(() -> StorageKeys.stagingKey(null, UPLOAD_ID))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> StorageKeys.stagingKey(USER_ID, null))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> StorageKeys.stagingKey(null, UPLOAD_ID, UploadPurpose.GENERIC))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> StorageKeys.stagingKey(USER_ID, null, UploadPurpose.AVATAR))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> StorageKeys.stagingKey(USER_ID, UPLOAD_ID, null))
				.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Nested
	class PersistentKey {

		@Test
		void scopesAvatarVaultAndLookKeysToTheSuppliedUserId() {
			assertThat(StorageKeys.persistentKey(USER_ID, StorageBoundary.AVATAR, STABLE_ID, ImageMediaType.JPEG).value())
				.isEqualTo("users/11111111-1111-1111-1111-111111111111/avatars/44444444-4444-4444-4444-444444444444.jpg");
			assertThat(StorageKeys.persistentKey(USER_ID, StorageBoundary.AVATAR, STABLE_ID, ImageMediaType.PNG).value())
				.isEqualTo("users/11111111-1111-1111-1111-111111111111/avatars/44444444-4444-4444-4444-444444444444.png");
			assertThat(StorageKeys.persistentKey(USER_ID, StorageBoundary.VAULT, STABLE_ID, ImageMediaType.JPEG).value())
				.isEqualTo("users/11111111-1111-1111-1111-111111111111/vault/44444444-4444-4444-4444-444444444444.jpg");
			assertThat(StorageKeys.persistentKey(USER_ID, StorageBoundary.LOOK, STABLE_ID, ImageMediaType.PNG).value())
				.isEqualTo("users/11111111-1111-1111-1111-111111111111/looks/44444444-4444-4444-4444-444444444444.png");
		}

		@Test
		void neverIncludesAnotherUsersIdOrAClientFilename() {
			StorageKey key = StorageKeys.persistentKey(USER_ID, StorageBoundary.VAULT, STABLE_ID, ImageMediaType.JPEG);

			assertThat(key.value()).contains(USER_ID.value().toString());
			assertThat(key.value()).doesNotContain(OTHER_USER_ID.value().toString());
			assertThat(key.value()).doesNotContain("../");
			assertThat(key.value()).doesNotContain("foo.jpg");
			assertThat(key.value()).endsWith(".jpg");
			assertThat(StorageKeys.persistentKey(USER_ID, StorageBoundary.LOOK, STABLE_ID, ImageMediaType.PNG).value())
				.endsWith(".png");
		}

		@Test
		void rejectsTempBoundary() {
			assertThatThrownBy(() -> StorageKeys.persistentKey(USER_ID, StorageBoundary.TEMP, STABLE_ID, ImageMediaType.JPEG))
				.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		void rejectsNullArguments() {
			assertThatThrownBy(() -> StorageKeys.persistentKey(null, StorageBoundary.AVATAR, STABLE_ID, ImageMediaType.JPEG))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> StorageKeys.persistentKey(USER_ID, null, STABLE_ID, ImageMediaType.JPEG))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> StorageKeys.persistentKey(USER_ID, StorageBoundary.AVATAR, null, ImageMediaType.JPEG))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> StorageKeys.persistentKey(USER_ID, StorageBoundary.AVATAR, STABLE_ID, null))
				.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Nested
	class GenerationTempKey {

		@Test
		void usesJobScopedGrammarNotUploads() {
			StorageKey jpeg = StorageKeys.generationTempKey(USER_ID, JOB_ID, OBJECT_ID, ImageMediaType.JPEG);
			StorageKey png = StorageKeys.generationTempKey(USER_ID, JOB_ID, OBJECT_ID, ImageMediaType.PNG);

			assertThat(jpeg.value()).isEqualTo(
				"temp/11111111-1111-1111-1111-111111111111/55555555-5555-5555-5555-555555555555/66666666-6666-6666-6666-666666666666.jpg");
			assertThat(png.value()).isEqualTo(
				"temp/11111111-1111-1111-1111-111111111111/55555555-5555-5555-5555-555555555555/66666666-6666-6666-6666-666666666666.png");
			assertThat(jpeg.value()).doesNotContain("/uploads/");
			assertThat(jpeg.value()).contains(USER_ID.value().toString());
			assertThat(jpeg.value()).doesNotContain(OTHER_USER_ID.value().toString());
			assertThat(jpeg.value()).doesNotContain("foo.jpg");
		}

		@Test
		void rejectsNullArguments() {
			assertThatThrownBy(() -> StorageKeys.generationTempKey(null, JOB_ID, OBJECT_ID, ImageMediaType.JPEG))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> StorageKeys.generationTempKey(USER_ID, null, OBJECT_ID, ImageMediaType.JPEG))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> StorageKeys.generationTempKey(USER_ID, JOB_ID, null, ImageMediaType.JPEG))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> StorageKeys.generationTempKey(USER_ID, JOB_ID, OBJECT_ID, null))
				.isInstanceOf(IllegalArgumentException.class);
		}
	}

	private static Path storageKeysSource() {
		Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
		Path current = cwd;
		while (current != null) {
			Path direct = current.resolve(Path.of(
				"src", "main", "java", "com", "aifashionvault", "platform", "objectstore", "StorageKeys.java"));
			if (Files.isRegularFile(direct)) {
				return direct;
			}
			Path underBackend = current.resolve("backend").resolve(Path.of(
				"src", "main", "java", "com", "aifashionvault", "platform", "objectstore", "StorageKeys.java"));
			if (Files.isRegularFile(underBackend)) {
				return underBackend;
			}
			current = current.getParent();
		}
		throw new IllegalStateException("Cannot locate StorageKeys.java from " + cwd);
	}
}
