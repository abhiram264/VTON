package com.aifashionvault.privacy;

import java.time.Instant;

import com.aifashionvault.shared.AvatarProfileId;
import com.aifashionvault.shared.LookId;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.shared.VaultItemId;

public interface PrivacyResourcePort {

	DeletionResult deleteAvatar(UserId userId, AvatarProfileId avatarProfileId);

	DeletionResult deleteVaultItem(UserId userId, VaultItemId vaultItemId);

	DeletionResult deleteLook(UserId userId, LookId lookId);

	UserDataExport exportUserData(UserId userId, Instant generatedAt);
}
