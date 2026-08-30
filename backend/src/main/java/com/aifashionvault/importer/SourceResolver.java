package com.aifashionvault.importer;

public interface SourceResolver {

	boolean supports(SourceReference source);

	ResolutionResult resolve(SourceReference source);
}
