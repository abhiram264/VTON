package com.aifashionvault.platform.web;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aifashionvault.platform.objectstore.ObjectStore;
import com.aifashionvault.shared.AccountMediaPurge;
import com.aifashionvault.user.JdbcIdentityProvisioning;
import com.aifashionvault.user.UserDeletion;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Erasure layering, asserted by TYPE rather than by collaborator name.
 *
 * <p>This file was named MeDeletionDoesNotPurgeStorageTest and asserted that
 * JdbcIdentityProvisioning declared no ObjectStore field and that DELETE /me
 * invoked no purge. Those assertions describe the pre-a875a49 defect. a875a49
 * fixed it by introducing an AccountMediaPurge collaborator, so the name-based
 * checks kept passing while describing something the code no longer did: a
 * string test for "ObjectStore" cannot see List<AccountMediaPurge>, and the
 * mocked ObjectStore was never wired into MeController, which made
 * verifyNoInteractions trivially true.
 *
 * <p>What is asserted here is the guarantee that matters: identity provisioning
 * reaches media through the narrow AccountMediaPurge port and never through the
 * storage SDK, and the user-deletion cascade stays unrelated to storage. Purge
 * ordering and throwing-purge behaviour are already covered by
 * AccountDeletionOrchestratorTest and are deliberately not repeated.
 *
 * <p>Erasure is NOT asserted generically: consent rows are retained by design
 * (V010 ON DELETE RESTRICT) and that retention is an unmade owner decision.
 */
class MeDeletionErasureLayeringTest {

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
	void identityProvisioningCollaboratesThroughAccountMediaPurgeNotTheStorageSdk() {
		assertThat(declaresFieldOfType(JdbcIdentityProvisioning.class, AccountMediaPurge.class))
			.as("a875a49 stores an ordered AccountMediaPurge collaborator")
			.isTrue();

		assertThat(declaresFieldOfType(JdbcIdentityProvisioning.class, ObjectStore.class))
			.as("identity provisioning must not reach the storage SDK directly")
			.isFalse();
	}

	@Test
	void meControllerExposesNoStorageCollaboratorAtAll() {
		for (Field field : MeController.class.getDeclaredFields()) {
			assertThat(field.getGenericType().getTypeName())
				.as("MeController." + field.getName() + " must not name a storage type")
				.doesNotContain("ObjectStore")
				.doesNotContain("AccountMediaPurge");
		}
	}

	@Test
	void userDeletionCascadeStaysUnrelatedToStorage() {
		assertThat(UserDeletion.mayCascade("collection_item")).isTrue();
		for (String relationshipName : STORAGE_RELATIONSHIPS) {
			assertThat(UserDeletion.mayCascade(relationshipName))
				.as(relationshipName)
				.isFalse();
		}
	}

	/**
	 * True when owner declares a field whose type mentions candidate, walking
	 * generic type arguments. This is exactly what the old name-based check
	 * could not do: the a875a49 collaborator is declared as
	 * List&lt;AccountMediaPurge&gt;, so a raw-type or String check never sees it.
	 */
	private static boolean declaresFieldOfType(Class<?> owner, Class<?> candidate) {
		for (Field field : owner.getDeclaredFields()) {
			if (mentions(field.getGenericType(), candidate)) {
				return true;
			}
		}
		return false;
	}

	private static boolean mentions(Type type, Class<?> candidate) {
		if (type == null) {
			return false;
		}
		if (type instanceof Class<?> cls) {
			return candidate.isAssignableFrom(cls);
		}
		if (type instanceof ParameterizedType parameterized) {
			return mentions(parameterized.getRawType(), candidate)
				|| Arrays.stream(parameterized.getActualTypeArguments())
					.anyMatch(argument -> mentions(argument, candidate));
		}
		if (type instanceof WildcardType wildcard) {
			return Arrays.stream(wildcard.getUpperBounds())
				.anyMatch(bound -> mentions(bound, candidate));
		}
		return false;
	}
}
