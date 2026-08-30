package com.aifashionvault.importer;

import java.util.Objects;
import java.util.List;

/**
 * Ordered resolver chain: asks resolvers in sequence and accepts the first
 * result other than {@link ResolutionStatus#UNSUPPORTED}. Exactly one resolver
 * owns a successful media publication because iteration stops at the winner.
 */
public final class ImportResolver {

	private final List<SourceResolver> resolvers;

	public ImportResolver(List<SourceResolver> resolvers) {
		Objects.requireNonNull(resolvers, "resolvers");
		this.resolvers = List.copyOf(resolvers);
		if (this.resolvers.isEmpty()) {
			throw new IllegalArgumentException("at least one source resolver is required");
		}
	}

	public ResolutionResult resolve(SourceReference source) {
		Objects.requireNonNull(source, "source");
		for (SourceResolver resolver : resolvers) {
			if (!resolver.supports(source)) {
				continue;
			}
			ResolutionResult result = Objects.requireNonNull(resolver.resolve(source), "resolver result");
			if (result.status() != ResolutionStatus.UNSUPPORTED) {
				return result;
			}
		}
		return new ResolutionResult(ResolutionStatus.UNSUPPORTED, null, null, null, null, null);
	}
}
