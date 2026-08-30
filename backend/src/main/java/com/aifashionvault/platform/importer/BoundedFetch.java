package com.aifashionvault.platform.importer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

import com.aifashionvault.importer.ImportResolverLimits;

/**
 * Bounded fetch engine: manual redirects (revalidating every hop), frozen
 * per-hop and total budgets, and streaming byte caps independent of any
 * Content-Length header. Used for both the HTML document and every Open Graph
 * image fetch; each call gets its own remaining-budget accounting.
 */
final class BoundedFetch {

	private final ImportHttpFetcher fetcher;
	private final ImportDns dns;

	BoundedFetch(ImportHttpFetcher fetcher, ImportDns dns) {
		this.fetcher = fetcher;
		this.dns = dns;
	}

	/**
	 * Fetches {@code start} following at most three redirects, resolving
	 * relative {@code Location} values against the current URI and re-running
	 * URL/DNS policy on every hop. The body is fully materialized up to
	 * {@code maxBytes}; a larger body raises UNUSABLE_CONTENT (documented as
	 * the size-cap terminal outcome, mirrored by the resolver's NEEDS_IMAGE
	 * classification).
	 */
	Fetched fetch(URI start, long maxBytes, Instant deadline) {
		URI current = SafeHttpUri.requireSafe(start);
		int redirects = 0;
		ImportHttpFetcher.ImportHttpResponse response;
		while (true) {
			ensureBudget(deadline);
			dns.resolveHost(current);
			response = fetchOne(current, maxBytes, deadline);
			if (isRedirect(response.statusCode())) {
				if (redirects >= SafeHttpUri.maxRedirects()) {
					throw new ImportNetworkException(FailureKind.BAD_REDIRECT, "redirect budget exhausted");
				}
				String location = headerLocation(response);
				if (location == null) {
					throw new ImportNetworkException(FailureKind.BAD_REDIRECT, "redirect without location");
				}
				URI next;
				try {
					next = current.resolve(location.strip());
				}
				catch (IllegalArgumentException | NullPointerException ex) {
					throw new ImportNetworkException(FailureKind.BAD_REDIRECT, "redirect target is malformed", ex);
				}
				SafeHttpUri.requireSafe(next);
				dns.resolveHost(next);
				redirects++;
				current = next;
				continue;
			}
			break;
		}
		if (response.truncated() || contentLengthOver(response, maxBytes)) {
			consumeQuietly(response.body());
			throw new ImportNetworkException(FailureKind.UNUSABLE_CONTENT, "response exceeds the size cap");
		}
		byte[] body = readAll(response.body(), maxBytes);
		return new Fetched(response.statusCode(), response.contentType(), current, body);
	}

	private ImportHttpFetcher.ImportHttpResponse fetchOne(URI uri, long maxBytes, Instant deadline) {
		try {
			return fetcher.get(uri, maxBytes, deadline);
		}
		catch (ImportHttpFetcher.TransportException ex) {
			throw transportFailure(ex);
		}
		catch (IOException ex) {
			throw new ImportNetworkException(FailureKind.TRANSPORT_UNUSABLE, "transport failure", ex);
		}
		catch (ImportNetworkException ex) {
			throw ex;
		}
	}

	private static ImportNetworkException transportFailure(ImportHttpFetcher.TransportException ex) {
		return switch (ex.failure()) {
			case TIMEOUT -> new ImportNetworkException(FailureKind.TIMEOUT, "fetch timed out", ex);
			case DNS -> new ImportNetworkException(FailureKind.DNS_FAILURE, "dns failure", ex);
			case TRANSPORT -> new ImportNetworkException(FailureKind.TRANSPORT_UNUSABLE, "transport failure", ex);
		};
	}

	private static void ensureBudget(Instant deadline) {
		if (deadline != null && Instant.now().isAfter(deadline)) {
			throw new ImportNetworkException(FailureKind.TIMEOUT, "total resolution budget exhausted");
		}
	}

	private static boolean isRedirect(int status) {
		return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
	}

	private static String headerLocation(ImportHttpFetcher.ImportHttpResponse response) {
		// The fake transport in tests supplies the location via a header-like map; the
		// production adapter exposes it as a dedicated accessor.
		return response.location();
	}

	private static boolean contentLengthOver(ImportHttpFetcher.ImportHttpResponse response, long maxBytes) {
		Long declared = response.contentLength();
		return declared != null && declared > maxBytes;
	}

	private static byte[] readAll(InputStream body, long maxBytes) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		try (body) {
			int read;
			while ((read = body.read(buffer)) != -1) {
				if (out.size() + (long) read > maxBytes) {
					throw new ImportNetworkException(FailureKind.UNUSABLE_CONTENT, "response exceeds the size cap");
				}
				out.write(buffer, 0, read);
			}
		}
		catch (IOException ex) {
			throw new ImportNetworkException(FailureKind.TRANSPORT_UNUSABLE, "failed reading body");
		}
		return out.toByteArray();
	}

	private static void consumeQuietly(InputStream body) {
		if (body == null) {
			return;
		}
		try (body) {
			body.readAllBytes();
		}
		catch (IOException ignored) {
		}
	}

	record Fetched(int statusCode, String contentType, URI finalUri, byte[] body) {
	}
}
