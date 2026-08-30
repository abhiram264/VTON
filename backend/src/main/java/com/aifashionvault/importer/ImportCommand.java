package com.aifashionvault.importer;

import java.net.URI;
import java.util.Objects;

import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

public record ImportCommand(
	UserId ownerId,
	String idempotencyKey,
	ShareImportKind kind,
	URI sourceUrl,
	UploadId uploadId) {

	public ImportCommand {
		Objects.requireNonNull(ownerId, "ownerId");
		IdempotencyKeys.validateFormat(idempotencyKey);
		Objects.requireNonNull(kind, "kind");
		switch (kind) {
			case URL -> {
				SourceUrls.requireHttpUri(sourceUrl);
				if (uploadId != null) {
					throw new IllegalArgumentException("uploadId must be null for URL imports");
				}
			}
			case IMAGE -> {
				Objects.requireNonNull(uploadId, "uploadId");
				if (sourceUrl != null) {
					throw new IllegalArgumentException("sourceUrl must be null for IMAGE imports");
				}
			}
			case IMAGE_URL -> {
				SourceUrls.requireHttpUri(sourceUrl);
				Objects.requireNonNull(uploadId, "uploadId");
			}
		}
	}
}
