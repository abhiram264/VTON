package com.aifashionvault.importer;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.aifashionvault.shared.ShareImportId;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UserId;

public record ShareImport(
	ShareImportId id,
	UserId userId,
	String idempotencyKey,
	String requestFingerprint,
	ShareImportKind kind,
	URI sourceUrl,
	StorageKey storageKey,
	ShareImportState state,
	int attemptCount,
	Instant attemptLeasedAt,
	UUID attemptToken,
	ResolutionReason terminalReason,
	String sourceTitle,
	String sourceSiteName,
	Instant createdAt,
	Instant updatedAt) {

	public ShareImport {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(userId, "userId");
		IdempotencyKeys.validateFormat(idempotencyKey);
		Objects.requireNonNull(requestFingerprint, "requestFingerprint");
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(state, "state");
		Objects.requireNonNull(createdAt, "createdAt");
		Objects.requireNonNull(updatedAt, "updatedAt");
		if (attemptCount < 0) {
			throw new IllegalArgumentException("attemptCount must not be negative");
		}
		switch (kind) {
			case URL, IMAGE_URL -> SourceUrls.requireHttpUri(sourceUrl);
			case IMAGE -> {
				if (sourceUrl != null) {
					throw new IllegalArgumentException("sourceUrl must be null for IMAGE imports");
				}
			}
		}
		if (attemptToken != null && attemptLeasedAt == null) {
			throw new IllegalArgumentException("attemptLeasedAt is required when attemptToken is present");
		}
		switch (state) {
			case NEEDS_IMAGE, FAILED_RETRYABLE, FAILED_TERMINAL -> Objects.requireNonNull(terminalReason, "terminalReason");
			case READY -> {
				if (storageKey == null) {
					throw new IllegalArgumentException("storageKey is required when READY");
				}
				if (terminalReason != null) {
					throw new IllegalArgumentException("terminalReason must be null in state READY");
				}
			}
			case RESOLVING, DELETED -> {
				if (terminalReason != null) {
					throw new IllegalArgumentException("terminalReason must be null in state " + state);
				}
			}
		}
		sourceTitle = bounded(sourceTitle);
		sourceSiteName = bounded(sourceSiteName);
	}

	private static String bounded(String value) {
		if (value == null) {
			return null;
		}
		if (value.length() > ImportResolverLimits.MAX_SOURCE_METADATA_LENGTH) {
			throw new IllegalArgumentException("source metadata exceeds the bounded length");
		}
		return value;
	}
}
