package com.aifashionvault.platform.objectstore;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

import com.aifashionvault.generation.vton.GenerationOutputStore;
import com.aifashionvault.generation.vton.PrivateMediaRef;
import com.aifashionvault.generation.vton.ProviderMediaSource;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UserId;

/**
 * Object-store-backed output store for one generation attempt. Writes use an
 * idempotent, attempt-pinned TEMP key (Q33), so a recopy of the same
 * provider output targets the same object rather than minting a new one and
 * the object store {@code if-none-match} guard makes repeated puts safe.
 */
public final class ObjectStoreGenerationOutputStore implements GenerationOutputStore {

	private static final int LOCAL_PUT_ATTEMPTS = 2;

	private final GenerationMediaStore media;
	private final ObjectStore objects;
	private final UserId userId;
	private final UUID jobId;
	private final UUID attemptId;
	private final ImageMediaType mediaType;
	private PrivateMediaRef written;

	public ObjectStoreGenerationOutputStore(
		GenerationMediaStore media,
		ObjectStore objects,
		UserId userId,
		UUID jobId,
		UUID attemptId,
		ImageMediaType mediaType
	) {
		if (media == null) {
			throw new IllegalArgumentException("media is required");
		}
		if (objects == null) {
			throw new IllegalArgumentException("objects is required");
		}
		if (userId == null) {
			throw new IllegalArgumentException("userId is required");
		}
		if (jobId == null) {
			throw new IllegalArgumentException("jobId is required");
		}
		if (attemptId == null) {
			throw new IllegalArgumentException("attemptId is required");
		}
		if (mediaType == null) {
			throw new IllegalArgumentException("mediaType is required");
		}
		this.media = media;
		this.objects = objects;
		this.userId = userId;
		this.jobId = jobId;
		this.attemptId = attemptId;
		this.mediaType = mediaType;
	}

	/**
	 * Copies the provider output into the private TEMP boundary, applying
	 * bounded local storage recovery (Q33/T18): a transient store
	 * unavailability failure gets exactly one immediate local retry against
	 * the same source and the same idempotent TEMP key. A second transient
	 * failure, a confirmed-missing staged object, or a source that cannot be
	 * repositioned for the retry surfaces as
	 * {@link GenerationOutputStore.StorageUnavailableException} so the
	 * processor can terminalize STORAGE_UNAVAILABLE without regenerating
	 * paid provider work. Logging stays on the opaque TEMP key: no object
	 * content or provider URLs (Q59).
	 */
	@Override
	public PrivateMediaRef writePrivateTemp(ProviderMediaSource source) throws IOException {
		if (source == null) {
			throw new IllegalArgumentException("source is required");
		}
		if (written != null) {
			return written;
		}
		StorageKey dest = tempKey();
		InputStream stream = source.stream();
		ObjectStoreExceptions.ObjectStoreUnavailableException unavailable = null;
		for (int attempt = 1; attempt <= LOCAL_PUT_ATTEMPTS; attempt++) {
			try {
				media.putBounded(dest, stream, source.maxBytes(), source.mediaType());
				written = new PrivateMediaRef(dest, source.contentLength());
				return written;
			}
			catch (ObjectStoreExceptions.ObjectStoreUnavailableException ex) {
				if (unavailable != null) {
					ex.addSuppressed(unavailable);
				}
				unavailable = ex;
			}
			catch (ObjectStoreExceptions.StagedObjectMissingException ex) {
				// Confirmed-missing source is terminal (Q33): a local retry
				// cannot recreate an object the store reports absent.
				throw new GenerationOutputStore.StorageUnavailableException(
					"generation output source is missing from the object store", ex);
			}
			if (attempt < LOCAL_PUT_ATTEMPTS && !reposition(stream)) {
				throw new GenerationOutputStore.StorageUnavailableException(
					"object store could not hold the generation output; source cannot be re-read for the local retry",
					unavailable);
			}
		}
		throw new GenerationOutputStore.StorageUnavailableException(
			"object store could not hold the generation output after bounded local recovery",
			unavailable);
	}

	public void discard() {
		objects.delete(new ObjectRef(StorageBoundary.TEMP, tempKey().value()));
		written = null;
	}

	public ClaimResult promote(String sourceVersion) {
		return media.claim(new GenerationClaimCommand(
			userId,
			tempKey(),
			StorageKeys.persistentKey(userId, StorageBoundary.LOOK, attemptId, mediaType),
			sourceVersion,
			attemptId.toString()));
	}

	private static boolean reposition(InputStream stream) {
		if (!stream.markSupported()) {
			return false;
		}
		try {
			stream.reset();
			return true;
		}
		catch (IOException ex) {
			return false;
		}
	}

	private StorageKey tempKey() {
		return StorageKeys.generationTempKey(userId, jobId, attemptId, mediaType);
	}
}
