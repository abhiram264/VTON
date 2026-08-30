package com.aifashionvault.collection;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.aifashionvault.shared.CollectionId;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.shared.VaultItemId;

public interface CollectionStore {
	Collection create(UserId ownerId, CollectionId collectionId, String name, Instant now);
	List<Collection> list(UserId ownerId);
	Optional<Collection> find(UserId ownerId, CollectionId collectionId);
	Optional<Collection> rename(UserId ownerId, CollectionId collectionId, String name, Instant now);
	boolean delete(UserId ownerId, CollectionId collectionId);
	MembershipResult addItem(UserId ownerId, CollectionId collectionId, VaultItemId vaultItemId, Instant now);
	MembershipResult removeItem(UserId ownerId, CollectionId collectionId, VaultItemId vaultItemId, Instant now);
}
