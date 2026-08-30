package com.aifashionvault.platform.importer;

import java.net.URI;

import com.aifashionvault.importer.ImportResolverLimits;

/**
 * URL admission policy for the share-import fetcher: absolute hierarchical
 * http/https only, non-empty host, no userinfo, no fragment, port 80/443 only
 * (explicit or scheme default). Applied to the initial URL, every redirect
 * target, and every Open Graph image URL.
 */
final class SafeHttpUri {

	private SafeHttpUri() {
	}

	static URI requireSafe(URI uri) {
		if (uri == null) {
			throw new ImportNetworkException(FailureKind.UNSAFE_URL, "uri is required");
		}
		String scheme = uri.getScheme();
		if (scheme == null) {
			throw new ImportNetworkException(FailureKind.UNSAFE_URL, "uri must be absolute");
		}
		boolean https = scheme.equalsIgnoreCase("https");
		if (!https && !scheme.equalsIgnoreCase("http")) {
			throw new ImportNetworkException(FailureKind.UNSAFE_URL, "uri scheme is not permitted");
		}
		if (!uri.isAbsolute() || uri.getAuthority() == null) {
			throw new ImportNetworkException(FailureKind.UNSAFE_URL, "uri must be hierarchical");
		}
		String host = uri.getHost();
		if (host == null || host.isBlank()) {
			throw new ImportNetworkException(FailureKind.UNSAFE_URL, "uri requires a host");
		}
		if (uri.getUserInfo() != null) {
			throw new ImportNetworkException(FailureKind.UNSAFE_URL, "uri must not carry credentials");
		}
		if (uri.getRawFragment() != null) {
			throw new ImportNetworkException(FailureKind.UNSAFE_URL, "uri must not carry a fragment");
		}
		int port = uri.getPort();
		if (port != -1 && port != 80 && port != 443) {
			throw new ImportNetworkException(FailureKind.UNSAFE_URL, "uri port is not permitted");
		}
		return uri;
	}

	static URI parse(String raw) {
		if (raw == null || raw.isBlank()) {
			throw new ImportNetworkException(FailureKind.BAD_REDIRECT, "uri text is required");
		}
		final URI parsed;
		try {
			parsed = new URI(raw.strip());
		}
		catch (Exception ex) {
			throw new ImportNetworkException(FailureKind.BAD_REDIRECT, "uri text is malformed", ex);
		}
		return parsed;
	}

	static int effectivePort(URI safeUri) {
		int port = safeUri.getPort();
		if (port != -1) {
			return port;
		}
		return safeUri.getScheme().equalsIgnoreCase("https") ? 443 : 80;
	}

	static int maxRedirects() {
		return ImportResolverLimits.MAX_REDIRECTS;
	}
}
