package com.aifashionvault.importer;

import java.net.URI;
import java.util.Objects;

import com.aifashionvault.shared.UploadId;

public record SourceReference(
	URI url,
	UploadId verifiedUploadId) {

	public SourceReference {
		if (url == null && verifiedUploadId == null) {
			throw new IllegalArgumentException("source reference requires a url or a verified upload");
		}
		if (url != null) {
			SourceUrls.requireHttpUri(url);
		}
	}
}
