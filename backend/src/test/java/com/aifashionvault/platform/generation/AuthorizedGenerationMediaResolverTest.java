package com.aifashionvault.platform.generation;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.TimeZone;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.aifashionvault.generation.GenerationJob;
import com.aifashionvault.generation.GenerationStore;
import com.aifashionvault.generation.vton.ItemCategory;
import com.aifashionvault.generation.vton.PrivateMediaRef;
import com.aifashionvault.generation.vton.VtonProductAsset;
import com.aifashionvault.platform.generation.AuthorizedGenerationMediaResolver.ResolvedMedia;
import com.aifashionvault.platform.generation.AuthorizedGenerationMediaResolver.UnauthorizedGenerationMediaException;
import com.aifashionvault.platform.objectstore.ObjectStore;
import com.aifashionvault.shared.AvatarImageId;
import com.aifashionvault.shared.AvatarProfileId;
import com.aifashionvault.shared.ClientRequestId;
import com.aifashionvault.shared.GenerationJobId;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.shared.VaultItemId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest(properties = "spring.autoconfigure.exclude=")
class AuthorizedGenerationMediaResolverTest {

	private static final String POSTGRES_IMAGE = "postgres:18";
	private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);
	private static final Instant NOW = Instant.parse("2026-04-02T12:00:00Z");

	static {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
	}

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("postgres")
		.withStartupTimeout(STARTUP_TIMEOUT);

	@Autowired
	private DataSource dataSource;

	@Autowired
	private JdbcTemplate jdbc;

	private ObjectStore objectStore;
	private AuthorizedGenerationMediaResolver resolver;

	@DynamicPropertySource
	static void registerDatasource(DynamicPropertyRegistry registry) {
		POSTGRES.start();
		registry.add("vault.db.url", POSTGRES::getJdbcUrl);
		registry.add("vault.db.username", POSTGRES::getUsername);
		registry.add("vault.db.password", POSTGRES::getPassword);
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@BeforeEach
	void setUp() {
		jdbc.update("TRUNCATE TABLE vault_item, avatar_image, avatar_profile, users CASCADE");
		objectStore = mock(ObjectStore.class);
		resolver = new AuthorizedGenerationMediaResolver(dataSource, objectStore, mock(GenerationStore.class));
	}

	@Test
	void resolveReturnsAuthorizedStorageKeysWithoutCallingObjectStore() {
		UserId userId = insertUser();
		AvatarImageId avatarImageId = insertAvatar(userId, "ACTIVE");
		VaultItemId tops = insertVaultItem(userId, "Tops");
		VaultItemId uncategorized = insertVaultItem(userId, null);
		GenerationJob job = job(userId, avatarImageId, List.of(tops, uncategorized), null, null);

		ResolvedMedia resolved = resolver.resolve(job);

		String avatarKey = "users/" + userId.value() + "/avatars/" + avatarImageId.value() + ".jpg";
		String topsKey = "users/" + userId.value() + "/vault/" + tops.value() + ".jpg";
		String garmentKey = "users/" + userId.value() + "/vault/" + uncategorized.value() + ".jpg";
		assertThat(resolved.avatar()).isEqualTo(new PrivateMediaRef(new StorageKey(avatarKey), OptionalLong.empty()));
		assertThat(resolved.products()).containsExactly(
			new VtonProductAsset(new PrivateMediaRef(new StorageKey(topsKey), OptionalLong.empty()), new ItemCategory("tops")),
			new VtonProductAsset(new PrivateMediaRef(new StorageKey(garmentKey), OptionalLong.empty()), new ItemCategory("garment")));
		assertThat(resolved.avatar().storageKey().value())
			.doesNotContain("http://", "https://", "data:", "x-amz");
		assertThat(resolved.products())
			.extracting(product -> product.media().storageKey().value())
			.allSatisfy(key -> assertThat(key).doesNotContain("http://", "https://", "data:", "x-amz"));
		verifyNoInteractions(objectStore);
	}

	@Test
	void missingAvatarFailsBeforeObjectStoreAccess() {
		UserId userId = insertUser();
		VaultItemId product = insertVaultItem(userId, "garment");
		GenerationJob job = job(userId, new AvatarImageId(UUID.randomUUID()), List.of(product), null, null);

		assertUnauthorized(job);
	}

	@Test
	void missingProductFailsBeforeObjectStoreAccess() {
		UserId userId = insertUser();
		AvatarImageId avatarImageId = insertAvatar(userId, "ACTIVE");
		GenerationJob job = job(userId, avatarImageId, List.of(new VaultItemId(UUID.randomUUID())), null, null);

		assertUnauthorized(job);
	}

	@Test
	void crossTenantAvatarFailsBeforeObjectStoreAccess() {
		UserId owner = insertUser();
		UserId stranger = insertUser();
		AvatarImageId strangerAvatar = insertAvatar(stranger, "ACTIVE");
		VaultItemId product = insertVaultItem(owner, "garment");
		GenerationJob job = job(owner, strangerAvatar, List.of(product), null, null);

		assertUnauthorized(job);
	}

	@Test
	void crossTenantProductFailsBeforeObjectStoreAccess() {
		UserId owner = insertUser();
		UserId stranger = insertUser();
		AvatarImageId avatarImageId = insertAvatar(owner, "ACTIVE");
		VaultItemId strangerProduct = insertVaultItem(stranger, "garment");
		GenerationJob job = job(owner, avatarImageId, List.of(strangerProduct), null, null);

		assertUnauthorized(job);
	}

	@Test
	void deletingAvatarProfileFailsBeforeObjectStoreAccess() {
		UserId userId = insertUser();
		AvatarImageId avatarImageId = insertAvatar(userId, "DELETING");
		VaultItemId product = insertVaultItem(userId, "garment");
		GenerationJob job = job(userId, avatarImageId, List.of(product), null, null);

		assertUnauthorized(job);
	}

	@Test
	void subjectDeletedJobFailsBeforeObjectStoreAccess() {
		UserId userId = insertUser();
		AvatarImageId avatarImageId = insertAvatar(userId, "ACTIVE");
		VaultItemId product = insertVaultItem(userId, "garment");
		GenerationJob job = job(userId, avatarImageId, List.of(product), null, NOW.plusSeconds(1));

		assertUnauthorized(job);
	}

	@Test
	void cancelledJobFailsBeforeObjectStoreAccess() {
		UserId userId = insertUser();
		AvatarImageId avatarImageId = insertAvatar(userId, "ACTIVE");
		VaultItemId product = insertVaultItem(userId, "garment");
		GenerationJob job = job(userId, avatarImageId, List.of(product), NOW.plusSeconds(1), null);

		assertUnauthorized(job);
	}

	private void assertUnauthorized(GenerationJob job) {
		assertThatThrownBy(() -> resolver.resolve(job))
			.isInstanceOf(UnauthorizedGenerationMediaException.class)
			.isInstanceOf(IllegalStateException.class);
		verifyNoInteractions(objectStore);
	}

	private UserId insertUser() {
		UserId userId = new UserId(UUID.randomUUID());
		jdbc.update(
			"INSERT INTO users (id, created_at, updated_at, deleted_at) VALUES (?, ?, ?, NULL)",
			userId.value(),
			Timestamp.from(NOW),
			Timestamp.from(NOW));
		return userId;
	}

	private AvatarImageId insertAvatar(UserId userId, String profileStatus) {
		AvatarProfileId profileId = new AvatarProfileId(UUID.randomUUID());
		AvatarImageId imageId = new AvatarImageId(UUID.randomUUID());
		jdbc.update(
			"INSERT INTO avatar_profile (id, user_id, primary_image_id, status, revision, created_at, updated_at) "
				+ "VALUES (?, ?, NULL, ?, 1, ?, ?)",
			profileId.value(),
			userId.value(),
			profileStatus,
			Timestamp.from(NOW),
			Timestamp.from(NOW));
		jdbc.update(
			"INSERT INTO avatar_image (id, profile_id, storage_key, quality_status, created_at) VALUES (?, ?, ?, 'ACCEPTED', ?)",
			imageId.value(),
			profileId.value(),
			"users/" + userId.value() + "/avatars/" + imageId.value() + ".jpg",
			Timestamp.from(NOW));
		jdbc.update(
			"UPDATE avatar_profile SET primary_image_id = ? WHERE id = ?",
			imageId.value(),
			profileId.value());
		return imageId;
	}

	private VaultItemId insertVaultItem(UserId userId, String category) {
		VaultItemId vaultItemId = new VaultItemId(UUID.randomUUID());
		jdbc.update(
			"INSERT INTO vault_item (id, user_id, storage_key, category, created_at) VALUES (?, ?, ?, ?, ?)",
			vaultItemId.value(),
			userId.value(),
			"users/" + userId.value() + "/vault/" + vaultItemId.value() + ".jpg",
			category,
			Timestamp.from(NOW));
		return vaultItemId;
	}

	private static GenerationJob job(
		UserId userId,
		AvatarImageId avatarImageId,
		List<VaultItemId> products,
		Instant cancelledAt,
		Instant subjectDeletedAt) {
		boolean terminal = cancelledAt != null || subjectDeletedAt != null;
		Instant completedAt = cancelledAt != null ? cancelledAt : subjectDeletedAt;
		return new GenerationJob(
			new GenerationJobId(UUID.randomUUID()),
			userId,
			null,
			terminal ? GenerationJob.FAILED : GenerationJob.QUEUED,
			"try-on",
			new ClientRequestId("req-" + UUID.randomUUID()),
			NOW,
			completedAt,
			avatarImageId,
			products,
			cancelledAt,
			subjectDeletedAt);
	}
}
