package com.aifashionvault.platform.objectstore;

import java.io.InputStream;

import com.aifashionvault.shared.StorageKey;

public interface GenerationMediaStore {

	void putBounded(StorageKey dest, InputStream source, long maxBytes, String mediaType);

	ClaimResult claim(GenerationClaimCommand command);

	default ClaimResult claimProductInput(ProductInputClaimCommand command) {
		throw new UnsupportedOperationException("claimProductInput");
	}
}
