package com.aifashionvault.user;

import com.aifashionvault.shared.UserId;

public sealed interface IdentityResolution {

	record Active(UserId userId) implements IdentityResolution {

		public Active {
			if (userId == null) {
				throw new IllegalArgumentException("userId is required");
			}
		}
	}

	record Deleted(UserId userId) implements IdentityResolution {

		public Deleted {
			if (userId == null) {
				throw new IllegalArgumentException("userId is required");
			}
		}
	}
}
