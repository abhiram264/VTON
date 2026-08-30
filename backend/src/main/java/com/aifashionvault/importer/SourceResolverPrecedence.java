package com.aifashionvault.importer;

public final class SourceResolverPrecedence {

	public static final int SHARED_IMAGE = 100;
	public static final int OPTIONAL_MIN = 200;
	public static final int OPTIONAL_MAX = 800;
	public static final int GENERIC_OPEN_GRAPH = 900;
	public static final int GENERIC_URL = 1000;

	private SourceResolverPrecedence() {
	}
}
