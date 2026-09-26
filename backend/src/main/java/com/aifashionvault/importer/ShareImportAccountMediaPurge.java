package com.aifashionvault.importer;

import java.util.Objects;

import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.shared.AccountMediaPurge;

/**
 * Account-deletion purge port. Rows remain retryable until every owned object
 * deletion returns successfully or is confirmed already absent.
 */
public class ShareImportAccountMediaPurge implements AccountMediaPurge {

	private final JdbcShareImportStore store;
	private final ImportMediaAccess mediaAccess;

	public ShareImportAccountMediaPurge(JdbcShareImportStore store, ImportMediaAccess mediaAccess) {
		this.store = Objects.requireNonNull(store, "store");
		this.mediaAccess = Objects.requireNonNull(mediaAccess, "mediaAccess");
	}

	@Override
	public void purgeForUser(UserId userId) {
		Objects.requireNonNull(userId, "userId");
		for (StorageKey key : store.storageKeysFor(userId)) {
			mediaAccess.deleteObject(key);
		}
		store.purgeForUser(userId);
	}
}
