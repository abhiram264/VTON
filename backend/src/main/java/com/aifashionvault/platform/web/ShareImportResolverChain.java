package com.aifashionvault.platform.web;

import com.aifashionvault.importer.ImportResolver;
import com.aifashionvault.shared.ShareImportId;
import com.aifashionvault.shared.UserId;

/**
 * Builds the ordered resolver chain for one resolution attempt. Production
 * wires {@code SharedImageResolver, GenericOpenGraphResolver,
 * GenericUrlResolver} from the platform adapters; tests substitute fakes so no
 * network or object store is ever touched.
 */
public interface ShareImportResolverChain {

	ImportResolver chain(UserId ownerId, ShareImportId importId, String attemptToken);
}
