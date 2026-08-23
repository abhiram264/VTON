package com.aifashionvault.platform.web;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.aifashionvault.platform.objectstore.ObjectStore;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.user.IdentityProvisioning;
import com.aifashionvault.user.JdbcIdentityProvisioning;
import com.aifashionvault.user.User;
import com.aifashionvault.user.UserDeletion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class MeDeletionDoesNotPurgeStorageTest {

	private static final UserId USER_ID = new UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"));

	private static final List<String> STORAGE_RELATIONSHIPS = List.of(
		"object_store",
		"objectstore",
		"ObjectStore",
		"storage",
		"s3",
		"purgeUserObjects",
		"users/{userId}/avatars",
		"temp");

	@Test
	void meControllerDoesNotMentionObjectStoreOrPurgeUserObjects() {
		assertNoObjectStoreOrPurge(MeController.class);
	}

	@Test
	void jdbcIdentityProvisioningHasNoObjectStoreAndBeginDeletionSignatureStays() {
		assertThat(JdbcIdentityProvisioning.class.getDeclaredFields())
			.noneMatch(MeDeletionDoesNotPurgeStorageTest::isObjectStoreField);

		Method[] beginDeletion = Arrays.stream(JdbcIdentityProvisioning.class.getDeclaredMethods())
			.filter(method -> method.getName().equals("beginDeletion"))
			.toArray(Method[]::new);
		assertThat(beginDeletion).hasSize(1);
		assertThat(beginDeletion[0].getParameterTypes()).containsExactly(UserId.class, Instant.class);
		assertThat(beginDeletion[0].getReturnType()).isEqualTo(User.class);
		assertNoObjectStoreOrPurge(beginDeletion[0]);
	}

	@Test
	void deleteDoesNotInvokePurgeUserObjects() {
		IdentityProvisioning provisioning = mock(IdentityProvisioning.class);
		ObjectStore objectStore = mock(ObjectStore.class);
		@SuppressWarnings("unchecked")
		ObjectProvider<IdentityProvisioning> identityProvisioning = mock(ObjectProvider.class);
		when(identityProvisioning.getIfAvailable()).thenReturn(provisioning);

		new MeController(identityProvisioning).delete(USER_ID);

		verify(provisioning).beginDeletion(eq(USER_ID), any(Instant.class));
		verifyNoMoreInteractions(provisioning);
		verify(objectStore, never()).purgeUserObjects(any());
		verifyNoInteractions(objectStore);
	}

	@Test
	void userDeletionMayCascadeRemainsUnrelatedToStorage() {
		assertThat(UserDeletion.mayCascade("collection_item")).isTrue();
		for (String relationshipName : STORAGE_RELATIONSHIPS) {
			assertThat(UserDeletion.mayCascade(relationshipName))
				.as(relationshipName)
				.isFalse();
		}
		assertNoObjectStoreOrPurge(UserDeletion.class);
	}

	private static boolean isObjectStoreField(Field field) {
		return mentionsObjectStoreOrPurge(field.getName()) || mentionsObjectStore(field.getGenericType());
	}

	private static void assertNoObjectStoreOrPurge(Class<?> type) {
		for (Field field : type.getDeclaredFields()) {
			assertThat(field.getName())
				.as(type.getSimpleName() + "." + field.getName())
				.doesNotContain("ObjectStore")
				.doesNotContain("purgeUserObjects");
			assertNoObjectStoreType(field.getGenericType(), type.getSimpleName() + "." + field.getName());
		}
		for (Constructor<?> constructor : type.getDeclaredConstructors()) {
			assertNoObjectStoreOrPurge(constructor);
		}
		for (Method method : type.getDeclaredMethods()) {
			assertNoObjectStoreOrPurge(method);
		}
	}

	private static void assertNoObjectStoreOrPurge(Constructor<?> constructor) {
		assertThat(constructor.getName()).doesNotContain("ObjectStore").doesNotContain("purgeUserObjects");
		for (Type parameter : constructor.getGenericParameterTypes()) {
			assertNoObjectStoreType(parameter, constructor.toGenericString());
		}
	}

	private static void assertNoObjectStoreOrPurge(Method method) {
		assertThat(method.getName())
			.as(method.toGenericString())
			.doesNotContain("ObjectStore")
			.doesNotContain("purgeUserObjects");
		assertNoObjectStoreType(method.getGenericReturnType(), method.toGenericString());
		for (Type parameter : method.getGenericParameterTypes()) {
			assertNoObjectStoreType(parameter, method.toGenericString());
		}
	}

	private static void assertNoObjectStoreType(Type type, String owner) {
		assertThat(mentionsObjectStore(type))
			.as(owner + " mentions ObjectStore via " + type.getTypeName())
			.isFalse();
		assertThat(type.getTypeName())
			.as(owner)
			.doesNotContain("purgeUserObjects");
	}

	private static boolean mentionsObjectStore(Type type) {
		if (type == null) {
			return false;
		}
		if (type.getTypeName().contains("ObjectStore")) {
			return true;
		}
		if (type instanceof Class<?> cls) {
			return ObjectStore.class.isAssignableFrom(cls);
		}
		if (type instanceof ParameterizedType parameterized) {
			if (mentionsObjectStore(parameterized.getRawType())) {
				return true;
			}
			for (Type argument : parameterized.getActualTypeArguments()) {
				if (mentionsObjectStore(argument)) {
					return true;
				}
			}
			return false;
		}
		if (type instanceof WildcardType wildcard) {
			for (Type bound : wildcard.getUpperBounds()) {
				if (mentionsObjectStore(bound)) {
					return true;
				}
			}
			for (Type bound : wildcard.getLowerBounds()) {
				if (mentionsObjectStore(bound)) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean mentionsObjectStoreOrPurge(String name) {
		return name.contains("ObjectStore") || name.contains("purgeUserObjects");
	}
}
