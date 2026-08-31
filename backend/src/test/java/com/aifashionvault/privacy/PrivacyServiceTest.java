package com.aifashionvault.privacy;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifashionvault.shared.AvatarProfileId;
import com.aifashionvault.shared.LookId;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.shared.VaultItemId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrivacyServiceTest {

	private static final UserId USER = new UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
	private static final Instant NOW = Instant.parse("2026-08-31T12:00:00Z");

	@Test
	void exposesExactlyFivePublicOperations() {
		assertThat(Set.of(PrivacyService.class.getDeclaredMethods()).stream().map(Method::getName))
				.containsExactlyInAnyOrder("deleteAvatar", "deleteVaultItem", "deleteLook", "exportUserData", "deleteAccount");
	}

	@Test
	void delegatesOwnerScopedIdempotentDeletes() {
		RecordingResources resources = new RecordingResources();
		PrivacyService service = service(resources);

		assertThat(service.deleteAvatar(USER, new AvatarProfileId(UUID.randomUUID()))).isEqualTo(DeletionResult.DELETED_OR_ABSENT);
		assertThat(service.deleteVaultItem(USER, new VaultItemId(UUID.randomUUID()))).isEqualTo(DeletionResult.CONFLICT);
		assertThat(service.deleteLook(USER, new LookId(UUID.randomUUID()))).isEqualTo(DeletionResult.DELETED_OR_ABSENT);
		assertThat(resources.owners).containsExactly(USER, USER, USER);
	}

	@Test
	void exportUsesFixedTimeAndRejectsForbiddenOrCrossOwnerData() {
		RecordingResources resources = new RecordingResources();
		PrivacyService service = service(resources);
		assertThat(service.exportUserData(USER).generatedAt()).isEqualTo(NOW);

		resources.export = export(USER, Map.of("avatar_url", "https://secret"));
		assertThatThrownBy(() -> service.exportUserData(USER)).isInstanceOf(IllegalStateException.class);
		resources.export = export(USER, Map.of("items", List.of(Map.of("callback_url", "https://secret"))));
		assertThatThrownBy(() -> service.exportUserData(USER)).isInstanceOf(IllegalStateException.class);
		resources.export = export(new UserId(UUID.randomUUID()), Map.of());
		assertThatThrownBy(() -> service.exportUserData(USER)).isInstanceOf(IllegalStateException.class);
	}

	private static PrivacyService service(RecordingResources resources) {
		AccountDeletionWorkflow workflow = new AccountDeletionWorkflow() {
			@Override public AccountDeletionStatus request(UserId userId) { return null; }
			@Override public AccountDeletionStatus resume(UserId userId) { return null; }
		};
		return new DefaultPrivacyService(resources, workflow, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private static UserDataExport export(UserId owner, Map<String, Object> account) {
		return new UserDataExport("1", owner, NOW, account, List.of(), List.of(), List.of(), List.of(), List.of());
	}

	private static final class RecordingResources implements PrivacyResourcePort {
		private final java.util.ArrayList<UserId> owners = new java.util.ArrayList<>();
		private UserDataExport export = export(USER, Map.of("email", "masked@example.invalid"));

		@Override public DeletionResult deleteAvatar(UserId userId, AvatarProfileId id) { owners.add(userId); return DeletionResult.DELETED_OR_ABSENT; }
		@Override public DeletionResult deleteVaultItem(UserId userId, VaultItemId id) { owners.add(userId); return DeletionResult.CONFLICT; }
		@Override public DeletionResult deleteLook(UserId userId, LookId id) { owners.add(userId); return DeletionResult.DELETED_OR_ABSENT; }
		@Override public UserDataExport exportUserData(UserId userId, Instant generatedAt) { return export; }
	}
}
