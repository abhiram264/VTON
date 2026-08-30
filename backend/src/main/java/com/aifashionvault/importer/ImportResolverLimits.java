package com.aifashionvault.importer;

import java.time.Duration;

public final class ImportResolverLimits {

	public static final int MAX_REDIRECTS = 3;
	public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
	public static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
	public static final Duration TOTAL_RESOLUTION_TIMEOUT = Duration.ofSeconds(10);
	public static final long MAX_HTML_BYTES = 524288;
	public static final long MAX_IMAGE_BYTES = 20971520;
	public static final Duration RESOLUTION_LEASE = Duration.ofSeconds(60);
	public static final int MIN_IDEMPOTENCY_KEY_LENGTH = 16;
	public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
	public static final int MAX_SOURCE_METADATA_LENGTH = 512;

	private ImportResolverLimits() {
	}
}
