package com.aifashionvault.user;

import java.time.Instant;

public final class UserDeletion {

	private UserDeletion() {
	}

	public static User markDeleted(User user, Instant deletedAt) {
		return user.markDeleted(deletedAt);
	}

	public static boolean mayCascade(String relationshipName) {
		if (relationshipName == null) {
			throw new IllegalArgumentException("relationshipName is required");
		}
		return "collection_item".equals(relationshipName);
	}
}
