package com.aifashionvault.avatar;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.aifashionvault.platform.objectstore.ClaimCommand;
import com.aifashionvault.platform.objectstore.ClaimResult;
import com.aifashionvault.platform.objectstore.ImageInspector;
import com.aifashionvault.platform.objectstore.ImageInspectorAvatarBytesInspector;
import com.aifashionvault.platform.objectstore.ObjectStoreAvatarMediaAccess;
import com.aifashionvault.platform.objectstore.MediaDeclaration;
import com.aifashionvault.platform.objectstore.ObjectMetadata;
import com.aifashionvault.platform.objectstore.ObjectRef;
import com.aifashionvault.platform.objectstore.ObjectStore;
import com.aifashionvault.platform.objectstore.ObjectStoreExceptions;
import com.aifashionvault.platform.objectstore.PresignedAccess;
import com.aifashionvault.shared.StorageDomain;
import com.aifashionvault.platform.objectstore.StorageKeys;
import com.aifashionvault.platform.objectstore.UploadCapability;
import com.aifashionvault.platform.objectstore.UploadPurpose;
import com.aifashionvault.shared.AvatarImageId;
import com.aifashionvault.shared.AvatarProfileId;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AvatarWorkflowTest {

	private static final String POSTGRES_IMAGE = "postgres:18";
	private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);
	private static final Instant NOW = Instant.parse("2026-04-02T12:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	static {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
	}

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("postgres")
		.withStartupTimeout(STARTUP_TIMEOUT);

	private static DriverManagerDataSource dataSource;
	private static JdbcTemplate jdbc;

	private JdbcAvatarStore store;
	private FakeObjectStore objectStore;
	private AvatarWorkflow workflow;
	private RecordingSemanticInspector semanticInspector;

	@BeforeAll
	static void startDatabaseAndMigrate() {
		POSTGRES.start();
		Flyway.configure()
			.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
			.locations("classpath:db/migration")
			.load()
			.migrate();
		dataSource = new DriverManagerDataSource(
			POSTGRES.getJdbcUrl(),
			POSTGRES.getUsername(),
			POSTGRES.getPassword());
		jdbc = new JdbcTemplate(dataSource);
	}

	@BeforeEach
	void setUp() {
		jdbc.update("TRUNCATE TABLE avatar_image, avatar_profile, users CASCADE");
		store = new JdbcAvatarStore(dataSource, CLOCK);
		objectStore = new FakeObjectStore();
		semanticInspector = new RecordingSemanticInspector();
		workflow = new AvatarWorkflow(
			store,
			new ObjectStoreAvatarMediaAccess(objectStore),
			new AvatarPreflight(
				new ImageInspectorAvatarBytesInspector(new ImageInspector()),
				semanticInspector),
			CLOCK);
	}

	@Test
	void createFromAvatarUploadPersistsActiveProfileAndClaimsAvatarBoundary() throws Exception {
		UserId userId = insertUser();
		UploadId uploadId = new UploadId(UUID.randomUUID());
		byte[] jpeg = texturedJpeg();
		ObjectRef temp = staging(userId, uploadId, UploadPurpose.AVATAR);
		objectStore.put(temp, jpeg);

		AvatarProfile created = workflow.create(userId, uploadId);

		assertThat(created.status()).isEqualTo("ACTIVE");
		assertThat(created.userId()).isEqualTo(userId);
		assertThat(created.primaryImageId()).isNotNull();
		assertThat(created.revision()).isEqualTo(1);
		assertThat(store.findByUser(userId)).contains(created);
		AvatarImage image = workflow.currentImage(created).orElseThrow();
		assertThat(image.sourceUploadId()).isEqualTo(uploadId);
		assertThat(image.lifecycleState()).isEqualTo(AvatarImageLifecycle.ACTIVE);
		assertThat(image.qualityStatus()).isEqualTo("ACCEPTED");
		assertThat(image.pose()).isNull();
		assertThat(image.width()).isEqualTo(512);
		assertThat(image.height()).isEqualTo(512);
		assertThat(image.preflightPolicyVersion()).isEqualTo("avatar-preflight-v1");
		assertThat(objectStore.claims()).hasSize(1);
		ClaimCommand claim = objectStore.claims().getFirst();
		assertThat(claim.destinationBoundary()).isEqualTo(StorageDomain.AVATAR);
		assertThat(claim.claimMarker()).isEqualTo(image.id().value().toString());
		assertThat(claim.destination()).isEqualTo(image.storageKey());
		assertThat(objectStore.head(new ObjectRef(StorageDomain.AVATAR, image.storageKey().value()))).isNotNull();
		assertThat(store.isUploadConsumed(userId, uploadId)).isTrue();
		assertThat(objectStore.deleted()).contains(temp);
		assertThatThrownBy(() -> objectStore.head(temp))
			.isInstanceOf(ObjectStoreExceptions.StagedObjectMissingException.class);
	}

	@Test
	void createFromGenericUploadIsNotClaimableAndInsertsNothing() throws Exception {
		UserId userId = insertUser();
		UploadId uploadId = new UploadId(UUID.randomUUID());
		objectStore.put(staging(userId, uploadId, UploadPurpose.GENERIC), texturedJpeg());

		assertAvatarException(() -> workflow.create(userId, uploadId), AvatarProblemCode.AVATAR_UPLOAD_NOT_CLAIMABLE);

		assertThat(objectStore.claims()).isEmpty();
		assertThat(store.findByUser(userId)).isEmpty();
		assertThat(store.listImagesForUser(userId)).isEmpty();
		assertThat(count("avatar_profile")).isZero();
		assertThat(count("avatar_image")).isZero();
	}

	@Test
	void failedPreflightDeletesAvatarTempAndInsertsNoImage() {
		UserId userId = insertUser();
		UploadId uploadId = new UploadId(UUID.randomUUID());
		ObjectRef temp = staging(userId, uploadId, UploadPurpose.AVATAR);
		objectStore.put(temp, "not-an-image".getBytes(StandardCharsets.US_ASCII));

		assertAvatarException(() -> workflow.create(userId, uploadId), AvatarProblemCode.AVATAR_UNREADABLE);

		assertThat(objectStore.claims()).isEmpty();
		assertThat(objectStore.deleted()).contains(temp);
		assertThatThrownBy(() -> objectStore.head(temp))
			.isInstanceOf(ObjectStoreExceptions.StagedObjectMissingException.class);
		assertThat(store.findByUser(userId)).isEmpty();
		assertThat(store.listImagesForUser(userId)).isEmpty();
		assertThat(count("avatar_image")).isZero();
	}

	@Test
	void deterministicSemanticRejectionDeletesStagingAndPersistsOrClaimsNothing() throws Exception {
		UserId userId = insertUser();
		UploadId uploadId = new UploadId(UUID.randomUUID());
		ObjectRef temp = staging(userId, uploadId, UploadPurpose.AVATAR);
		objectStore.put(temp, texturedJpeg());
		semanticInspector.inspection =
			new SemanticInspection(PersonCountClass.ZERO, false, false, "detector-private");

		assertAvatarException(
			() -> workflow.create(userId, uploadId),
			AvatarProblemCode.AVATAR_PERSON_NOT_SINGLE);

		assertThat(objectStore.claims()).isEmpty();
		assertThat(objectStore.deleted()).contains(temp);
		assertThatThrownBy(() -> objectStore.head(temp))
			.isInstanceOf(ObjectStoreExceptions.StagedObjectMissingException.class);
		assertThat(store.findByUser(userId)).isEmpty();
		assertThat(store.listImagesForUser(userId)).isEmpty();
		assertThat(count("avatar_profile")).isZero();
		assertThat(count("avatar_image")).isZero();
	}

	@Test
	void unavailableInspectionRetainsStagingAndSameUploadSucceedsAfterRecovery() throws Exception {
		UserId userId = insertUser();
		UploadId uploadId = new UploadId(UUID.randomUUID());
		ObjectRef temp = staging(userId, uploadId, UploadPurpose.AVATAR);
		objectStore.put(temp, texturedJpeg());
		semanticInspector.unavailable = true;

		assertAvatarException(
			() -> workflow.create(userId, uploadId),
			AvatarProblemCode.AVATAR_PREFLIGHT_UNAVAILABLE);

		assertThat(objectStore.claims()).isEmpty();
		assertThat(objectStore.deleted()).doesNotContain(temp);
		assertThat(objectStore.head(temp)).isNotNull();
		assertThat(store.findByUser(userId)).isEmpty();
		assertThat(store.listImagesForUser(userId)).isEmpty();
		assertThat(count("avatar_profile")).isZero();
		assertThat(count("avatar_image")).isZero();

		semanticInspector.unavailable = false;
		AvatarProfile recovered = workflow.create(userId, uploadId);

		assertThat(recovered.status()).isEqualTo("ACTIVE");
		assertThat(objectStore.claims()).hasSize(1);
		assertThat(semanticInspector.calls).isEqualTo(2);
		assertThat(workflow.currentImage(recovered).orElseThrow().preflightPolicyVersion())
			.isEqualTo("avatar-preflight-v1");
	}

	@Test
	void replayOfCurrentPrimaryReturnsSameProfileEvenWithStaleRevision() throws Exception {
		UserId userId = insertUser();
		UploadId uploadId = new UploadId(UUID.randomUUID());
		objectStore.put(staging(userId, uploadId, UploadPurpose.AVATAR), texturedJpeg());
		AvatarProfile created = workflow.create(userId, uploadId);
		AvatarImageId imageId = created.primaryImageId();
		semanticInspector.inspection =
			new SemanticInspection(PersonCountClass.ZERO, false, false, "new-policy");

		AvatarProfile createReplay = workflow.create(userId, uploadId);
		AvatarProfile replaceReplay = workflow.replace(userId, created.id(), created.revision() - 1, uploadId);

		assertThat(createReplay.id()).isEqualTo(created.id());
		assertThat(createReplay.primaryImageId()).isEqualTo(imageId);
		assertThat(replaceReplay.id()).isEqualTo(created.id());
		assertThat(replaceReplay.primaryImageId()).isEqualTo(imageId);
		assertThat(objectStore.claims()).hasSize(1);
		assertThat(store.listImagesForUser(userId)).hasSize(1);
		assertThat(semanticInspector.calls).isEqualTo(1);
		assertThat(store.isUploadConsumed(userId, uploadId)).isTrue();
		assertThat(objectStore.deleted()).contains(staging(userId, uploadId, UploadPurpose.AVATAR));
	}

	@Test
	void successfulReplaceConsumesUploadDeletesStagingAndReplaysWithoutPreflight() throws Exception {
		UserId userId = insertUser();
		UploadId first = new UploadId(UUID.randomUUID());
		UploadId second = new UploadId(UUID.randomUUID());
		ObjectRef secondTemp = staging(userId, second, UploadPurpose.AVATAR);
		objectStore.put(staging(userId, first, UploadPurpose.AVATAR), texturedJpeg());
		objectStore.put(secondTemp, texturedJpeg());
		AvatarProfile created = workflow.create(userId, first);

		AvatarProfile replaced = workflow.replace(userId, created.id(), created.revision(), second);
		semanticInspector.inspection =
			new SemanticInspection(PersonCountClass.ZERO, false, false, "new-policy");
		AvatarProfile replayed = workflow.replace(userId, created.id(), created.revision(), second);

		assertThat(replayed).isEqualTo(replaced);
		assertThat(store.isUploadConsumed(userId, second)).isTrue();
		assertThat(objectStore.deleted()).contains(secondTemp);
		assertThatThrownBy(() -> objectStore.head(secondTemp))
			.isInstanceOf(ObjectStoreExceptions.StagedObjectMissingException.class);
		assertThat(semanticInspector.calls).isEqualTo(2);
		assertThat(objectStore.claims()).hasSize(2);
	}

	@Test
	void distinctSecondCreateConflictsBeforeInspectionOrClaim() throws Exception {
		UserId userId = insertUser();
		UploadId first = new UploadId(UUID.randomUUID());
		UploadId second = new UploadId(UUID.randomUUID());
		ObjectRef secondTemp = staging(userId, second, UploadPurpose.AVATAR);
		objectStore.put(staging(userId, first, UploadPurpose.AVATAR), texturedJpeg());
		objectStore.put(secondTemp, texturedJpeg());
		AvatarProfile created = workflow.create(userId, first);

		assertAvatarException(() -> workflow.create(userId, second), AvatarProblemCode.AVATAR_CONFLICT);

		assertThat(store.findByUser(userId).orElseThrow().id()).isEqualTo(created.id());
		assertThat(store.findByUser(userId).orElseThrow().primaryImageId()).isEqualTo(created.primaryImageId());
		assertThat(count("avatar_profile")).isEqualTo(1);
		assertThat(semanticInspector.calls).isEqualTo(1);
		assertThat(objectStore.claims()).hasSize(1);
		assertThat(objectStore.head(secondTemp)).isNotNull();
	}

	@Test
	void staleReplaceOfNewUploadIsRejected() throws Exception {
		UserId userId = insertUser();
		UploadId first = new UploadId(UUID.randomUUID());
		UploadId second = new UploadId(UUID.randomUUID());
		objectStore.put(staging(userId, first, UploadPurpose.AVATAR), texturedJpeg());
		objectStore.put(staging(userId, second, UploadPurpose.AVATAR), texturedJpeg());
		AvatarProfile created = workflow.create(userId, first);

		assertAvatarException(
			() -> workflow.replace(userId, created.id(), created.revision() - 1, second),
			AvatarProblemCode.AVATAR_STALE_REVISION);

		assertThat(store.findByUser(userId).orElseThrow().primaryImageId()).isEqualTo(created.primaryImageId());
		assertThat(store.findImage(userId, created.primaryImageId()).orElseThrow().lifecycleState())
			.isEqualTo(AvatarImageLifecycle.ACTIVE);
		assertThat(semanticInspector.calls).isEqualTo(1);
		assertThat(objectStore.claims()).hasSize(1);
		assertThat(objectStore.head(staging(userId, second, UploadPurpose.AVATAR))).isNotNull();
	}

	@Test
	void concurrentCreateRacePreservesClaimedPersistentObjectForReconciliation() throws Exception {
		UserId userId = insertUser();
		UploadId uploadId = new UploadId(UUID.randomUUID());
		objectStore.put(staging(userId, uploadId, UploadPurpose.AVATAR), texturedJpeg());
		FailingMutationStore racingStore = new FailingMutationStore(dataSource, CLOCK);
		racingStore.insertFailure = new AvatarException(AvatarProblemCode.AVATAR_CONFLICT);
		AvatarWorkflow racingWorkflow = workflow(racingStore);

		assertAvatarException(
			() -> racingWorkflow.create(userId, uploadId),
			AvatarProblemCode.AVATAR_CONFLICT);

		// The mutation outcome is AMBIGUOUS, so the claimed AVATAR object must be
		// preserved, not deleted: the commit may still have landed and a delete here
		// would destroy a live image. AvatarMediaCleanup.reconcilePersistentOrphans
		// reaps it once it is provably unreferenced past the orphan age.
		ObjectRef claimed = claimedPersistentObject();
		assertThat(objectStore.deleted()).doesNotContain(claimed);
		assertThat(objectStore.head(claimed)).isNotNull();
		assertThat(store.findByUser(userId)).isEmpty();
		assertThat(store.findByStorageKey(new StorageKey(claimed.objectKey()))).isEmpty();
		assertThat(store.isUploadConsumed(userId, uploadId)).isFalse();
	}

	@Test
	void replaceCommitRacePreservesClaimedPersistentObjectAndPreservesStaleProblem() throws Exception {
		UserId userId = insertUser();
		UploadId first = new UploadId(UUID.randomUUID());
		UploadId second = new UploadId(UUID.randomUUID());
		objectStore.put(staging(userId, first, UploadPurpose.AVATAR), texturedJpeg());
		AvatarProfile created = workflow.create(userId, first);
		objectStore.put(staging(userId, second, UploadPurpose.AVATAR), texturedJpeg());
		FailingMutationStore racingStore = new FailingMutationStore(dataSource, CLOCK);
		racingStore.replaceFailure = new AvatarException(AvatarProblemCode.AVATAR_STALE_REVISION);
		AvatarWorkflow racingWorkflow = workflow(racingStore);

		assertAvatarException(
			() -> racingWorkflow.replace(userId, created.id(), created.revision(), second),
			AvatarProblemCode.AVATAR_STALE_REVISION);

		// Same rule as create: the stale-revision commit failed, so the newly claimed
		// object is an unreferenced orphan left for reconciliation, not deleted now.
		ObjectRef claimed = claimedPersistentObject();
		assertThat(objectStore.deleted()).doesNotContain(claimed);
		assertThat(objectStore.head(claimed)).isNotNull();
		assertThat(store.findByUser(userId).orElseThrow().primaryImageId()).isEqualTo(created.primaryImageId());
		assertThat(store.isUploadConsumed(userId, second)).isFalse();
	}

	@Test
	void compensationFailureLeavesOrphanForReconciliationAndDoesNotMaskOriginalProblem() throws Exception {
		UserId userId = insertUser();
		UploadId uploadId = new UploadId(UUID.randomUUID());
		objectStore.put(staging(userId, uploadId, UploadPurpose.AVATAR), texturedJpeg());
		objectStore.failAvatarDeletes = true;
		FailingMutationStore racingStore = new FailingMutationStore(dataSource, CLOCK);
		racingStore.insertFailure = new AvatarException(AvatarProblemCode.AVATAR_CONFLICT);
		AvatarWorkflow racingWorkflow = workflow(racingStore);

		assertAvatarException(
			() -> racingWorkflow.create(userId, uploadId),
			AvatarProblemCode.AVATAR_CONFLICT);

		// Persistent deletes are known to fail here. The workflow attempts none, so
		// the orphan survives for reconciliation and the caller still sees the
		// original AVATAR_CONFLICT rather than a masked compensation error.
		ObjectRef orphan = claimedPersistentObject();
		assertThat(objectStore.deleted()).doesNotContain(orphan);
		assertThat(objectStore.head(orphan)).isNotNull();
		assertThat(store.findByStorageKey(new StorageKey(orphan.objectKey()))).isEmpty();
		assertThat(store.isUploadConsumed(userId, uploadId)).isFalse();
	}

	@Test
	void missingReplaceRejectsBeforeSemanticInspectionAndRetainsStaging() throws Exception {
		UserId userId = insertUser();
		UploadId uploadId = new UploadId(UUID.randomUUID());
		ObjectRef temp = staging(userId, uploadId, UploadPurpose.AVATAR);
		objectStore.put(temp, texturedJpeg());

		assertAvatarException(
			() -> workflow.replace(
				userId,
				new AvatarProfileId(UUID.randomUUID()),
				1,
				uploadId),
			AvatarProblemCode.AVATAR_NOT_FOUND);

		assertThat(semanticInspector.calls).isZero();
		assertThat(objectStore.claims()).isEmpty();
		assertThat(objectStore.head(temp)).isNotNull();
		assertThat(count("avatar_profile")).isZero();
		assertThat(count("avatar_image")).isZero();
	}

	@Test
	void deleteThenReplaceDoesNotResurrect() throws Exception {
		UserId userId = insertUser();
		UploadId first = new UploadId(UUID.randomUUID());
		UploadId second = new UploadId(UUID.randomUUID());
		objectStore.put(staging(userId, first, UploadPurpose.AVATAR), texturedJpeg());
		objectStore.put(staging(userId, second, UploadPurpose.AVATAR), texturedJpeg());
		AvatarProfile created = workflow.create(userId, first);
		workflow.delete(userId, created.id(), created.revision());

		assertAvatarException(
			() -> workflow.replace(userId, created.id(), created.revision() + 1, second),
			AvatarProblemCode.AVATAR_NOT_FOUND);
		assertThat(workflow.get(userId, created.id())).isEmpty();
		assertThat(workflow.list(userId)).isEmpty();
		assertThat(store.findByIdAndUser(created.id(), userId).orElseThrow().status()).isEqualTo("DELETING");
		assertThat(count("avatar_profile")).isEqualTo(1);
		assertThat(semanticInspector.calls).isEqualTo(1);
		assertThat(objectStore.head(staging(userId, second, UploadPurpose.AVATAR))).isNotNull();
	}

	@Test
	void listIsEmptyWhenUserHasNoUsableProfile() {
		UserId userId = insertUser();

		assertThat(workflow.list(userId)).isEmpty();
		assertThat(workflow.get(userId, new AvatarProfileId(UUID.randomUUID()))).isEmpty();
		assertThat(count("avatar_profile")).isZero();
		assertThat(count("avatar_image")).isZero();
	}

	@Test
	void getOfActiveProfileWithMissingObjectThrowsAndLeavesRow() throws Exception {
		UserId userId = insertUser();
		UploadId uploadId = new UploadId(UUID.randomUUID());
		objectStore.put(staging(userId, uploadId, UploadPurpose.AVATAR), texturedJpeg());
		AvatarProfile created = workflow.create(userId, uploadId);
		AvatarImage image = store.findImage(userId, created.primaryImageId()).orElseThrow();
		objectStore.remove(new ObjectRef(StorageDomain.AVATAR, image.storageKey().value()));

		assertAvatarException(() -> workflow.get(userId, created.id()), AvatarProblemCode.AVATAR_MEDIA_MISSING);
		assertAvatarException(() -> workflow.list(userId), AvatarProblemCode.AVATAR_MEDIA_MISSING);

		assertThat(store.findByUser(userId)).contains(created);
		assertThat(store.findImage(userId, created.primaryImageId())).isPresent();
		assertThat(store.findImage(userId, created.primaryImageId()).orElseThrow().lifecycleState())
			.isEqualTo(AvatarImageLifecycle.ACTIVE);
	}

	@Test
	void createWhileDeletingConflictsAndDoesNotReactivate() throws Exception {
		UserId userId = insertUser();
		UploadId first = new UploadId(UUID.randomUUID());
		UploadId second = new UploadId(UUID.randomUUID());
		objectStore.put(staging(userId, first, UploadPurpose.AVATAR), texturedJpeg());
		objectStore.put(staging(userId, second, UploadPurpose.AVATAR), texturedJpeg());
		AvatarProfile created = workflow.create(userId, first);
		AvatarImageId retired = created.primaryImageId();
		workflow.delete(userId, created.id(), created.revision());

		assertAvatarException(() -> workflow.create(userId, second), AvatarProblemCode.AVATAR_CONFLICT);

		assertThat(store.findByUser(userId)).isEmpty();
		assertThat(workflow.get(userId, created.id())).isEmpty();
		assertThat(workflow.list(userId)).isEmpty();
		assertThat(store.findByIdAndUser(created.id(), userId).orElseThrow().status()).isEqualTo("DELETING");
		assertThat(store.findImage(userId, retired).orElseThrow().lifecycleState())
			.isEqualTo(AvatarImageLifecycle.DELETION_PENDING);
		assertThat(count("avatar_profile")).isEqualTo(1);
	}

	@Test
	void createAfterMarkReconciledInsertsNewProfileId() throws Exception {
		UserId userId = insertUser();
		UploadId first = new UploadId(UUID.randomUUID());
		UploadId second = new UploadId(UUID.randomUUID());
		objectStore.put(staging(userId, first, UploadPurpose.AVATAR), texturedJpeg());
		objectStore.put(staging(userId, second, UploadPurpose.AVATAR), texturedJpeg());
		AvatarProfile created = workflow.create(userId, first);
		workflow.delete(userId, created.id(), created.revision());
		store.markReconciled(created.primaryImageId());

		AvatarProfile recreated = workflow.create(userId, second);

		assertThat(store.findByIdAndUser(created.id(), userId)).isEmpty();
		assertThat(recreated.id()).isNotEqualTo(created.id());
		assertThat(recreated.status()).isEqualTo("ACTIVE");
		assertThat(recreated.primaryImageId()).isNotEqualTo(created.primaryImageId());
		assertThat(workflow.currentImage(recreated).orElseThrow().qualityStatus()).isEqualTo("ACCEPTED");
		assertThat(count("avatar_profile")).isEqualTo(1);
	}

	@Test
	void consumedUploadCannotResurrectAfterAcceptedImageAndProfileAreReconciled() throws Exception {
		UserId userId = insertUser();
		UploadId uploadId = new UploadId(UUID.randomUUID());
		ObjectRef temp = staging(userId, uploadId, UploadPurpose.AVATAR);
		byte[] jpeg = texturedJpeg();
		objectStore.put(temp, jpeg);
		AvatarProfile created = workflow.create(userId, uploadId);
		workflow.delete(userId, created.id(), created.revision());
		store.markReconciled(created.primaryImageId());
		objectStore.put(temp, jpeg);
		int semanticCalls = semanticInspector.calls;
		int claims = objectStore.claims().size();

		assertAvatarException(
			() -> workflow.create(userId, uploadId),
			AvatarProblemCode.AVATAR_UPLOAD_NOT_CLAIMABLE);

		assertThat(store.findByUser(userId)).isEmpty();
		assertThat(store.findByIdAndUser(created.id(), userId)).isEmpty();
		assertThat(store.isUploadConsumed(userId, uploadId)).isTrue();
		assertThat(semanticInspector.calls).isEqualTo(semanticCalls);
		assertThat(objectStore.claims()).hasSize(claims);
		assertThat(objectStore.head(temp)).isNotNull();
	}

	@Test
	void retiredUploadIsNotClaimableAndRepeatDeleteSucceeds() throws Exception {
		UserId userId = insertUser();
		UserId stranger = insertUser();
		UploadId first = new UploadId(UUID.randomUUID());
		UploadId second = new UploadId(UUID.randomUUID());
		objectStore.put(staging(userId, first, UploadPurpose.AVATAR), texturedJpeg());
		objectStore.put(staging(userId, second, UploadPurpose.AVATAR), texturedJpeg());
		AvatarProfile created = workflow.create(userId, first);
		workflow.replace(userId, created.id(), created.revision(), second);

		assertAvatarException(() -> workflow.create(userId, first), AvatarProblemCode.AVATAR_UPLOAD_NOT_CLAIMABLE);
		workflow.delete(userId, created.id(), created.revision() + 1);
		workflow.delete(userId, created.id(), 99);
		assertThat(workflow.list(userId)).isEmpty();
		assertThat(workflow.get(userId, created.id())).isEmpty();
		assertAvatarException(
			() -> workflow.delete(stranger, created.id(), 1),
			AvatarProblemCode.AVATAR_NOT_FOUND);
		assertThat(workflow.get(stranger, created.id())).isEmpty();
	}

	@Test
	void configurationWiresRequiredBeansAndDoesNotBeanObjectStore() {
		Configuration configuration = AvatarConfiguration.class.getAnnotation(Configuration.class);
		assertThat(configuration).isNotNull();
		assertThat(configuration.proxyBeanMethods()).isFalse();
		// A FactoryBean<T> @Bean method produces T, not FactoryBean. Reading the raw
		// return type collapsed every deferred bean to FactoryBean, which both
		// failed contains() and made doesNotContain(ObjectStore) vacuously true.
		Set<Class<?>> beanTypes = java.util.Arrays.stream(AvatarConfiguration.class.getDeclaredMethods())
			.filter(method -> method.getAnnotation(Bean.class) != null)
			.map(AvatarWorkflowTest::beanProductType)
			.collect(Collectors.toSet());
		assertThat(beanTypes)
			.contains(
				JdbcAvatarStore.class,
				SemanticInspector.class,
				AvatarPreflight.class,
				AvatarWorkflow.class,
				AvatarAccountMediaPurge.class)
			.doesNotContain(ObjectStore.class);
	}

	@Test
	void workflowSourceHasNoQueueOrProviderTypes() throws Exception {
		String source = Files.readString(avatarWorkflowSource(), StandardCharsets.UTF_8);
		assertThat(source)
			.doesNotContain("software.amazon.awssdk.services.sqs")
			.doesNotContain("SqsClient")
			.doesNotContain("Redis")
			.doesNotContain("VTON")
			.doesNotContain("Fashn")
			.doesNotContain("com.aifashionvault.user");
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

	private int count(String table) {
		Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
		assertThat(count).isNotNull();
		return count;
	}

	private static ObjectRef staging(UserId userId, UploadId uploadId, UploadPurpose purpose) {
		return new ObjectRef(StorageDomain.TEMP, StorageKeys.stagingKey(userId, uploadId, purpose).value());
	}

	private static Class<?> beanProductType(Method beanMethod) {
		Class<?> returnType = beanMethod.getReturnType();
		if (!FactoryBean.class.equals(returnType)) {
			return returnType;
		}
		Type generic = beanMethod.getGenericReturnType();
		if (generic instanceof ParameterizedType parameterized
				&& parameterized.getActualTypeArguments().length == 1
				&& parameterized.getActualTypeArguments()[0] instanceof Class<?> product) {
			return product;
		}
		return returnType;
	}

	private AvatarWorkflow workflow(JdbcAvatarStore avatarStore) {
		return new AvatarWorkflow(
			avatarStore,
			new ObjectStoreAvatarMediaAccess(objectStore),
			new AvatarPreflight(
				new ImageInspectorAvatarBytesInspector(new ImageInspector()),
				semanticInspector),
			CLOCK);
	}

	private ObjectRef claimedPersistentObject() {
		ClaimCommand claim = objectStore.claims().getLast();
		return new ObjectRef(claim.destinationBoundary(), claim.destination().value());
	}

	private static void assertAvatarException(Runnable action, AvatarProblemCode code) {
		assertThatThrownBy(action::run)
			.isInstanceOf(AvatarException.class)
			.satisfies(thrown -> {
				AvatarException exception = (AvatarException) thrown;
				assertThat(exception.code()).isEqualTo(code);
				assertThat(exception.getMessage()).isEqualTo(code.name());
				assertThat(exception.getMessage())
					.doesNotContain("://")
					.doesNotContain("users/")
					.doesNotContain("temp/");
			});
	}

	private static byte[] texturedJpeg() throws IOException {
		BufferedImage image = new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < 512; y++) {
			for (int x = 0; x < 512; x++) {
				int light = ((x / 32) + (y / 32)) % 2 == 0 ? 220 : 40;
				image.setRGB(x, y, (light << 16) | (light << 8) | light);
			}
		}
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("JPEG");
		if (!writers.hasNext()) {
			throw new IllegalStateException("no ImageWriter for JPEG");
		}
		ImageWriter writer = writers.next();
		try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
			writer.setOutput(output);
			writer.write(image);
		}
		finally {
			writer.dispose();
		}
		return bytes.toByteArray();
	}

	private static Path avatarWorkflowSource() {
		Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
		Path current = cwd;
		while (current != null) {
			Path direct = current.resolve(Path.of(
				"src",
				"main",
				"java",
				"com",
				"aifashionvault",
				"avatar",
				"AvatarWorkflow.java"));
			if (Files.isRegularFile(direct)) {
				return direct;
			}
			Path underBackend = current.resolve("backend").resolve(Path.of(
				"src",
				"main",
				"java",
				"com",
				"aifashionvault",
				"avatar",
				"AvatarWorkflow.java"));
			if (Files.isRegularFile(underBackend)) {
				return underBackend;
			}
			current = current.getParent();
		}
		throw new IllegalStateException("Cannot locate AvatarWorkflow.java from " + cwd);
	}

	private static final class FailingMutationStore extends JdbcAvatarStore {

		private RuntimeException insertFailure;
		private RuntimeException replaceFailure;

		FailingMutationStore(DriverManagerDataSource dataSource, Clock clock) {
			super(dataSource, clock);
		}

		@Override
		public AvatarProfile insertFirst(AvatarProfile profile, AvatarImage image) {
			if (insertFailure != null) {
				throw insertFailure;
			}
			return super.insertFirst(profile, image);
		}

		@Override
		public AvatarProfile replaceImage(
			AvatarProfileId profileId,
			UserId userId,
			int expectedRevision,
			AvatarImage newImage
		) {
			if (replaceFailure != null) {
				throw replaceFailure;
			}
			return super.replaceImage(profileId, userId, expectedRevision, newImage);
		}
	}

	private static final class RecordingSemanticInspector implements SemanticInspector {

		private SemanticInspection inspection =
			new SemanticInspection(PersonCountClass.ONE, true, true, "test-evaluator");
		private boolean unavailable;
		private int calls;

		@Override
		public SemanticInspection inspect(byte[] imageBytes) throws SemanticInspectionUnavailableException {
			calls++;
			if (unavailable) {
				throw new SemanticInspectionUnavailableException();
			}
			return inspection;
		}
	}

	private static final class FakeObjectStore implements ObjectStore {

		private final Map<ObjectRef, Stored> objects = new HashMap<>();
		private final List<ClaimCommand> claims = new ArrayList<>();
		private final List<ObjectRef> deleted = new ArrayList<>();
		private boolean failAvatarDeletes;

		void put(ObjectRef ref, byte[] payload) {
			objects.put(ref, new Stored(
				new ObjectMetadata(payload.length, "image/jpeg", "etag-1"),
				payload));
		}

		void remove(ObjectRef ref) {
			objects.remove(ref);
		}

		List<ClaimCommand> claims() {
			return List.copyOf(claims);
		}

		List<ObjectRef> deleted() {
			return List.copyOf(deleted);
		}

		@Override
		public UploadCapability presignStagingPut(UserId userId, UploadId uploadId, MediaDeclaration declaration) {
			throw new AssertionError("presignStagingPut must not be called");
		}

		@Override
		public UploadCapability presignStagingPut(
			UserId userId,
			UploadId uploadId,
			MediaDeclaration declaration,
			UploadPurpose purpose) {
			throw new AssertionError("presignStagingPut must not be called");
		}

		@Override
		public ObjectMetadata head(ObjectRef object) {
			Stored stored = objects.get(object);
			if (stored == null) {
				throw new ObjectStoreExceptions.StagedObjectMissingException("missing staged object");
			}
			return stored.metadata();
		}

		@Override
		public byte[] readBounded(ObjectRef object, String expectedVersion, int maxBytes) {
			Stored stored = objects.get(object);
			if (stored == null) {
				throw new ObjectStoreExceptions.StagedObjectMissingException("missing staged object");
			}
			byte[] payload = stored.payload();
			if (payload.length <= maxBytes) {
				return payload;
			}
			byte[] bounded = new byte[maxBytes];
			System.arraycopy(payload, 0, bounded, 0, maxBytes);
			return bounded;
		}

		@Override
		public void delete(ObjectRef object) {
			deleted.add(object);
			if (failAvatarDeletes && object.boundary() == StorageDomain.AVATAR) {
				throw new ObjectStoreExceptions.ObjectStoreUnavailableException("simulated delete failure");
			}
			objects.remove(object);
		}

		@Override
		public ClaimResult claim(ClaimCommand command) {
			claims.add(command);
			ObjectRef avatarTemp = new ObjectRef(
				StorageDomain.TEMP,
				StorageKeys.stagingKey(command.userId(), command.uploadId(), UploadPurpose.AVATAR).value());
			Stored source = objects.get(avatarTemp);
			if (source == null) {
				throw new ObjectStoreExceptions.StagedObjectMissingException("missing staged object");
			}
			objects.put(new ObjectRef(command.destinationBoundary(), command.destination().value()), source);
			return new ClaimResult.Copied();
		}

		@Override
		public void purgeUserObjects(UserId userId) {
			throw new AssertionError("purgeUserObjects must not be called");
		}

		@Override
		public void cleanupTemp() {
			throw new AssertionError("cleanupTemp must not be called");
		}

		@Override
		public PresignedAccess presignGet(ObjectRef object, Duration ttl) {
			throw new AssertionError("presignGet must not be called");
		}

		private record Stored(ObjectMetadata metadata, byte[] payload) {
		}
	}
}
