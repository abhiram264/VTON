package com.aifashionvault.platform.web;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.aifashionvault.importer.IdempotencyKeys;
import com.aifashionvault.importer.ImportCommand;
import com.aifashionvault.importer.ImportMediaAccess;
import com.aifashionvault.importer.ImportResolver;
import com.aifashionvault.importer.JdbcShareImportStore;
import com.aifashionvault.importer.ResolutionReason;
import com.aifashionvault.importer.ResolutionResult;
import com.aifashionvault.importer.ResolutionStatus;
import com.aifashionvault.importer.ShareImport;
import com.aifashionvault.importer.ShareImportKind;
import com.aifashionvault.importer.ShareImportState;
import com.aifashionvault.importer.SourceReference;
import com.aifashionvault.importer.SourceUrls;
import com.aifashionvault.normalization.ProductCategory;
import com.aifashionvault.platform.importer.ImporterRetryableException;
import com.aifashionvault.platform.objectstore.ObjectStoreExceptions;
import com.aifashionvault.shared.ShareImportId;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

/**
 * Share-import application service: validates the frozen request union,
 * arbitrates idempotency through the PostgreSQL store, runs one bounded
 * resolver attempt per claimed lease, and maps results to the frozen response
 * union. Problem paths carry an opaque code only (ADR-015).
 */
public final class ShareImportService {

	private final JdbcShareImportStore store;
	private final ShareImportMediaAccess media;
	private final ShareImportResolverChain chains;

	public ShareImportService(JdbcShareImportStore store, ShareImportMediaAccess media,
			ShareImportResolverChain chains) {
		this.store = store;
		this.media = media;
		this.chains = chains;
	}

	public record CreateOutcome(ShareImportResponse body, int status) {
	}

	public record AttachOutcome(ShareImportResponse body, int status) {
	}

	public CreateOutcome create(UserId owner, String rawKey, ShareImportRequest request) {
		String key = requireKey(rawKey);
		Parsed parsed = parse(request);
		ImportCommand command = new ImportCommand(owner, key, parsed.kind, parsed.sourceUrl, parsed.uploadId);
		Optional<JdbcShareImportStore.CreateResult> arbitration = store.arbitrateCreate(command);
		if (arbitration.isPresent()) {
			JdbcShareImportStore.CreateResult decided = arbitration.get();
			// A fingerprint conflict is a key-reuse verdict and nothing else:
			// it is decided from the stored request alone, so it must not be
			// re-labelled by the media state of this request (unchanged).
			if (decided instanceof JdbcShareImportStore.FingerprintConflict) {
				throw ShareImportProblem.keyReused();
			}
			// A replay still revalidates staging before it resolves: the
			// earlier successful claim consumed that upload, so the media
			// is no longer claimable and replaying it would answer forever
			// (docs/API_CONTRACTS.md:48, F05).
			requireClaimableUpload(owner, parsed.uploadId);
			return handleCreateResult(decided, parsed.uploadId, false);
		}
		requireClaimableUpload(owner, parsed.uploadId);
		return handleCreateResult(store.create(command), parsed.uploadId, true);
	}

	private CreateOutcome handleCreateResult(
			JdbcShareImportStore.CreateResult result, UploadId uploadId, boolean newlyCreated) {
		return switch (result) {
			case JdbcShareImportStore.Created created -> runAttempt(created.shareImport(), uploadId, newlyCreated);
			case JdbcShareImportStore.Replayed replayed -> replayCreate(replayed.shareImport(), uploadId);
			case JdbcShareImportStore.FingerprintConflict ignored -> throw ShareImportProblem.keyReused();
		};
	}

	public ShareImportResponse get(UserId owner, String importId) {
		return map(requireOwned(owner, importId));
	}

	public AttachOutcome attach(UserId owner, String importId, String rawKey, AttachShareImageRequest request) {
		String key = requireKey(rawKey);
		ShareImportId id = parseImportId(importId);
		UploadId uploadId = parseUploadId(request == null || request.uploadId() == null ? null : request.uploadId());
		ShareImport current = requireOwned(owner, importId);
		// Recovery idempotency is bound to the persisted (key, upload) pair:
		// a replay reuses both; the same key under a different upload — or a
		// different key after recovery completed — is a conflict, never a
		// silent overwrite (spec: recovery replay / upload-binding).
		Optional<JdbcShareImportStore.RecoveryBinding> binding = store.recoveryBinding(owner, id);
		if (binding.isPresent()) {
			if (!binding.get().recoveryKey().equals(key)) {
				throw ShareImportProblem.stateConflict();
			}
			if (!binding.get().uploadId().value().equals(uploadId.value())) {
				throw ShareImportProblem.keyReused();
			}
			// Binding exists only on a READY row: replay the stored result.
			return new AttachOutcome(ShareImportResponse.ready(current.id().value().toString()), 200);
		}
		return switch (current.state()) {
			case NEEDS_IMAGE, FAILED_RETRYABLE -> {
				requireClaimableUpload(owner, uploadId);
				yield switch (store.beginRecovery(owner, id)) {
					case JdbcShareImportStore.RecoveryClaimed claimed ->
						runRecovery(claimed.shareImport(), uploadId, key);
					case JdbcShareImportStore.RecoveryNotRecoverable late -> attachState(late.shareImport());
					case JdbcShareImportStore.ImportMissing ignored -> throw ShareImportProblem.notFound();
				};
			}
		// PUT never re-runs URL resolution: recovery is permitted only from
		// NEEDS_IMAGE or FAILED_RETRYABLE (spec). While the import is RESOLVING
		// — fresh or expired lease — invalid request data is rejected without
		// mutation and the current state is reported; an expired lease is
		// reclaimed through create replay, which owns the attempt lease.
		case RESOLVING -> {
			requireClaimableUpload(owner, uploadId);
			yield attachState(current);
		}
		// A completed recovery replay returned above; any other READY row has no recovery binding.
		case READY -> throw ShareImportProblem.stateConflict();
		case FAILED_TERMINAL -> throw ShareImportProblem.stateConflict();
		case DELETED -> throw ShareImportProblem.notFound();
		};
	}

	public boolean delete(UserId owner, String importId) {
		ShareImportId id = parseImportId(importId);
		return switch (store.delete(owner, id)) {
			case JdbcShareImportStore.Deleted deleted -> {
				deleteObject(deleted.storageKey());
				markCleanedUp(owner, id);
				yield true;
			}
			case JdbcShareImportStore.AlreadyDeleted alreadyDeleted -> {
				// A retained key on an already-DELETED row is the cleanup-FAILED
				// signal: retry the physical removal exactly once. A successful
				// cleanup cleared the key, so an ordinary repeated DELETE skips
				// the removal and stays a 204 (idempotent logical delete).
				if (alreadyDeleted.storageKey() != null) {
					deleteObject(alreadyDeleted.storageKey());
					markCleanedUp(owner, id);
				}
				yield true;
			}
			case JdbcShareImportStore.Missing ignored -> false;
		};
	}

	/**
	 * Confirms a resolved import into the owner's persistent Vault. The import
	 * must be READY and owner-visible: the lookup is the same owner-scoped
	 * claim the read and delete paths use, so another owner's import is
	 * indistinguishable from a missing one (never weakened). The saved item
	 * carries the import's own source reference, so provenance survives
	 * confirmation; a repeated confirmation returns the same saved item.
	 */
	public VaultItemResponse confirm(UserId owner, String importId, ConfirmShareImportRequest request) {
		ShareImportId id = parseImportId(importId);
		Confirmation corrections = parseConfirmation(request);
		return store.confirm(owner, id, corrections.name(), corrections.category(), corrections.collectionId())
			.map(item -> new VaultItemResponse(item.itemId().toString(), item.sourceReferenceId().toString(),
				item.name(), null, category(item.category()), null, null, null, null, item.createdAt()))
			.orElseThrow(ShareImportProblem::notFound);
	}

	/**
	 * Parses the optional owner corrections of a confirmation request. A blank
	 * name, an unknown category or a malformed collection id is a malformed
	 * request (400) rather than a silent no-op.
	 */
	private static Confirmation parseConfirmation(ConfirmShareImportRequest request) {
		if (request == null) {
			return new Confirmation(null, null, null);
		}
		String name = request.name() == null ? null : request.name().strip();
		if (name != null && (name.isEmpty() || name.codePointCount(0, name.length()) > 200)) {
			throw ShareImportProblem.invalid();
		}
		String category = null;
		if (request.category() != null) {
			try {
				category = ProductCategory.valueOf(request.category().strip().toUpperCase(Locale.ROOT)).name();
			}
			catch (IllegalArgumentException ex) {
				throw ShareImportProblem.invalid();
			}
		}
		UUID collectionId = null;
		if (request.collectionId() != null) {
			try {
				collectionId = UUID.fromString(request.collectionId().strip());
			}
			catch (IllegalArgumentException ex) {
				throw ShareImportProblem.invalid();
			}
		}
		return new Confirmation(name, category, collectionId);
	}

	private static ProductCategory category(String stored) {
		return stored == null ? null : ProductCategory.valueOf(stored);
	}

	private record Confirmation(String name, String category, UUID collectionId) {
	}


	private void markCleanedUp(UserId owner, ShareImportId id) {
		store.markCleanedUp(owner, id);
	}

	private void deleteObject(com.aifashionvault.shared.StorageKey key) {
		if (key == null) {
			return;
		}
		try {
			media.deleteObject(key);
		}
		catch (ObjectStoreExceptions.ObjectStoreUnavailableException ex) {
			throw ShareImportProblem.dependencyUnavailable();
		}
	}

	private CreateOutcome replayCreate(ShareImport existing, UploadId uploadId) {
		if (existing.state() == ShareImportState.RESOLVING || existing.state() == ShareImportState.FAILED_RETRYABLE) {
			return switch (store.beginAttempt(existing.userId(), existing.id())) {
				case JdbcShareImportStore.AttemptClaimed claimed -> runAttempt(claimed.shareImport(), uploadId, false);
				case JdbcShareImportStore.AttemptHeld held -> new CreateOutcome(map(held.shareImport()), 202);
			};
		}
		return new CreateOutcome(map(existing), 200);
	}

	private CreateOutcome runAttempt(ShareImport attempt, UploadId uploadId, boolean newlyCreated) {
		int ok = newlyCreated ? 201 : 200;
		ResolutionResult result = resolve(attempt, uploadId);
		if (result.status() == ResolutionStatus.UNSUPPORTED) {
			store.publish(attempt.id(), attempt.attemptToken(), invalidSource());
			throw ShareImportProblem.uploadNotClaimable();
		}
		if (!store.publish(attempt.id(), attempt.attemptToken(), result)) {
			// The attempt lost its lease (concurrent delete/reclaim won): the
			// row's storage key was never set, so only this caller can remove
			// the object the resolver already published. Best-effort only.
			deleteLoserObject(result);
			return new CreateOutcome(map(requireOwned(attempt.userId(), attempt.id().value().toString())), ok);
		}
		return new CreateOutcome(mapResult(attempt.id(), result), ok);
	}

	private AttachOutcome runRecovery(ShareImport attempt, UploadId uploadId, String recoveryKey) {
		ResolutionResult result = resolve(attempt, uploadId);
		if (result.status() == ResolutionStatus.UNSUPPORTED) {
			store.publishRecovery(attempt.id(), attempt.attemptToken(), invalidSource(), recoveryKey, uploadId);
			throw ShareImportProblem.uploadNotClaimable();
		}
		if (!store.publishRecovery(attempt.id(), attempt.attemptToken(), result, recoveryKey, uploadId)) {
			// Same late-loser cleanup as runAttempt: the losing recovery
			// attempt's published object is unreachable through the row.
			deleteLoserObject(result);
			return new AttachOutcome(map(requireOwned(attempt.userId(), attempt.id().value().toString())), 200);
		}
		return new AttachOutcome(mapResult(attempt.id(), result), 200);
	}

	private void deleteLoserObject(ResolutionResult result) {
		if (result.storageKey() != null) {
			try {
				media.deleteObject(result.storageKey());
			}
			catch (RuntimeException ignored) {
				// best-effort: a leaked object is preferable to failing the loser's response
			}
		}
	}

	private ResolutionResult resolve(ShareImport attempt, UploadId uploadId) {
		ImportResolver resolver = chains.chain(attempt.userId(), attempt.id(), attempt.attemptToken().toString());
		SourceReference source = new SourceReference(attempt.sourceUrl(), uploadId);
		try {
			return resolver.resolve(source);
		}
		catch (ImporterRetryableException ex) {
			store.publish(attempt.id(), attempt.attemptToken(),
				new ResolutionResult(ResolutionStatus.RETRYABLE_FAILURE, null, null, null, null, ex.reason()));
			throw problemFor(ex.reason());
		}
	}

	private AttachOutcome attachState(ShareImport current) {
		return switch (current.state()) {
			case RESOLVING -> new AttachOutcome(map(current), 202);
			case READY -> new AttachOutcome(ShareImportResponse.ready(current.id().value().toString()), 200);
			case DELETED -> throw ShareImportProblem.notFound();
			default -> throw ShareImportProblem.stateConflict();
		};
	}

	private ShareImport requireOwned(UserId owner, String importId) {
		ShareImportId id = parseImportId(importId);
		return store.find(owner, id).orElseThrow(ShareImportProblem::notFound);
	}

	private void requireClaimableUpload(UserId owner, UploadId uploadId) {
		if (uploadId != null && media.findVerifiedUpload(owner, uploadId).isEmpty()) {
			throw ShareImportProblem.uploadNotClaimable();
		}
	}

	private ShareImportResponse map(ShareImport importRow) {
		String id = importRow.id().value().toString();
		return switch (importRow.state()) {
			case RESOLVING -> ShareImportResponse.resolving(id);
			case READY -> ShareImportResponse.ready(id);
			case NEEDS_IMAGE -> ShareImportResponse.needsImage(id);
			case FAILED_RETRYABLE -> ShareImportResponse.failed(id, "FAILED_RETRYABLE",
				reason(importRow, ResolutionReason.DEPENDENCY_UNAVAILABLE).name());
			case FAILED_TERMINAL -> ShareImportResponse.failed(id, "FAILED_TERMINAL",
				ResolutionReason.INVALID_SOURCE.name());
			case DELETED -> throw ShareImportProblem.notFound();
		};
	}

	private ShareImportResponse mapResult(ShareImportId id, ResolutionResult result) {
		return switch (result.status()) {
			case RESOLVED -> ShareImportResponse.ready(id.value().toString());
			case NEEDS_IMAGE -> ShareImportResponse.needsImage(id.value().toString());
			case TERMINAL_FAILURE -> ShareImportResponse.failed(id.value().toString(), "FAILED_TERMINAL",
				ResolutionReason.INVALID_SOURCE.name());
			default -> throw ShareImportProblem.invalid();
		};
	}

	private static ResolutionReason reason(ShareImport importRow, ResolutionReason fallback) {
		return importRow.terminalReason() == null ? fallback : importRow.terminalReason();
	}

	private static RuntimeException problemFor(ResolutionReason reason) {
		return reason == ResolutionReason.RESOLUTION_TIMEOUT
			? ShareImportProblem.resolutionTimeout()
			: ShareImportProblem.dependencyUnavailable();
	}

	private static ResolutionResult invalidSource() {
		return new ResolutionResult(ResolutionStatus.TERMINAL_FAILURE, null, null, null, null,
			ResolutionReason.INVALID_SOURCE);
	}

	private static String requireKey(String rawKey) {
		if (rawKey == null || rawKey.isBlank()) {
			throw ShareImportProblem.keyRequired();
		}
		String key = rawKey.strip();
		try {
			IdempotencyKeys.validateFormat(key);
		}
		catch (IllegalArgumentException ex) {
			throw ShareImportProblem.keyRequired();
		}
		return key;
	}

	private static ShareImportId parseImportId(String text) {
		try {
			return new ShareImportId(UUID.fromString(text));
		}
		catch (IllegalArgumentException ex) {
			throw ShareImportProblem.invalid();
		}
	}

	private static UploadId parseUploadId(String text) {
		if (text == null || text.isBlank()) {
			throw ShareImportProblem.invalid();
		}
		try {
			return new UploadId(UUID.fromString(text.strip()));
		}
		catch (IllegalArgumentException ex) {
			throw ShareImportProblem.invalid();
		}
	}

	private static Parsed parse(ShareImportRequest request) {
		if (request == null || request.kind() == null) {
			throw ShareImportProblem.invalid();
		}
		ShareImportKind kind = switch (request.kind()) {
			case "URL" -> ShareImportKind.URL;
			case "IMAGE" -> ShareImportKind.IMAGE;
			case "IMAGE_URL" -> ShareImportKind.IMAGE_URL;
			default -> throw ShareImportProblem.invalid();
		};
		URI sourceUrl = null;
		UploadId uploadId = null;
		switch (kind) {
			case URL -> {
				if (request.uploadId() != null) {
					throw ShareImportProblem.invalid();
				}
				sourceUrl = parseSourceUrl(request.sourceUrl());
			}
			case IMAGE -> {
				if (request.sourceUrl() != null) {
					throw ShareImportProblem.invalid();
				}
				uploadId = parseUploadId(request.uploadId());
			}
			case IMAGE_URL -> {
				sourceUrl = parseSourceUrl(request.sourceUrl());
				uploadId = parseUploadId(request.uploadId());
			}
		}
		return new Parsed(kind, sourceUrl, uploadId);
	}

	private static URI parseSourceUrl(String text) {
		if (text == null || text.isBlank()) {
			throw ShareImportProblem.invalid();
		}
		final URI uri;
		try {
			uri = URI.create(text.strip());
		}
		catch (IllegalArgumentException ex) {
			throw ShareImportProblem.invalid();
		}
		try {
			return SourceUrls.requireHttpUri(uri);
		}
		catch (IllegalArgumentException | NullPointerException ex) {
			throw ShareImportProblem.invalid();
		}
	}

	private record Parsed(ShareImportKind kind, URI sourceUrl, UploadId uploadId) {
	}
}
