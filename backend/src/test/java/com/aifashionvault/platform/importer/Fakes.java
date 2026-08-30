package com.aifashionvault.platform.importer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.aifashionvault.importer.ImportMediaAccess;
import com.aifashionvault.importer.ResolutionReason;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UploadId;

/**
 * In-memory fakes for the resolver tests: transport, DNS, image policy, and
 * media access. No network or object store is ever touched; outputs carry no
 * URL, key, HTML, or bytes.
 */
final class Fakes {

	static final String HOST = "source.example.test";
	static final URI PAGE = URI.create("https://" + HOST + "/item");

	private Fakes() {
	}

	/** Scriptable transport serving canned hops keyed by URI string. */
	static final class FakeTransport implements ImportHttpFetcher {

		final List<URI> requested = new ArrayList<>();
		Map<String, Hop> hops = new LinkedHashMap<>();

		@Override
		public ImportHttpResponse get(URI target, long maxBytes) throws IOException {
			requested.add(target);
			Hop hop = hops.get(target.toString());
			if (hop == null) {
				return respond(target, 404, "text/plain", null, "missing");
			}
			if (hop.failure != null) {
				throw hop.failure;
			}
			if (hop.bytes != null || hop.declaredLength != null) {
				byte[] data = hop.bytes == null ? new byte[0] : hop.bytes;
				Long declared = hop.declaredLength == null ? (long) data.length : hop.declaredLength;
				boolean truncated = declared > maxBytes;
				return new ImportHttpResponse(
						hop.status,
						hop.contentType,
						declared,
						truncated,
						new ByteArrayInputStream(data),
						target,
						hop.location);
			}
			return respond(target, hop.status, hop.contentType, hop.location, hop.body);
		}

		private static ImportHttpResponse respond(URI uri, int status, String contentType, String location,
				String body) {
			byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
			return new ImportHttpResponse(
					status,
					contentType,
					(long) bytes.length,
					false,
					new ByteArrayInputStream(bytes),
					uri,
					location);
		}

		static class Hop {

			final int status;
			final String contentType;
			final String location;
			final String body;
			final TransportException failure;
			byte[] bytes;
			Long declaredLength;

			Hop(int status, String contentType, String location, String body, TransportException failure) {
				this.status = status;
				this.contentType = contentType;
				this.location = location;
				this.body = body;
				this.failure = failure;
			}

			Hop withBytes(byte[] data) {
				this.bytes = data;
				return this;
			}
		}

		static Hop serve(int status, String contentType, String body) {
			return new Hop(status, contentType, null, body, null);
		}

		static Hop redirect(String location) {
			return new Hop(302, null, location, null, null);
		}

		static Hop fail(TransportFailure kind, String message) {
			return new Hop(0, null, null, null, new TransportException(kind, message));
		}
	}

	/** DNS fake: every hostname resolves to a fixed public address unless scripted. */
	static final class FakeDns extends ImportDns {

		final List<String> lookedUp = new ArrayList<>();
		Map<String, InetAddress[]> answers = new LinkedHashMap<>();
		boolean failAll;

		FakeDns() {
			super(hostname -> {
				throw new UnsupportedOperationException("wire answers first");
			});
		}

		void answer(String host, InetAddress... addresses) {
			answers.put(host, addresses);
		}

		void answerDefault(String host) {
			answer(host, publicV4());
		}

		@Override
		public ResolvedTarget resolveHost(URI httpUri) {
			String host = httpUri.getHost();
			lookedUp.add(host);
			if (failAll) {
				throw new ImportNetworkException(FailureKind.DNS_FAILURE, "dns lookup failed");
			}
			InetAddress[] resolved = answers.get(host);
			if (resolved == null) {
				throw new ImportNetworkException(FailureKind.DNS_FAILURE, "dns returned no addresses");
			}
			for (InetAddress address : resolved) {
				if (!isExternallyRoutable(address)) {
					throw new ImportNetworkException(FailureKind.UNSAFE_URL, "hostname resolves to a blocked address");
				}
			}
			return new ResolvedTarget(host, resolved);
		}
	}

	static InetAddress publicV4() {
		try {
			return InetAddress.getByName("93.184.216.34");
		}
		catch (UnknownHostException ex) {
			throw new IllegalStateException(ex);
		}
	}

	static InetAddress address(String literal) {
		try {
			return InetAddress.getByName(literal);
		}
		catch (UnknownHostException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** In-memory media access recording claims and publishes. */
	static final class FakeMediaAccess implements ImportMediaAccess {

		final Map<UploadId, VerifiedImage> verified = new LinkedHashMap<>();
		final List<StorageKey> claimed = new ArrayList<>();
		final List<StorageKey> published = new ArrayList<>();
		final List<String> publishedTypes = new ArrayList<>();
		final List<StorageKey> deleted = new ArrayList<>();
		StorageKey conflictOn;
		boolean unavailable;

		@Override
		public java.util.Optional<VerifiedImage> findVerifiedUpload(UploadId uploadId) {
			if (unavailable) {
				throw new com.aifashionvault.platform.objectstore.ObjectStoreExceptions
					.ObjectStoreUnavailableException("object store is unavailable");
			}
			return java.util.Optional.ofNullable(verified.get(uploadId));
		}

		@Override
		public ClaimOutcome claimVerifiedUpload(VerifiedImage upload, StorageKey destination) {
			if (unavailable) {
				throw new com.aifashionvault.platform.objectstore.ObjectStoreExceptions
					.ObjectStoreUnavailableException("object store is unavailable");
			}
			if (conflictOn != null && conflictOn.value().equals(destination.value())) {
				return ClaimOutcome.CONFLICT;
			}
			claimed.add(destination);
			return ClaimOutcome.CLAIMED;
		}

		@Override
		public boolean publishFetchedImage(InputStream content, long contentLength, StorageKey destination,
				String contentType) {
			if (unavailable) {
				throw new com.aifashionvault.platform.objectstore.ObjectStoreExceptions
					.ObjectStoreUnavailableException("object store is unavailable");
			}
			published.add(destination);
			publishedTypes.add(contentType);
			return true;
		}

		@Override
		public void deleteObject(StorageKey key) {
			deleted.add(key);
		}
	}

	static String keyText(StorageKey key) {
		return key == null ? "" : key.value();
	}

	static boolean containsRetryable(Throwable ex) {
		return ex instanceof ImporterRetryableException;
	}

	static ResolutionReason retryableReason(Throwable ex) {
		return ((ImporterRetryableException) ex).reason();
	}
}
