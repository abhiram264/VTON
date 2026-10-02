package com.aifashionvault.platform.studio;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.aifashionvault.look.JdbcLookStore;
import com.aifashionvault.shared.AvatarProfileId;
import com.aifashionvault.shared.LookId;
import com.aifashionvault.shared.StudioSlot;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.shared.VaultItemId;
import com.aifashionvault.studio.JdbcStudioStore;
import com.aifashionvault.studio.StudioItem;
import com.aifashionvault.studio.StudioSession;

import static org.assertj.core.api.Assertions.assertThat;

class StudioDraftClonerTest {

	static {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
	}

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
		.withDatabaseName("postgres")
		.withStartupTimeout(Duration.ofMinutes(5));
	private static final Instant T0 = Instant.parse("2026-10-03T11:00:00Z");

	private JdbcTemplate jdbc;
	private StudioDraftCloner cloner;
	private JdbcStudioStore studioStore;
	private UserId owner;
	private UserId stranger;

	@BeforeAll
	static void database() {
		POSTGRES.start();
	}

	@BeforeEach
	void reset() {
		var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		Flyway.configure().dataSource(source).load().migrate();
		jdbc = new JdbcTemplate(source);
		jdbc.update("TRUNCATE studio_item, studio_session, look_item, look, avatar_profile, vault_item, users CASCADE");
		owner = user();
		stranger = user();
		cloner = new StudioDraftCloner(new JdbcLookStore(source), source);
		studioStore = new JdbcStudioStore(source);
	}

	@Test
	void cloneOfAForeignLookDisclosesNothing() {
		LookId look = look(stranger, null, vaultItem(stranger), StudioSlot.UPPER_BODY);

		StudioDraftCloner.CloneResult result = cloner.cloneFromLook(look, owner, T0);

		assertThat(result.notFound()).isTrue();
		assertThat(result.draft()).isNull();
		assertThat(result.unresolvedGarments()).isEmpty();
		assertThat(result.avatarUnavailable()).isFalse();
	}

	@Test
	void cloneCarriesTheExactSourceGarmentsAndAvatarAndPersistsAsADraft() {
		AvatarProfileId avatar = avatarProfile(owner, "ACTIVE");
		VaultItemId top = vaultItem(owner);
		VaultItemId bottom = vaultItem(owner);
		LookId lookId = look(owner, avatar, top, StudioSlot.UPPER_BODY);
		addLookItem(lookId, bottom, StudioSlot.LOWER_BODY);

		StudioDraftCloner.CloneResult result = cloner.cloneFromLook(lookId, owner, T0);

		assertThat(result.notFound()).isFalse();
		assertThat(result.unresolvedGarments()).isEmpty();
		assertThat(result.avatarUnavailable()).isFalse();
		StudioSession draft = result.draft();
		assertThat(draft.userId()).isEqualTo(owner);
		assertThat(draft.avatarProfileId()).isEqualTo(avatar);
		assertThat(draft.items()).extracting(StudioItem::vaultItemId).containsExactlyInAnyOrder(top, bottom);
		assertThat(draft.items()).extracting(StudioItem::slot)
			.containsExactlyInAnyOrder(StudioSlot.UPPER_BODY, StudioSlot.LOWER_BODY);

		StudioSession persisted = studioStore.create(draft);
		assertThat(persisted.id()).isEqualTo(draft.id());
		assertThat(persisted.userId()).isEqualTo(draft.userId());
		assertThat(persisted.avatarProfileId()).isEqualTo(draft.avatarProfileId());
		assertThat(persisted.createdAt()).isEqualTo(draft.createdAt());
		assertThat(persisted.updatedAt()).isEqualTo(draft.updatedAt());
		assertThat(persisted.items()).containsExactlyInAnyOrderElementsOf(draft.items());
		// Reads are canonically ordered by slot, not by the caller's insertion order.
		assertThat(persisted.items()).extracting(StudioItem::slot)
			.containsExactly(StudioSlot.LOWER_BODY, StudioSlot.UPPER_BODY);
		assertThat(studioStore.findForUser(draft.id(), owner)).contains(persisted);
		assertThat(studioStore.findLatestForUser(owner)).contains(persisted);
	}

	@Test
	void clonedPositionsArePerSlotNotGlobalListIndices() {
		VaultItemId top = vaultItem(owner);
		VaultItemId ring = vaultItem(owner);
		VaultItemId necklace = vaultItem(owner);
		LookId lookId = look(owner, null, top, StudioSlot.UPPER_BODY);
		addLookItem(lookId, ring, StudioSlot.JEWELLERY);
		addLookItem(lookId, necklace, StudioSlot.JEWELLERY);

		StudioSession draft = cloner.cloneFromLook(lookId, owner, T0).draft();

		assertThat(draft.items()).filteredOn(i -> i.slot() == StudioSlot.JEWELLERY)
			.extracting(StudioItem::position).containsExactly(0, 1);
		assertThat(draft.items()).filteredOn(i -> i.slot() == StudioSlot.UPPER_BODY)
			.extracting(StudioItem::position).containsExactly(0);
		assertThat(studioStore.create(draft).items()).containsExactlyInAnyOrderElementsOf(draft.items());
	}

	@Test
	void anotherUsersGarmentIsReportedNotSilentlyDropped() {
		VaultItemId mine = vaultItem(owner);
		VaultItemId theirs = vaultItem(stranger);
		LookId lookId = look(owner, null, mine, StudioSlot.UPPER_BODY);
		addLookItem(lookId, theirs, StudioSlot.LOWER_BODY);

		StudioDraftCloner.CloneResult result = cloner.cloneFromLook(lookId, owner, T0);

		assertThat(result.unresolvedGarments()).containsExactly(
			new StudioDraftCloner.UnresolvedGarment(StudioSlot.LOWER_BODY, theirs,
				StudioDraftCloner.UnresolvedReason.GARMENT_NOT_OWNED));
		assertThat(result.draft().items()).extracting(StudioItem::vaultItemId).containsExactly(mine);
		assertThat(studioStore.create(result.draft()).items()).extracting(StudioItem::vaultItemId).containsExactly(mine);
	}

	@Test
	void aRetiredAvatarIsNotCarriedIntoTheDraft() {
		AvatarProfileId retired = avatarProfile(owner, "DELETING");
		VaultItemId top = vaultItem(owner);
		LookId lookId = look(owner, retired, top, StudioSlot.UPPER_BODY);

		StudioDraftCloner.CloneResult result = cloner.cloneFromLook(lookId, owner, T0);

		assertThat(result.avatarUnavailable()).isTrue();
		assertThat(result.draft().avatarProfileId()).isNull();
		assertThat(result.draft().items()).extracting(StudioItem::vaultItemId).containsExactly(top);
	}

	@Test
	void theClonedDraftIsEditableThroughTheStore() {
		VaultItemId top = vaultItem(owner);
		LookId lookId = look(owner, null, top, StudioSlot.UPPER_BODY);
		StudioSession draft = studioStore.create(cloner.cloneFromLook(lookId, owner, T0).draft());

		VaultItemId replacement = vaultItem(owner);
		StudioSession edited = new StudioSession(draft.id(), owner, null, draft.createdAt(), T1,
			List.of(new StudioItem(draft.items().get(0).id(), draft.id(), StudioSlot.UPPER_BODY, replacement, 0, T0)));

		assertThat(studioStore.save(edited, draft.updatedAt()).saved()).isTrue();
		assertThat(studioStore.findForUser(draft.id(), owner).orElseThrow().items())
			.extracting(StudioItem::vaultItemId).containsExactly(replacement);
	}

	private static final Instant T1 = T0.plusSeconds(300);

	private LookId look(UserId user, AvatarProfileId avatar, VaultItemId firstGarment, StudioSlot slot) {
		LookId id = new LookId(UUID.randomUUID());
		jdbc.update("INSERT INTO look(id,user_id,avatar_profile_id,storage_key,quality_status,created_at) VALUES(?,?,?,?,?,?)",
			id.value(), user.value(), avatar == null ? null : avatar.value(),
			"users/" + user.value() + "/looks/" + id.value() + ".png", "PASS", timestamp(T0));
		addLookItem(id, firstGarment, slot);
		return id;
	}

	private void addLookItem(LookId lookId, VaultItemId vaultItemId, StudioSlot slot) {
		jdbc.update("INSERT INTO look_item(look_id,vault_item_id,slot) VALUES(?,?,?)",
			lookId.value(), vaultItemId.value(), slot.name());
	}

	private UserId user() {
		UserId id = new UserId(UUID.randomUUID());
		jdbc.update("INSERT INTO users(id,created_at,updated_at) VALUES(?,now(),now())", id.value());
		return id;
	}

	private VaultItemId vaultItem(UserId user) {
		VaultItemId id = new VaultItemId(UUID.randomUUID());
		jdbc.update("INSERT INTO vault_item(id,user_id,storage_key,created_at) VALUES(?,?,?,?)",
			id.value(), user.value(), "users/" + user.value() + "/vault/" + id.value() + ".jpg", timestamp(T0));
		return id;
	}

	private AvatarProfileId avatarProfile(UserId user, String status) {
		AvatarProfileId id = new AvatarProfileId(UUID.randomUUID());
		jdbc.update("INSERT INTO avatar_profile(id,user_id,status,created_at,updated_at) VALUES(?,?,?,?,?)",
			id.value(), user.value(), status, timestamp(T0), timestamp(T0));
		return id;
	}

	private static java.sql.Timestamp timestamp(Instant value) {
		return java.sql.Timestamp.from(value.truncatedTo(ChronoUnit.MICROS));
	}
}
