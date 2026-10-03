package com.aifashionvault.platform.generation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

import com.aifashionvault.generation.FrozenSingleItemIntent;
import com.aifashionvault.generation.GenerationJob;
import com.aifashionvault.generation.GenerationStore;
import com.aifashionvault.generation.vton.ItemCategory;
import com.aifashionvault.generation.vton.PrivateMediaRef;
import com.aifashionvault.generation.vton.VtonProductAsset;
import com.aifashionvault.platform.objectstore.ObjectRef;
import com.aifashionvault.platform.objectstore.ObjectStore;
import com.aifashionvault.platform.objectstore.ObjectStoreExceptions;
import com.aifashionvault.shared.StorageDomain;
import com.aifashionvault.platform.objectstore.StorageKeys;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.VaultItemId;

public final class AuthorizedGenerationMediaResolver {

	private static final String DEFAULT_CATEGORY = "clothing";

	private static final String AUTHORIZED_AVATAR_SQL =
		"SELECT i.storage_key FROM avatar_image i "
			+ "JOIN avatar_profile p ON p.id = i.profile_id "
			+ "WHERE i.id = ? AND p.user_id = ? AND p.status = 'ACTIVE' AND i.lifecycle_state = 'ACTIVE'";

	private static final String AUTHORIZED_PRODUCT_SQL =
		"SELECT storage_key, category FROM vault_item WHERE id = ? AND user_id = ?";

	private final GenerationStore generationStore;
	private final ObjectStore objectStore;
	private final JdbcTemplate jdbc;

	public AuthorizedGenerationMediaResolver(
		DataSource dataSource,
		ObjectStore objectStore,
		GenerationStore generationStore
	) {
		if (dataSource == null) {
			throw new IllegalArgumentException("dataSource is required");
		}
		if (objectStore == null) {
			throw new IllegalArgumentException("objectStore is required");
		}
		if (generationStore == null) {
			throw new IllegalArgumentException("generationStore is required");
		}
		this.jdbc = new JdbcTemplate(dataSource);
		this.objectStore = objectStore;
		this.generationStore = generationStore;
	}

	public ResolvedMedia resolve(GenerationJob job) {
		if (job == null) {
			throw new IllegalArgumentException("job is required");
		}
		if (job.cancelledAt() != null || job.subjectDeletedAt() != null) {
			throw unauthorized();
		}
		if (job.avatarImageId() == null) {
			throw unauthorized();
		}
		List<String> avatarKeys = jdbc.query(
			AUTHORIZED_AVATAR_SQL,
			(rs, rowNum) -> rs.getString("storage_key"),
			job.avatarImageId().value(),
			job.userId().value());
		if (avatarKeys.size() != 1) {
			throw unauthorized();
		}
		PrivateMediaRef avatar = privateRef(avatarKeys.getFirst());
		Optional<FrozenSingleItemIntent> frozen;
		try {
			frozen = generationStore.findFrozenSingleItemIntent(job.id());
		}
		catch (UnsupportedOperationException ignored) {
			// Legacy stores retain the Vault-backed resolver path.
			frozen = Optional.empty();
		}
		if (frozen.isPresent()) {
			FrozenSingleItemIntent intent = frozen.get();
			if (!intent.ownerId().equals(job.userId()) || !intent.avatarImageId().equals(job.avatarImageId())) {
				throw unauthorized();
			}
			StorageKey key = StorageKeys.generationInputKey(
				intent.ownerId(), intent.generationId().value(), intent.productMediaId().value());
			try {
				objectStore.head(new ObjectRef(StorageDomain.TEMP, key.value()));
			}
			catch (ObjectStoreExceptions.StagedObjectMissingException ex) {
				throw new GenerationInputMissingException("generation input is absent", ex);
			}
			catch (ObjectStoreExceptions.ObjectStoreUnavailableException ex) {
				throw new GenerationInputUnavailableException("generation input storage unavailable", ex);
			}
			PrivateMediaRef product = privateRef(key.value());
			return new ResolvedMedia(avatar, List.of(new VtonProductAsset(product, new ItemCategory(DEFAULT_CATEGORY))));
		}
		List<VtonProductAsset> products = new ArrayList<>();
		for (VaultItemId productId : job.productVaultItemIds()) {
			List<VtonProductAsset> rows = jdbc.query(
				AUTHORIZED_PRODUCT_SQL,
				(rs, rowNum) -> new VtonProductAsset(
					privateRef(rs.getString("storage_key")),
					category(rs.getString("category"))),
				productId.value(),
				job.userId().value());
			if (rows.size() != 1) {
				throw unauthorized();
			}
			products.add(rows.getFirst());
		}
		return new ResolvedMedia(avatar, products);
	}

	private static ItemCategory category(String raw) {
		if (raw == null || raw.isBlank()) {
			return new ItemCategory(DEFAULT_CATEGORY);
		}
		return new ItemCategory(raw);
	}

	private static PrivateMediaRef privateRef(String storageKey) {
		return new PrivateMediaRef(new StorageKey(storageKey), OptionalLong.empty());
	}

	private static UnauthorizedGenerationMediaException unauthorized() {
		return new UnauthorizedGenerationMediaException("generation media is not authorized");
	}

	public record ResolvedMedia(PrivateMediaRef avatar, List<VtonProductAsset> products) {
		public ResolvedMedia {
			if (avatar == null) {
				throw new IllegalArgumentException("avatar is required");
			}

			if (products == null) {
				throw new IllegalArgumentException("products is required");
			}
			products = List.copyOf(products);
		}
	}
	public static final class GenerationInputMissingException extends IllegalStateException {
		public GenerationInputMissingException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	public static final class GenerationInputUnavailableException extends IllegalStateException {
		public GenerationInputUnavailableException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	public static final class UnauthorizedGenerationMediaException extends IllegalStateException {

		public UnauthorizedGenerationMediaException(String message) {
			super(message);
		}
	}
}
