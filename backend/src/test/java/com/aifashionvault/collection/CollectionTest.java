package com.aifashionvault.collection;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifashionvault.shared.CollectionId;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.shared.VaultItemId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CollectionTest {

	private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");
	private static final Instant UPDATED_AT = Instant.parse("2026-01-02T00:00:00Z");
	private static final Instant MEMBER_AT = Instant.parse("2026-01-03T00:00:00Z");
	private static final UUID VAULT_ITEM_UUID = UUID.fromString("33333333-3333-3333-3333-333333333333");

	@Test
	void collectionItemRequiresEveryComponentAndHasNoCollectionItemId() {
		CollectionId collectionId = collectionId();
		VaultItemId vaultItemId = vaultItemId();

		assertThatThrownBy(() -> new CollectionItem(null, vaultItemId, MEMBER_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CollectionItem(collectionId, null, MEMBER_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CollectionItem(collectionId, vaultItemId, null))
			.isInstanceOf(IllegalArgumentException.class);

		assertThat(CollectionItem.class.getRecordComponents())
			.extracting(RecordComponent::getName)
			.containsExactly("collectionId", "vaultItemId", "createdAt");
		assertThat(CollectionItem.class.getRecordComponents())
			.extracting(RecordComponent::getType)
			.containsExactly(CollectionId.class, VaultItemId.class, Instant.class);
	}

	@Test
	void collectionRequiresUserIdAndNonBlankName() {
		assertThatThrownBy(() -> new Collection(collectionId(), null, "Wedding 2026", CREATED_AT, UPDATED_AT, List.of()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Collection(collectionId(), userId(), null, CREATED_AT, UPDATED_AT, List.of()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Collection(collectionId(), userId(), "", CREATED_AT, UPDATED_AT, List.of()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Collection(collectionId(), userId(), "   ", CREATED_AT, UPDATED_AT, List.of()))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void itemsAreStoredAsUnmodifiableCopy() {
		List<CollectionItem> mutable = new ArrayList<>();
		Collection collection = new Collection(collectionId(), userId(), "Wedding 2026", CREATED_AT, UPDATED_AT, mutable);
		mutable.add(new CollectionItem(collectionId(), vaultItemId(), MEMBER_AT));

		assertThat(collection.items()).isEmpty();
		assertThatThrownBy(() -> collection.items().add(new CollectionItem(collectionId(), vaultItemId(), MEMBER_AT)))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void collectionHasNoEmbeddedVaultItemAndNoOrgOrTenant() {
		assertThat(Collection.class.getRecordComponents())
			.extracting(RecordComponent::getName)
			.containsExactly("id", "userId", "name", "createdAt", "updatedAt", "items");
		assertThat(Collection.class.getRecordComponents())
			.extracting(RecordComponent::getType)
			.containsExactly(CollectionId.class, UserId.class, String.class, Instant.class, Instant.class, List.class);

		assertThat(componentTypeNames(Collection.class)).doesNotContain("VaultItem", "orgId", "tenantId", "org_id", "tenant_id");
		assertThat(componentTypeNames(CollectionItem.class)).doesNotContain("VaultItem");
		assertThat(Collection.class.getPackageName()).isEqualTo("com.aifashionvault.collection");
		assertThat(CollectionItem.class.getPackageName()).isEqualTo("com.aifashionvault.collection");
	}

	@Test
	void addItemThenRemoveItemLeavesVaultItemIdIntactAndChangesMembershipOnly() {
		VaultItemId vaultItemId = vaultItemId();
		Collection empty = emptyCollection();

		Collection added = empty.addItem(vaultItemId, MEMBER_AT);

		assertThat(added).isNotSameAs(empty);
		assertThat(empty.items()).isEmpty();
		assertThat(added.id()).isEqualTo(empty.id());
		assertThat(added.userId()).isEqualTo(empty.userId());
		assertThat(added.name()).isEqualTo(empty.name());
		assertThat(added.createdAt()).isEqualTo(empty.createdAt());
		assertThat(added.updatedAt()).isEqualTo(empty.updatedAt());
		assertThat(added.items()).containsExactly(new CollectionItem(empty.id(), vaultItemId, MEMBER_AT));

		Collection removed = added.removeItem(vaultItemId);

		assertThat(removed).isNotSameAs(added);
		assertThat(added.items()).containsExactly(new CollectionItem(empty.id(), vaultItemId, MEMBER_AT));
		assertThat(removed.items()).isEmpty();
		assertThat(removed.id()).isEqualTo(empty.id());
		assertThat(removed.userId()).isEqualTo(empty.userId());
		assertThat(removed.name()).isEqualTo(empty.name());
		assertThat(removed.createdAt()).isEqualTo(empty.createdAt());
		assertThat(removed.updatedAt()).isEqualTo(empty.updatedAt());

		assertThat(vaultItemId.value()).isEqualTo(VAULT_ITEM_UUID);
		assertThat(new VaultItemId(VAULT_ITEM_UUID)).isEqualTo(vaultItemId);
		assertThat(removed.addItem(vaultItemId, MEMBER_AT).items())
			.extracting(CollectionItem::vaultItemId)
			.containsExactly(vaultItemId);
	}

	@Test
	void addItemIsIdempotentForTheSameVaultItemId() {
		VaultItemId vaultItemId = vaultItemId();
		Collection once = emptyCollection().addItem(vaultItemId, MEMBER_AT);
		Collection twice = once.addItem(vaultItemId, Instant.parse("2026-01-04T00:00:00Z"));

		assertThat(twice.items()).containsExactly(new CollectionItem(once.id(), vaultItemId, MEMBER_AT));
	}

	@Test
	void discardingACollectionDoesNotInvalidateVaultItemId() {
		VaultItemId vaultItemId = vaultItemId();
		Collection collection = emptyCollection().addItem(vaultItemId, MEMBER_AT);

		collection = null;

		assertThat(collection).isNull();
		assertThat(vaultItemId.value()).isEqualTo(VAULT_ITEM_UUID);
		assertThat(new VaultItemId(vaultItemId.value())).isEqualTo(vaultItemId);
	}

	private static Collection emptyCollection() {
		return new Collection(collectionId(), userId(), "Wedding 2026", CREATED_AT, UPDATED_AT, List.of());
	}

	private static CollectionId collectionId() {
		return new CollectionId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
	}

	private static UserId userId() {
		return new UserId(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
	}

	private static VaultItemId vaultItemId() {
		return new VaultItemId(VAULT_ITEM_UUID);
	}

	private static List<String> componentTypeNames(Class<?> type) {
		List<String> names = new ArrayList<>();
		for (RecordComponent component : type.getRecordComponents()) {
			names.add(component.getName());
			names.add(component.getType().getSimpleName());
		}
		return names;
	}
}
