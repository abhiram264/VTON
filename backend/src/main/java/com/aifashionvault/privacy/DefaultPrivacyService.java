package com.aifashionvault.privacy;

import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.aifashionvault.shared.AvatarProfileId;
import com.aifashionvault.shared.LookId;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.shared.VaultItemId;

public final class DefaultPrivacyService implements PrivacyService {

	private static final Set<String> FORBIDDEN_EXPORT_KEY_PARTS = Set.of("base64", "bytes", "credential", "lease",
			"media", "objectkey", "payload", "provider", "pushtoken", "token", "url");

	private final PrivacyResourcePort resources;
	private final AccountDeletionWorkflow workflow;
	private final Clock clock;

	public DefaultPrivacyService(PrivacyResourcePort resources, AccountDeletionWorkflow workflow, Clock clock) {
		this.resources = Objects.requireNonNull(resources, "resources is required");
		this.workflow = Objects.requireNonNull(workflow, "workflow is required");
		this.clock = Objects.requireNonNull(clock, "clock is required");
	}

	@Override
	public DeletionResult deleteAvatar(UserId userId, AvatarProfileId avatarProfileId) {
		return resources.deleteAvatar(required(userId, "userId"), required(avatarProfileId, "avatarProfileId"));
	}

	@Override
	public DeletionResult deleteVaultItem(UserId userId, VaultItemId vaultItemId) {
		return resources.deleteVaultItem(required(userId, "userId"), required(vaultItemId, "vaultItemId"));
	}

	@Override
	public DeletionResult deleteLook(UserId userId, LookId lookId) {
		return resources.deleteLook(required(userId, "userId"), required(lookId, "lookId"));
	}

	@Override
	public UserDataExport exportUserData(UserId userId) {
		UserId owner = required(userId, "userId");
		UserDataExport export = Objects.requireNonNull(resources.exportUserData(owner, clock.instant()),
				"resource export is required");
		if (!owner.equals(export.userId())) {
			throw new IllegalStateException("export owner mismatch");
		}
		validateSafe(export.account());
		export.consents().forEach(DefaultPrivacyService::validateSafe);
		export.avatars().forEach(DefaultPrivacyService::validateSafe);
		export.vaultItems().forEach(DefaultPrivacyService::validateSafe);
		export.looks().forEach(DefaultPrivacyService::validateSafe);
		export.generations().forEach(DefaultPrivacyService::validateSafe);
		return export;
	}

	@Override
	public AccountDeletionStatus deleteAccount(UserId userId) {
		return workflow.request(required(userId, "userId"));
	}

	private static void validateSafe(Map<?, ?> values) {
		for (Map.Entry<?, ?> entry : values.entrySet()) {
			if (!(entry.getKey() instanceof String key)) {
				throw new IllegalStateException("export keys must be strings");
			}
			String normalizedKey = key.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
			if (FORBIDDEN_EXPORT_KEY_PARTS.stream().anyMatch(normalizedKey::contains)) {
				throw new IllegalStateException("export contains forbidden field");
			}
			validateSafeValue(entry.getValue());
		}
	}

	private static void validateSafeValue(Object value) {
		if (value instanceof Map<?, ?> nested) {
			validateSafe(nested);
		} else if (value instanceof Iterable<?> nested) {
			nested.forEach(DefaultPrivacyService::validateSafeValue);
		}
	}

	private static <T> T required(T value, String name) {
		return Objects.requireNonNull(value, name + " is required");
	}
}
