package com.aifashionvault.studio;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.aifashionvault.shared.AvatarProfileId;
import com.aifashionvault.shared.StudioItemId;
import com.aifashionvault.shared.StudioSessionId;
import com.aifashionvault.shared.StudioSlot;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.shared.VaultItemId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcStudioStoreTest {

	static {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
	}

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
		.withDatabaseName("postgres")
		.withStartupTimeout(Duration.ofMinutes(5));
	private static final Instant T0 = Instant.parse("2026-10-03T09:00:00Z");
	private static final Instant T1 = Instant.parse("2026-10-03T09:05:00Z");
	private static final Instant T2 = Instant.parse("2026-10-03T09:10:00Z");

	private JdbcTemplate jdbc;
	private JdbcStudioStore store;
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
		jdbc.update("TRUNCATE studio_item, studio_session, avatar_profile, vault_item, users CASCADE");
		owner = user();
		stranger = user();
		store = new JdbcStudioStore(source);
	}

	@Test
	void roundTripsOrderedItemsForTheOwnerOnly() {
		StudioSessionId id = newId();
		StudioSession session = draft(id, owner, T0, T0,
			item(id, StudioSlot.LOWER_BODY, vaultItem(owner), 0),
			item(id, StudioSlot.UPPER_BODY, vaultItem(owner), 0));

		StudioSession created = store.create(session);

		assertThat(created).isEqualTo(session);
		assertThat(created.items()).extracting(StudioItem::slot)
			.containsExactly(StudioSlot.LOWER_BODY, StudioSlot.UPPER_BODY);
		assertThat(store.findForUser(id, owner)).contains(session);
		assertThat(store.findForUser(id, stranger)).isEmpty();
		assertThat(store.findLatestForUser(owner)).contains(session);
		assertThat(store.findLatestForUser(stranger)).isEmpty();
	}

	@Test
	void latestDraftIsTheMostRecentlyUpdatedOwnedSession() {
		StudioSession first = store.create(draft(newId(), owner, T0, T0));
		StudioSession second = store.create(draft(newId(), owner, T0, T1));
		store.create(draft(newId(), stranger, T0, T2));

		assertThat(store.findLatestForUser(owner)).contains(second);
		assertThat(store.findForUser(first.id(), owner)).contains(first);
	}

	@Test
	void staleSaveIsRejectedAndDoesNotOverwriteTheNewerDraft() {
		StudioSession created = store.create(draft(newId(), owner, T0, T0));
		StudioSession newer = draft(created.id(), owner, T0, T1,
			item(created.id(), StudioSlot.LOWER_BODY, vaultItem(owner), 0));

		assertThat(store.save(newer, T0).status()).isEqualTo(JdbcStudioStore.SaveStatus.SAVED);

		StudioSession losingEdit = draft(created.id(), owner, T0, T2,
			item(created.id(), StudioSlot.UPPER_BODY, vaultItem(owner), 0));
		JdbcStudioStore.SaveResult result = store.save(losingEdit, T0);

		assertThat(result.status()).isEqualTo(JdbcStudioStore.SaveStatus.STALE);
		assertThat(result.session()).isNull();
		assertThat(store.findForUser(created.id(), owner)).contains(newer);
	}

	@Test
	void saveOnUnknownOrForeignSessionIsNotFound() {
		StudioSession created = store.create(draft(newId(), owner, T0, T0));

		assertThat(store.save(draft(newId(), owner, T0, T1), T0).status())
			.isEqualTo(JdbcStudioStore.SaveStatus.NOT_FOUND);

		StudioSession foreignAttempt = new StudioSession(created.id(), stranger, null, T0, T1, List.of());
		assertThat(store.save(foreignAttempt, T0).status()).isEqualTo(JdbcStudioStore.SaveStatus.NOT_FOUND);
		assertThat(store.findForUser(created.id(), owner)).contains(created);
	}

	@Test
	void referencesOwnedByAnotherUserAreRefusedBeforeAnyWrite() {
		StudioSessionId borrowingId = newId();
		StudioSession itemBorrowing = draft(borrowingId, owner, T0, T0,
			item(borrowingId, StudioSlot.UPPER_BODY, vaultItem(stranger), 0));

		assertThatThrownBy(() -> store.create(itemBorrowing))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("not owned");
		assertThat(countSessions()).isZero();

		StudioSession avatarBorrowing = new StudioSession(newId(), owner, avatarProfile(stranger), T0, T0, List.of());

		assertThatThrownBy(() -> store.create(avatarBorrowing))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("not owned");
		assertThat(countSessions()).isZero();
	}

	@Test
	void conflictingOrDuplicateDraftIsRefusedAndNothingIsPersisted() {
		StudioSessionId id = newId();
		VaultItemId garment = vaultItem(owner);

		assertThatThrownBy(() -> draft(id, owner, T0, T0,
			item(id, StudioSlot.FULL_BODY, garment, 0),
			item(id, StudioSlot.UPPER_BODY, vaultItem(owner), 0)))
			.isInstanceOf(IllegalArgumentException.class);

		assertThatThrownBy(() -> draft(id, owner, T0, T0,
			item(id, StudioSlot.UPPER_BODY, garment, 0),
			item(id, StudioSlot.LOWER_BODY, garment, 0)))
			.isInstanceOf(IllegalArgumentException.class);

		assertThat(countSessions()).isZero();
	}

	@Test
	void concurrentStaleEditsCannotBothWin() throws Exception {
		StudioSession created = store.create(draft(newId(), owner, T0, T0));
		StudioSession first = draft(created.id(), owner, T0, T1,
			item(created.id(), StudioSlot.UPPER_BODY, vaultItem(owner), 0));
		StudioSession second = draft(created.id(), owner, T0, T2,
			item(created.id(), StudioSlot.LOWER_BODY, vaultItem(owner), 0));

		CyclicBarrier barrier = new CyclicBarrier(2);
		AtomicReference<JdbcStudioStore.SaveResult> a = new AtomicReference<>();
		AtomicReference<JdbcStudioStore.SaveResult> b = new AtomicReference<>();
		Runnable saveFirst = () -> a.set(race(barrier, first));
		Runnable saveSecond = () -> b.set(race(barrier, second));
		Thread one = new Thread(saveFirst);
		Thread two = new Thread(saveSecond);
		one.start();
		two.start();
		one.join();
		two.join();

		List<JdbcStudioStore.SaveStatus> statuses = List.of(a.get().status(), b.get().status());
		assertThat(statuses).containsExactlyInAnyOrder(
			JdbcStudioStore.SaveStatus.SAVED, JdbcStudioStore.SaveStatus.STALE);
		// Exactly one writer won, and the stored draft is that winner's — not a blend, and
		// not the loser's (no lost update).
		StudioSession persisted = store.findForUser(created.id(), owner).orElseThrow();
		StudioSession winner = a.get().saved() ? first : second;
		assertThat(persisted).isEqualTo(winner);
	}

	private JdbcStudioStore.SaveResult race(CyclicBarrier barrier, StudioSession next) {
		try {
			barrier.await(10, TimeUnit.SECONDS);
		}
		catch (Exception ex) {
			throw new IllegalStateException("barrier", ex);
		}
		return store.save(next, T0);
	}

	private long countSessions() {
		return jdbc.queryForObject("SELECT count(*) FROM studio_session", Long.class);
	}

	private StudioSession draft(StudioSessionId id, UserId user, Instant createdAt, Instant updatedAt, StudioItem... items) {
		return new StudioSession(id, user, null, createdAt, updatedAt, List.of(items));
	}

	private StudioItem item(StudioSessionId id, StudioSlot slot, VaultItemId vaultItemId, int position) {
		return new StudioItem(new StudioItemId(UUID.randomUUID()), id, slot, vaultItemId, position, T0);
	}

	private UserId user() {
		UserId id = new UserId(UUID.randomUUID());
		jdbc.update("INSERT INTO users(id,created_at,updated_at) VALUES(?,now(),now())", id.value());
		return id;
	}

	private VaultItemId vaultItem(UserId user) {
		VaultItemId id = new VaultItemId(UUID.randomUUID());
		jdbc.update("INSERT INTO vault_item(id,user_id,storage_key,created_at) VALUES(?,?,?,now())",
			id.value(), user.value(), "users/" + user.value() + "/vault/" + id.value() + ".jpg");
		return id;
	}

	private AvatarProfileId avatarProfile(UserId user) {
		AvatarProfileId id = new AvatarProfileId(UUID.randomUUID());
		jdbc.update("INSERT INTO avatar_profile(id,user_id,status,created_at,updated_at) VALUES(?,?,'ACTIVE',now(),now())",
			id.value(), user.value());
		return id;
	}

	private static StudioSessionId newId() {
		return new StudioSessionId(UUID.randomUUID());
	}
}
