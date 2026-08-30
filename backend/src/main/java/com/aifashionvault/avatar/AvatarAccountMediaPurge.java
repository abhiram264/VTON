package com.aifashionvault.avatar;

import java.util.List;

import com.aifashionvault.shared.AccountMediaPurge;
import com.aifashionvault.shared.UserId;

public class AvatarAccountMediaPurge implements AccountMediaPurge {

	private final JdbcAvatarStore store;
	private final AvatarMediaAccess mediaAccess;

	public AvatarAccountMediaPurge(JdbcAvatarStore store, AvatarMediaAccess mediaAccess) {
		if (store == null) {
			throw new IllegalArgumentException("store is required");
		}
		if (mediaAccess == null) {
			throw new IllegalArgumentException("mediaAccess is required");
		}
		this.store = store;
		this.mediaAccess = mediaAccess;
	}

	@Override
	public void purgeForUser(UserId userId) {
		if (userId == null) {
			throw new IllegalArgumentException("userId is required");
		}
		store.findByUser(userId).ifPresent(profile ->
			store.logicalDelete(profile.id(), userId, profile.revision()));
		List<AvatarImage> images = store.listImagesForUser(userId);
		for (AvatarImage image : images) {
			mediaAccess.deletePersistent(image.storageKey());
		}
		for (AvatarImage image : images) {
			store.markReconciled(image.id());
		}
	}
}
