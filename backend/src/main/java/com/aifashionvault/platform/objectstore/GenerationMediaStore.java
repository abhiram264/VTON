package com.aifashionvault.platform.objectstore;

import java.io.InputStream;

import com.aifashionvault.shared.StorageKey;

public interface GenerationMediaStore {

	void putBounded(StorageKey dest, InputStream source, long maxBytes, String mediaType);

	ClaimResult claim(GenerationClaimCommand command);

	void preflightProductInput(ProductInputClaimCommand command);

	ClaimResult claimProductInput(ProductInputClaimCommand command);

	void releaseProductInput(ProductInputClaimCommand command);
}
