package com.aifashionvault.platform.objectstore;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifashionvault.avatar.AvatarMediaAccess;
import com.aifashionvault.shared.StorageDomain;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObjectStoreTypesTest {

	private static final UserId USER_ID = new UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
	private static final UploadId UPLOAD_ID = new UploadId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
	private static final URI SIGNED_URI = URI.create("https://example.invalid/staging/put?X-Amz-Signature=redact-me-token");

	@Test
	void objectStoreDeclaresExactlyTheTenPortMethods() {
		assertThat(ObjectStore.class.isInterface()).isTrue();
		Method[] methods = ObjectStore.class.getDeclaredMethods();
		assertThat(methods).hasSize(10);
		assertThat(Arrays.stream(methods).map(Method::getName)).containsExactlyInAnyOrder(
			"presignStagingPut",
			"presignStagingPut",
			"head",
			"readBounded",
			"delete",
			"claim",
			"purgeUserObjects",
			"cleanupTemp",
			"presignGet",
			"list");

		assertThat(methodNamed(methods, "presignStagingPut", UserId.class, UploadId.class, MediaDeclaration.class)
			.getReturnType()).isEqualTo(UploadCapability.class);
		assertThat(methodNamed(
			methods,
			"presignStagingPut",
			UserId.class,
			UploadId.class,
			MediaDeclaration.class,
			UploadPurpose.class).getReturnType()).isEqualTo(UploadCapability.class);

		assertThat(methodNamed(methods, "head").getParameterTypes()).containsExactly(ObjectRef.class);
		assertThat(methodNamed(methods, "head").getReturnType()).isEqualTo(ObjectMetadata.class);

		assertThat(methodNamed(methods, "readBounded").getParameterTypes())
			.containsExactly(ObjectRef.class, String.class, int.class);
		assertThat(methodNamed(methods, "readBounded").getReturnType()).isEqualTo(byte[].class);

		assertThat(methodNamed(methods, "delete").getParameterTypes()).containsExactly(ObjectRef.class);
		assertThat(methodNamed(methods, "delete").getReturnType()).isEqualTo(void.class);

		assertThat(methodNamed(methods, "claim").getParameterTypes()).containsExactly(ClaimCommand.class);
		assertThat(methodNamed(methods, "claim").getReturnType()).isEqualTo(ClaimResult.class);

		assertThat(methodNamed(methods, "purgeUserObjects").getParameterTypes()).containsExactly(UserId.class);
		assertThat(methodNamed(methods, "purgeUserObjects").getReturnType()).isEqualTo(void.class);

		assertThat(methodNamed(methods, "cleanupTemp").getParameterTypes()).isEmpty();
		assertThat(methodNamed(methods, "cleanupTemp").getReturnType()).isEqualTo(void.class);

		assertThat(methodNamed(methods, "presignGet").getParameterTypes())
			.containsExactly(ObjectRef.class, Duration.class);
		assertThat(methodNamed(methods, "presignGet").getReturnType()).isEqualTo(PresignedAccess.class);

		Method list = methodNamed(methods, "list", StorageDomain.class, String.class);
		assertThat(list.getReturnType()).isEqualTo(List.class);
		assertThat(list.isDefault()).isTrue();
		ParameterizedType listReturnType = (ParameterizedType) list.getGenericReturnType();
		assertThat(listReturnType.getActualTypeArguments()).containsExactly(StoredObject.class);
	}

	@Test
	void listingDefaultsAreEmptyAndExposeOnlyStableMetadata() {
		ObjectStore objectStore = defaultMethodsOnly(ObjectStore.class);
		assertThat(objectStore.list(StorageDomain.AVATAR, "users/")).isEmpty();

		AvatarMediaAccess avatarMediaAccess = defaultMethodsOnly(AvatarMediaAccess.class);
		assertThat(avatarMediaAccess.listPersistentAvatars()).isEmpty();

		Method listPersistentAvatars = methodNamed(
			AvatarMediaAccess.class.getDeclaredMethods(),
			"listPersistentAvatars");
		assertThat(listPersistentAvatars.isDefault()).isTrue();
		ParameterizedType persistentObjects = (ParameterizedType) listPersistentAvatars.getGenericReturnType();
		assertThat(persistentObjects.getActualTypeArguments())
			.containsExactly(AvatarMediaAccess.PersistentObject.class);
	}

	@Test
	void storedObjectRejectsBlankKeysAndAllowsUnknownLastModified() {
		Instant lastModified = Instant.parse("2026-08-24T12:00:00Z");
		StoredObject stored = new StoredObject("users/u/avatars/a.jpg", lastModified);
		assertThat(stored.key()).isEqualTo("users/u/avatars/a.jpg");
		assertThat(stored.lastModified()).isEqualTo(lastModified);
		assertThat(new StoredObject("users/u/avatars/b.jpg", null).lastModified()).isNull();

		assertThatThrownBy(() -> new StoredObject(null, lastModified))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new StoredObject("", lastModified))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new StoredObject("   ", lastModified))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void persistentObjectRetainsStableKeyAndNullableTimestamp() {
		StorageKey key = new StorageKey("users/u/avatars/a.jpg");
		AvatarMediaAccess.PersistentObject object = new AvatarMediaAccess.PersistentObject(key, null);
		assertThat(object.key()).isEqualTo(key);
		assertThat(object.lastModified()).isNull();
	}

	@Test
	void objectStoreSignaturesHaveNoAmazonTypes() {
		for (Method method : ObjectStore.class.getDeclaredMethods()) {
			assertNoAmazonType(method.getGenericReturnType());
			for (Type parameter : method.getGenericParameterTypes()) {
				assertNoAmazonType(parameter);
			}
		}
	}

	@Test
	void ownedProductionSourcesHaveNoAmazonOrSpringTypes() throws IOException {
		List<String> files = List.of(
			"UploadId.java",
			"ObjectStore.java",
			"MediaDeclaration.java",
			"StoredObject.java",
			"ObjectMetadata.java",
			"UploadCapability.java",
			"VerifiedUpload.java",
			"ClaimCommand.java",
			"ClaimResult.java",
			"ImageMediaType.java",
			"ObjectStoreExceptions.java");
		for (String fileName : files) {
			String source = Files.readString(ownedProductionFile(fileName), StandardCharsets.UTF_8);
			assertThat(source).doesNotContain("software.amazon");
			assertThat(source).doesNotContain("org.springframework");
			assertThat(source).doesNotContain("@Component");
			assertThat(source).doesNotContain("@Service");
			assertThat(source).doesNotContain("@Bean");
		}
	}

	@Test
	void claimResultIsSealedWithCopiedReplayConflict() {
		assertThat(ClaimResult.class.isInterface()).isTrue();
		assertThat(ClaimResult.class.isSealed()).isTrue();
		assertThat(ClaimResult.class.getPermittedSubclasses()).containsExactlyInAnyOrder(
			ClaimResult.Copied.class,
			ClaimResult.Replay.class,
			ClaimResult.Conflict.class);
		assertThat(ClaimResult.Copied.class.isRecord()).isTrue();
		assertThat(ClaimResult.Replay.class.isRecord()).isTrue();
		assertThat(ClaimResult.Conflict.class.isRecord()).isTrue();
		assertThat(new ClaimResult.Copied()).isInstanceOf(ClaimResult.class);
		assertThat(new ClaimResult.Replay()).isInstanceOf(ClaimResult.class);
		assertThat(new ClaimResult.Conflict()).isInstanceOf(ClaimResult.class);
	}

	@Test
	void imageMediaTypeIsJpegAndPngOnly() {
		assertThat(ImageMediaType.class.isEnum()).isTrue();
		assertThat(ImageMediaType.values()).containsExactly(ImageMediaType.JPEG, ImageMediaType.PNG);
	}

	@Test
	void mediaDeclarationRejectsNullOrBlankContentTypeAndStoresLengthAsGiven() {
		MediaDeclaration declaration = new MediaDeclaration("image/jpeg", 0L);
		assertThat(declaration.contentType()).isEqualTo("image/jpeg");
		assertThat(declaration.contentLength()).isEqualTo(0L);
		assertThat(new MediaDeclaration("image/png", -12L).contentLength()).isEqualTo(-12L);

		assertThatThrownBy(() -> new MediaDeclaration(null, 10L)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new MediaDeclaration("", 10L)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new MediaDeclaration("   ", 10L)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void objectMetadataRejectsNullOrBlankContentTypeOrVersion() {
		ObjectMetadata metadata = new ObjectMetadata(8L, "image/png", "v1");
		assertThat(metadata.contentLength()).isEqualTo(8L);
		assertThat(metadata.contentType()).isEqualTo("image/png");
		assertThat(metadata.version()).isEqualTo("v1");

		assertThatThrownBy(() -> new ObjectMetadata(1L, null, "v1")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ObjectMetadata(1L, " ", "v1")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ObjectMetadata(1L, "image/jpeg", null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ObjectMetadata(1L, "image/jpeg", "")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void uploadCapabilityCopiesHeadersAndRedactsUriFromToString() {
		Map<String, String> headers = new HashMap<>();
		headers.put("host", "example.invalid");
		UploadCapability capability = new UploadCapability(
			UPLOAD_ID,
			"PUT",
			SIGNED_URI,
			headers,
			Instant.parse("2026-08-23T12:00:00Z"));

		headers.put("x-injected", "nope");
		assertThat(capability.signedHeaders()).containsExactlyEntriesOf(Map.of("host", "example.invalid"));
		assertThatThrownBy(() -> capability.signedHeaders().put("x", "y"))
			.isInstanceOf(UnsupportedOperationException.class);
		assertThat(capability.uri()).isEqualTo(SIGNED_URI);
		assertThat(capability.toString()).doesNotContain(SIGNED_URI.toString());
		assertThat(capability.toString()).doesNotContain("redact-me-token");
	}

	@Test
	void uploadCapabilityRejectsNullsAndBlankMethod() {
		Map<String, String> headers = Map.of("host", "example.invalid");
		Instant expiresAt = Instant.parse("2026-08-23T12:00:00Z");

		assertThatThrownBy(() -> new UploadCapability(null, "PUT", SIGNED_URI, headers, expiresAt))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new UploadCapability(UPLOAD_ID, null, SIGNED_URI, headers, expiresAt))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new UploadCapability(UPLOAD_ID, "  ", SIGNED_URI, headers, expiresAt))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new UploadCapability(UPLOAD_ID, "PUT", null, headers, expiresAt))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new UploadCapability(UPLOAD_ID, "PUT", SIGNED_URI, null, expiresAt))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new UploadCapability(UPLOAD_ID, "PUT", SIGNED_URI, headers, null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void verifiedUploadRejectsNullsBlankVersionAndNonPositiveDimensions() {
		VerifiedUpload upload = new VerifiedUpload(UPLOAD_ID, ImageMediaType.JPEG, 12L, 8, 6, "etag-1");
		assertThat(upload.id()).isEqualTo(UPLOAD_ID);
		assertThat(upload.mediaType()).isEqualTo(ImageMediaType.JPEG);
		assertThat(upload.contentLength()).isEqualTo(12L);
		assertThat(upload.width()).isEqualTo(8);
		assertThat(upload.height()).isEqualTo(6);
		assertThat(upload.version()).isEqualTo("etag-1");

		assertThatThrownBy(() -> new VerifiedUpload(null, ImageMediaType.PNG, 1L, 1, 1, "v"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new VerifiedUpload(UPLOAD_ID, null, 1L, 1, 1, "v"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new VerifiedUpload(UPLOAD_ID, ImageMediaType.PNG, 0L, 1, 1, "v"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new VerifiedUpload(UPLOAD_ID, ImageMediaType.PNG, 1L, 0, 1, "v"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new VerifiedUpload(UPLOAD_ID, ImageMediaType.PNG, 1L, 1, -1, "v"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new VerifiedUpload(UPLOAD_ID, ImageMediaType.PNG, 1L, 1, 1, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new VerifiedUpload(UPLOAD_ID, ImageMediaType.PNG, 1L, 1, 1, " "))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void claimCommandRejectsNullsAndBlankMarkerOrVersion() {
		StorageKey destination = new StorageKey("users/u/avatars/a.jpg");
		ClaimCommand command = new ClaimCommand(
			USER_ID,
			UPLOAD_ID,
			destination,
			StorageDomain.AVATAR,
			"marker-1",
			"source-v1");
		assertThat(command.userId()).isEqualTo(USER_ID);
		assertThat(command.uploadId()).isEqualTo(UPLOAD_ID);
		assertThat(command.destination()).isEqualTo(destination);
		assertThat(command.destinationBoundary()).isEqualTo(StorageDomain.AVATAR);
		assertThat(command.claimMarker()).isEqualTo("marker-1");
		assertThat(command.sourceVersion()).isEqualTo("source-v1");

		assertThatThrownBy(() -> new ClaimCommand(null, UPLOAD_ID, destination, StorageDomain.AVATAR, "m", "v"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ClaimCommand(USER_ID, null, destination, StorageDomain.AVATAR, "m", "v"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ClaimCommand(USER_ID, UPLOAD_ID, null, StorageDomain.AVATAR, "m", "v"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ClaimCommand(USER_ID, UPLOAD_ID, destination, null, "m", "v"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ClaimCommand(USER_ID, UPLOAD_ID, destination, StorageDomain.AVATAR, null, "v"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ClaimCommand(USER_ID, UPLOAD_ID, destination, StorageDomain.AVATAR, " ", "v"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ClaimCommand(USER_ID, UPLOAD_ID, destination, StorageDomain.AVATAR, "m", null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ClaimCommand(USER_ID, UPLOAD_ID, destination, StorageDomain.AVATAR, "m", ""))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void objectStoreExceptionsAreRuntimeExceptionsWithNonBlankMessages() throws Exception {
		assertThat(Modifier.isFinal(ObjectStoreExceptions.class.getModifiers())).isTrue();
		List<String> names = List.of(
			"InvalidUploadDeclarationException",
			"UnsupportedMediaTypeException",
			"StagedObjectMissingException",
			"ImageValidationException",
			"ClaimConflictException",
			"ObjectStoreUnavailableException");
		for (String name : names) {
			Class<?> type = Class.forName(ObjectStoreExceptions.class.getName() + "$" + name);
			assertThat(Modifier.isStatic(type.getModifiers())).isTrue();
			assertThat(Modifier.isFinal(type.getModifiers())).isTrue();
			assertThat(RuntimeException.class.isAssignableFrom(type)).isTrue();
			Constructor<?> constructor = type.getConstructor(String.class);
			RuntimeException exception = (RuntimeException) constructor.newInstance("missing staged object");
			assertThat(exception.getMessage()).isEqualTo("missing staged object");
			assertThatThrownBy(() -> {
				try {
					constructor.newInstance("  ");
				}
				catch (java.lang.reflect.InvocationTargetException ex) {
					throw ex.getCause();
				}
			}).isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void recordComponentsMatchFrozenShapes() {
		assertThat(componentTypes(MediaDeclaration.class)).containsExactly(String.class, long.class);
		assertThat(componentNames(MediaDeclaration.class)).containsExactly("contentType", "contentLength");
		assertThat(componentTypes(ObjectMetadata.class)).containsExactly(long.class, String.class, String.class);
		assertThat(componentNames(ObjectMetadata.class)).containsExactly("contentLength", "contentType", "version");
		assertThat(componentTypes(StoredObject.class)).containsExactly(String.class, Instant.class);
		assertThat(componentNames(StoredObject.class)).containsExactly("key", "lastModified");
		assertThat(componentTypes(AvatarMediaAccess.PersistentObject.class))
			.containsExactly(StorageKey.class, Instant.class);
		assertThat(componentNames(AvatarMediaAccess.PersistentObject.class))
			.containsExactly("key", "lastModified");
		assertThat(componentTypes(UploadCapability.class))
			.containsExactly(UploadId.class, String.class, URI.class, Map.class, Instant.class);
		assertThat(componentNames(UploadCapability.class))
			.containsExactly("id", "method", "uri", "signedHeaders", "expiresAt");
		assertThat(componentTypes(VerifiedUpload.class))
			.containsExactly(UploadId.class, ImageMediaType.class, long.class, int.class, int.class, String.class);
		assertThat(componentNames(ClaimCommand.class)).containsExactly(
			"userId",
			"uploadId",
			"destination",
			"destinationBoundary",
			"claimMarker",
			"sourceVersion");
	}

	private static <T> T defaultMethodsOnly(Class<T> port) {
		Object proxy = Proxy.newProxyInstance(
			port.getClassLoader(),
			new Class<?>[] { port },
			(instance, method, arguments) -> {
				if (method.isDefault()) {
					return InvocationHandler.invokeDefault(instance, method, arguments);
				}
				throw new AssertionError("unexpected abstract method " + method);
			});
		return port.cast(proxy);
	}

	private static Method methodNamed(Method[] methods, String name, Class<?>... parameterTypes) {
		return Arrays.stream(methods)
			.filter(method -> method.getName().equals(name))
			.filter(method -> parameterTypes.length == 0 || Arrays.equals(method.getParameterTypes(), parameterTypes))
			.findFirst()
			.orElseThrow(() -> new AssertionError("missing method " + name + Arrays.toString(parameterTypes)));
	}

	private static List<String> componentNames(Class<?> type) {
		return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
	}

	private static List<Class<?>> componentTypes(Class<?> type) {
		return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getType).toList();
	}

	private static void assertNoAmazonType(Type type) {
		if (type instanceof Class<?> cls) {
			assertThat(cls.getName()).doesNotStartWith("software.amazon");
			return;
		}
		if (type instanceof ParameterizedType parameterized) {
			assertNoAmazonType(parameterized.getRawType());
			for (Type argument : parameterized.getActualTypeArguments()) {
				assertNoAmazonType(argument);
			}
		}
	}

	private static Path ownedProductionFile(String fileName) {
		if (fileName.equals("UploadId.java")) {
			return locate("src", "main", "java", "com", "aifashionvault", "shared").resolve(fileName);
		}
		return locate("src", "main", "java", "com", "aifashionvault", "platform", "objectstore").resolve(fileName);
	}

	private static Path locate(String first, String... more) {
		Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
		Path current = cwd;
		while (current != null) {
			Path direct = current.resolve(Path.of(first, more));
			if (Files.isDirectory(direct)) {
				return direct;
			}
			Path underBackend = current.resolve("backend").resolve(Path.of(first, more));
			if (Files.isDirectory(underBackend)) {
				return underBackend;
			}
			current = current.getParent();
		}
		throw new IllegalStateException("Cannot locate " + Path.of(first, more) + " from " + cwd);
	}
}
