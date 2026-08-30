package com.aifashionvault.shared;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AccountMediaPurgeContractTest {

	@Test
	void isPublicInterfaceWithExactlyPurgeForUserOfUserId() {
		assertThat(AccountMediaPurge.class.isInterface()).isTrue();
		assertThat(Modifier.isPublic(AccountMediaPurge.class.getModifiers())).isTrue();
		assertThat(AccountMediaPurge.class.getPackageName()).isEqualTo("com.aifashionvault.shared");

		Method[] methods = AccountMediaPurge.class.getDeclaredMethods();
		assertThat(methods).hasSize(1);

		Method method = methods[0];
		assertThat(method.getName()).isEqualTo("purgeForUser");
		assertThat(method.getParameterCount()).isEqualTo(1);
		assertThat(method.getParameterTypes()).containsExactly(UserId.class);
		assertThat(method.getReturnType()).isEqualTo(void.class);
		assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
		assertThat(method.isDefault()).isFalse();
	}

	@Test
	void sourceAndTypeNameDoNotContainObjectStoreOrPurgeUserObjects() throws Exception {
		assertThat(AccountMediaPurge.class.getName())
			.doesNotContain("ObjectStore")
			.doesNotContain("purgeUserObjects");
		assertThat(AccountMediaPurge.class.getTypeName())
			.doesNotContain("ObjectStore")
			.doesNotContain("purgeUserObjects");

		for (Method method : AccountMediaPurge.class.getDeclaredMethods()) {
			assertThat(method.getName())
				.doesNotContain("ObjectStore")
				.doesNotContain("purgeUserObjects");
			assertThat(method.getReturnType().getTypeName())
				.doesNotContain("ObjectStore")
				.doesNotContain("purgeUserObjects");
			for (Class<?> parameter : method.getParameterTypes()) {
				assertThat(parameter.getTypeName())
					.doesNotContain("ObjectStore")
					.doesNotContain("purgeUserObjects");
			}
		}

		String source = Files.readString(accountMediaPurgeSource(), StandardCharsets.UTF_8);
		assertThat(source)
			.doesNotContain("ObjectStore")
			.doesNotContain("purgeUserObjects");
	}

	private static Path accountMediaPurgeSource() {
		Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
		Path current = cwd;
		while (current != null) {
			Path direct = current.resolve(Path.of(
				"src",
				"main",
				"java",
				"com",
				"aifashionvault",
				"shared",
				"AccountMediaPurge.java"));
			if (Files.isRegularFile(direct)) {
				return direct;
			}
			Path underBackend = current.resolve("backend").resolve(Path.of(
				"src",
				"main",
				"java",
				"com",
				"aifashionvault",
				"shared",
				"AccountMediaPurge.java"));
			if (Files.isRegularFile(underBackend)) {
				return underBackend;
			}
			current = current.getParent();
		}
		throw new IllegalStateException("Cannot locate AccountMediaPurge.java from " + cwd);
	}
}
