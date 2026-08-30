package com.aifashionvault.platform.importer;

import java.time.Duration;
import java.time.Instant;

import com.aifashionvault.importer.ImportResolverLimits;
import com.aifashionvault.importer.ResolutionReason;
import com.aifashionvault.importer.ResolutionResult;
import com.aifashionvault.importer.ResolutionStatus;
import com.aifashionvault.importer.SourceReference;
import com.aifashionvault.importer.SourceResolver;

/**
 * Deterministic terminal resolver for any otherwise-valid URL: preserves the
 * safe source reference and asks the shopper for an image. It never scrapes,
 * never fabricates metadata, and never reports READY without media.
 */
public final class GenericUrlResolver implements SourceResolver {

	@Override
	public boolean supports(SourceReference source) {
		return source.url() != null;
	}

	@Override
	public ResolutionResult resolve(SourceReference source) {
		if (!supports(source)) {
			return new ResolutionResult(ResolutionStatus.UNSUPPORTED, null, null, null, null, null);
		}
		return new ResolutionResult(
				ResolutionStatus.NEEDS_IMAGE,
				null,
				source.url(),
				null,
				null,
				ResolutionReason.URL_RESOLUTION_FAILED);
	}
}
