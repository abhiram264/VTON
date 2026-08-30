package com.aifashionvault.importer;

import java.net.URI;
import java.util.Objects;

public final class SourceUrls {

	private SourceUrls() {
	}

	public static URI requireHttpUri(URI uri) {
		Objects.requireNonNull(uri, "sourceUrl");
		String scheme = uri.getScheme();
		if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
			throw new IllegalArgumentException("sourceUrl must be an absolute http or https URI");
		}
		if (uri.getHost() == null || uri.getHost().isBlank()) {
			throw new IllegalArgumentException("sourceUrl requires a host");
		}
		if (uri.getUserInfo() != null) {
			throw new IllegalArgumentException("sourceUrl must not carry credentials");
		}
		if (uri.getRawFragment() != null) {
			throw new IllegalArgumentException("sourceUrl must not carry a fragment");
		}
		return uri;
	}
}
