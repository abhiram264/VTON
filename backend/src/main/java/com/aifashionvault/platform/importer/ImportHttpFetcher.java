package com.aifashionvault.platform.importer;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

/**
 * Replacerable transport seam for the share-import resolver. The production
 * adapter is the dedicated, SSRF-hardened import HTTP client; tests supply a
 * fake transport so no public-Internet call ever happens.
 */
public interface ImportHttpFetcher {

	/**
	 * Performs one GET under the full import network policy (redirect budget
	 * validation happens at the resolver level; the transport must already
	 * enforce timeouts, DNS/address policy, certificate verification, and byte
	 * streaming caps).
	 *
	 * @param target validated safe URI to fetch
	 * @param maxBytes hard cap on the response body; streams are truncated at
	 * exactly this bound and flagged when the origin sent more
	 */
	ImportHttpResponse get(URI target, long maxBytes) throws IOException, ImportNetworkException;

	/**
	 * Deadline-aware variant: transports that can honor a total-resolution
	 * deadline mid-fetch must do so (a slow-trickle origin must not hold the
	 * calling thread indefinitely); transports that cannot simply delegate.
	 *
	 * @param deadline total resolution budget deadline; {@code null} means no
	 * extra deadline beyond the transport's own per-read timeout
	 */
	default ImportHttpResponse get(URI target, long maxBytes, java.time.Instant deadline)
			throws IOException, ImportNetworkException {
		return get(target, maxBytes);
	}

	/** One hop's outcome; {@code location} carries the Location header on redirects. */
	record ImportHttpResponse(int statusCode, String contentType, Long contentLength, boolean truncated,
			InputStream body, URI finalUri, String location) {

		public ImportHttpResponse {
			if (finalUri == null) {
				throw new IllegalArgumentException("finalUri is required");
			}
		}
	}

	/** Classification of transport-level failures. */
	enum TransportFailure {
		/** Connection or read timeout. */
		TIMEOUT,
		/** DNS resolution failure. */
		DNS,
		/** Anything else: TLS, connection reset, protocol error. */
		TRANSPORT
	}

	/** Thrown by adapters on transport-level failure. */
	final class TransportException extends IOException {

		private static final long serialVersionUID = 1L;

		private final TransportFailure failure;

		public TransportException(TransportFailure failure, String message) {
			super(message);
			this.failure = failure;
		}

		public TransportFailure failure() {
			return failure;
		}
	}
}
