package com.aifashionvault.importer;

@FunctionalInterface
public interface SourceResolverFactory {

	SourceResolver create(ResolutionContext context);
}
