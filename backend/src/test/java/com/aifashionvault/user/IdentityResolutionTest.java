package com.aifashionvault.user;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentityResolutionTest {

	private static final UserId USER_ID = new UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"));

	@Test
	void identityResolutionIsSealedWithActiveAndDeletedUserId() {
		assertThat(IdentityResolution.class.isInterface()).isTrue();
		assertThat(IdentityResolution.class.isSealed()).isTrue();
		assertThat(IdentityResolution.class.getPermittedSubclasses())
			.containsExactlyInAnyOrder(IdentityResolution.Active.class, IdentityResolution.Deleted.class);

		assertThat(IdentityResolution.Active.class.isRecord()).isTrue();
		assertThat(IdentityResolution.Deleted.class.isRecord()).isTrue();
		assertThat(IdentityResolution.Active.class.getRecordComponents())
			.extracting(RecordComponent::getName)
			.containsExactly("userId")
			.doesNotContain("orgId", "tenantId", "firebaseUid");
		assertThat(IdentityResolution.Active.class.getRecordComponents())
			.extracting(RecordComponent::getType)
			.containsExactly(UserId.class);
		assertThat(IdentityResolution.Deleted.class.getRecordComponents())
			.extracting(RecordComponent::getName)
			.containsExactly("userId")
			.doesNotContain("orgId", "tenantId", "firebaseUid");
		assertThat(IdentityResolution.Deleted.class.getRecordComponents())
			.extracting(RecordComponent::getType)
			.containsExactly(UserId.class);
	}

	@Test
	void activeAndDeletedWrapUserId() {
		IdentityResolution.Active active = new IdentityResolution.Active(USER_ID);
		IdentityResolution.Deleted deleted = new IdentityResolution.Deleted(USER_ID);

		assertThat(active).isInstanceOf(IdentityResolution.class);
		assertThat(deleted).isInstanceOf(IdentityResolution.class);
		assertThat(active.userId()).isEqualTo(USER_ID);
		assertThat(deleted.userId()).isEqualTo(USER_ID);
	}

	@Test
	void activeAndDeletedRejectNullUserId() {
		assertThatThrownBy(() -> new IdentityResolution.Active(null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new IdentityResolution.Deleted(null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void identityProvisioningDeclaresResolveAndBeginDeletionOnly() {
		assertThat(IdentityProvisioning.class.isInterface()).isTrue();
		assertThat(IdentityProvisioning.class.isAnnotation()).isFalse();
		assertThat(Modifier.isInterface(IdentityProvisioning.class.getModifiers())).isTrue();

		Method[] methods = IdentityProvisioning.class.getDeclaredMethods();
		assertThat(methods).hasSize(2);
		assertThat(methods).allMatch(method -> Modifier.isAbstract(method.getModifiers()));
		assertThat(Arrays.stream(methods).map(Method::getName))
			.containsExactlyInAnyOrder("resolve", "beginDeletion");

		assertThat(methodNamed(methods, "resolve").getParameterTypes())
			.containsExactly(String.class, String.class);
		assertThat(methodNamed(methods, "resolve").getReturnType()).isEqualTo(IdentityResolution.class);
		assertThat(methodNamed(methods, "beginDeletion").getParameterTypes())
			.containsExactly(UserId.class, Instant.class);
		assertThat(methodNamed(methods, "beginDeletion").getReturnType()).isEqualTo(User.class);
	}

	@Test
	void identityTypesHaveNoSpringOrJdbcAnnotations() {
		assertThat(annotationNames(IdentityResolution.class))
			.noneMatch(IdentityResolutionTest::isSpringOrJdbc);
		assertThat(annotationNames(IdentityResolution.Active.class))
			.noneMatch(IdentityResolutionTest::isSpringOrJdbc);
		assertThat(annotationNames(IdentityResolution.Deleted.class))
			.noneMatch(IdentityResolutionTest::isSpringOrJdbc);
		assertThat(annotationNames(IdentityProvisioning.class))
			.noneMatch(IdentityResolutionTest::isSpringOrJdbc);
	}

	@Test
	void userPackageDoesNotImportPlatform() throws IOException {
		Path userRoot = userSourceRoot();
		List<String> violations = new ArrayList<>();
		try (Stream<Path> walk = Files.walk(userRoot)) {
			for (Path file : walk.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java")).toList()) {
				List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
				for (int i = 0; i < lines.size(); i++) {
					String trimmed = lines.get(i).trim();
					if (trimmed.startsWith("import ") && trimmed.contains("com.aifashionvault.platform")) {
						violations.add(file.getFileName() + ":" + (i + 1) + " " + trimmed);
					}
				}
			}
		}
		assertThat(violations).isEmpty();
	}

	private static Method methodNamed(Method[] methods, String name) {
		return Arrays.stream(methods)
			.filter(method -> method.getName().equals(name))
			.findFirst()
			.orElseThrow(() -> new AssertionError("missing method " + name));
	}

	private static List<String> annotationNames(Class<?> type) {
		return Arrays.stream(type.getDeclaredAnnotations())
			.map(annotation -> annotation.annotationType().getName())
			.toList();
	}

	private static boolean isSpringOrJdbc(String annotationName) {
		return annotationName.startsWith("org.springframework")
			|| annotationName.startsWith("java.sql")
			|| annotationName.startsWith("javax.sql")
			|| annotationName.startsWith("jakarta.sql");
	}

	private static Path userSourceRoot() {
		Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
		Path current = cwd;
		while (current != null) {
			Path direct = current.resolve(Path.of("src", "main", "java", "com", "aifashionvault", "user"));
			if (Files.isDirectory(direct)) {
				return direct;
			}
			Path underBackend = current.resolve("backend").resolve(Path.of("src", "main", "java", "com", "aifashionvault", "user"));
			if (Files.isDirectory(underBackend)) {
				return underBackend;
			}
			current = current.getParent();
		}
		throw new IllegalStateException("Cannot locate user sources from " + cwd);
	}
}
