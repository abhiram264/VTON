package com.aifashionvault.platform.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * DNS-rebinding (TOCTOU) coverage for the safe import client. The fake DNS
 * validates one answer and the fake connection layer records the exact
 * address dialed; a rebind attack is simulated by having the resolver return
 * a DIFFERENT (private) answer on any second lookup — the dial must still use
 * the first validated public address and must never re-resolve.
 */
class SafeImportHttpClientTest {

	private static final String HOST = "shop.example.test";
	private static final URI TARGET = URI.create("https://" + HOST + "/item");
	/** Resolution budget handed to the transport; the fake resolver stalls far longer. */
	private static final long BUDGET_MILLIS = 250;

	private final FakeDns dns = new FakeDns();
	private final FakeConnections connections = new FakeConnections();

	private SafeImportHttpClient client() {
		return new SafeImportHttpClient(dns, connections);
	}

	// --- Defect 1: connection must be pinned to the validated address ---

	@Test
	void validationPassDialsTheValidatedAddressWithOriginalHostHeader() throws Exception {
		dns.answer = Fakes.publicV4();
		byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
		connections.respondWith("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 5\r\n\r\n" + "hello");

		var response = client().get(TARGET, 1024);

		assertThat(connections.dialed).hasSize(1);
		assertThat(connections.dialed.get(0).address()).isEqualTo(Fakes.publicV4());
		assertThat(connections.dialed.get(0).host()).isEqualTo(HOST);
		assertThat(connections.dialed.get(0).tls()).isTrue();
		assertThat(connections.dialed.get(0).port()).isEqualTo(443);
		assertThat(dns.lookups).isEqualTo(1);
		String request = connections.dialed.get(0).requestWritten();
		assertThat(request).startsWith("GET /item HTTP/1.1\r\n");
		assertThat(request).contains("Host: " + HOST + "\r\n");
		assertThat(request).doesNotContain("93.184.216.34");
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.contentType()).isEqualTo("text/plain");
		assertThat(response.contentLength()).isEqualTo(5L);
		assertThat(new String(response.body().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello");
		assertThat(response.truncated()).isFalse();
	}

	@Test
	void rebindAttemptDialsValidatedAddressNeverTheReResolvedOne() throws Exception {
		// First lookup (validation) returns a public address; any later
		// lookup — the rebind — returns a private address. The dial must use
		// the validated public address and never re-resolve.
		dns.answer = Fakes.publicV4();
		dns.reboundAnswer = Fakes.address("169.254.169.254");
		connections.respondWith("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n");

		client().get(TARGET, 1024);

		assertThat(dns.lookups).isEqualTo(1); // no second resolution ever happens
		assertThat(connections.dialed).hasSize(1);
		assertThat(connections.dialed.get(0).address()).isEqualTo(Fakes.publicV4());
		assertThat(connections.dialed.get(0).address().getHostAddress())
			.isNotEqualTo("169.254.169.254");
	}

	@Test
	void unsafeValidationBlocksBeforeAnyDial() {
		dns.answer = Fakes.address("10.0.0.9");

		assertThatThrownBy(() -> client().get(TARGET, 1024)).isInstanceOf(ImportNetworkException.class);
		assertThat(connections.dialed).isEmpty();
		assertThat(dns.lookups).isEqualTo(1);
	}

	@Test
	void httpTargetDialsPort80WithoutTls() throws Exception {
		dns.answer = Fakes.publicV4();
		connections.respondWith("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n");

		client().get(URI.create("http://" + HOST + "/x"), 1024);

		assertThat(connections.dialed.get(0).tls()).isFalse();
		assertThat(connections.dialed.get(0).port()).isEqualTo(80);
	}

	@Test
	void oversizedDeclaredContentLengthIsTruncatedWithoutReadingTheBody() throws Exception {
		dns.answer = Fakes.publicV4();
		connections.respondWith("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: 999999\r\n\r\nAAAA");

		var response = client().get(TARGET, 10);

		assertThat(response.truncated()).isTrue();
		assertThat(response.contentLength()).isEqualTo(999999L);
	}

	// --- Hardening: origin-supplied header byte caps ---

	@Test
	void oversizedSingleHeaderLineIsTransportFailureNotUnboundedRead() {
		dns.answer = Fakes.publicV4();
		// one header line far past the 8 KiB per-line policy cap
		connections.respondWith("HTTP/1.1 200 OK\r\nX-Long: " + "A".repeat(64 * 1024) + "\r\n\r\n");

		assertThatThrownBy(() -> client().get(TARGET, 1024))
			.isInstanceOf(ImportHttpFetcher.TransportException.class)
			.extracting(ex -> ((ImportHttpFetcher.TransportException) ex).failure())
			.isEqualTo(ImportHttpFetcher.TransportFailure.TRANSPORT);
	}

	@Test
	void endlessSmallHeadersExceedTheTotalHeaderCap() {
		dns.answer = Fakes.publicV4();
		// many small lines, each under the per-line cap, past the 64 KiB block cap
		StringBuilder raw = new StringBuilder("HTTP/1.1 200 OK\r\n");
		for (int i = 0; i < 2400; i++) {
			raw.append("X-Pad-").append(i).append(": 0123456789abcdef\r\n");
		}
		raw.append("\r\n");
		connections.respondWith(raw.toString());

		assertThatThrownBy(() -> client().get(TARGET, 1024))
			.isInstanceOf(ImportHttpFetcher.TransportException.class)
			.extracting(ex -> ((ImportHttpFetcher.TransportException) ex).failure())
			.isEqualTo(ImportHttpFetcher.TransportFailure.TRANSPORT);
	}

	// --- Hardening: total-budget deadline enforced mid-fetch ---

	@Test
	void expiredDeadlineAbortsTheFetchMidReadAsTimeout() {
		dns.answer = Fakes.publicV4();
		// a fully valid response is available, but the origin stalls past the
		// deadline: DNS is instant so the budget gate passes, and only the
		// read guard can abort this fetch — with the budget checked only
		// between hops it would succeed
		connections.respondWith("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello");
		connections.stallBodyBeforeRead(BUDGET_MILLIS * 4);

		assertThatThrownBy(() -> client().get(TARGET, 1024, java.time.Instant.now().plusMillis(BUDGET_MILLIS)))
			.isInstanceOf(ImportHttpFetcher.TransportException.class)
			.extracting(ex -> ((ImportHttpFetcher.TransportException) ex).failure())
			.isEqualTo(ImportHttpFetcher.TransportFailure.TIMEOUT);
	}

	// --- Point-of-use DNS must run under the total resolution budget ---

	/**
	 * The transport resolves DNS itself, so bounding that resolution is the
	 * client's own responsibility. With the unbounded 1-arg lookup the calling
	 * thread is held for the resolver's entire stall and the fetch continues
	 * past the deadline before the read guard notices. The point-of-use lookup
	 * must instead abandon a stalled resolver as TIMEOUT, well inside the
	 * budget, and never reach a dial.
	 */
	@Test
	void stalledResolverIsAbortedInsideTheResolutionBudget() {
		BlockingDns blocking = new BlockingDns();
		SafeImportHttpClient client = new SafeImportHttpClient(blocking, connections);

		long startedAt = System.nanoTime();
		assertThatThrownBy(() -> client.get(TARGET, 1024, java.time.Instant.now().plusMillis(BUDGET_MILLIS)))
			.isInstanceOf(ImportNetworkException.class)
			.extracting(ex -> ((ImportNetworkException) ex).kind())
			.isEqualTo(FailureKind.TIMEOUT);
		long heldMillis = (System.nanoTime() - startedAt) / 1_000_000;

		// an unbounded lookup holds the caller for the whole stall; the budget
		// must abort far inside it (13x headroom keeps this off the CI knife edge)
		assertThat(heldMillis).isLessThan(BlockingDns.STALL_MILLIS / 2);
		assertThat(connections.dialed).isEmpty();
	}

	/**
	 * A resolver that stalls well past any resolution budget before answering —
	 * the black-holing resolver case. The unbounded 1-arg lookup would simply
	 * block the calling thread for the full stall and then fetch anyway; the
	 * budgeted overload must abandon it as TIMEOUT before any dial.
	 */
	private static final class BlockingDns extends ImportDns {

		private static final long STALL_MILLIS = 3_000;

		BlockingDns() {
			super(hostname -> {
				stall();
				return new InetAddress[] {Fakes.publicV4()};
			});
		}

		@Override
		public ResolvedTarget resolveHost(URI httpUri) {
			stall();
			return new ResolvedTarget(httpUri.getHost(), new InetAddress[] {Fakes.publicV4()});
		}

		private static void stall() {
			try {
				Thread.sleep(STALL_MILLIS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}
	}


	@Test
	void connectionRefusedIsTransportFailure() {
		dns.answer = Fakes.publicV4();
		connections.failWith(new java.net.ConnectException("refused"));

		assertThatThrownBy(() -> client().get(TARGET, 1024))
			.isInstanceOf(ImportHttpFetcher.TransportException.class)
			.extracting(ex -> ((ImportHttpFetcher.TransportException) ex).failure())
			.isEqualTo(ImportHttpFetcher.TransportFailure.TRANSPORT);
	}

	/** DNS fake with a distinct second answer to simulate rebinding. */
	private static final class FakeDns extends ImportDns {

		InetAddress answer;
		InetAddress reboundAnswer;
		int lookups;

		FakeDns() {
			super(hostname -> {
				throw new UnsupportedOperationException("not used");
			});
		}

		@Override
		public ResolvedTarget resolveHost(URI httpUri) {
			lookups++;
			String host = httpUri.getHost();
			if (answer == null) {
				throw new ImportNetworkException(FailureKind.DNS_FAILURE, "dns lookup failed");
			}
			InetAddress chosen = lookups > 1 && reboundAnswer != null ? reboundAnswer : answer;
			if (!isExternallyRoutable(chosen)) {
				throw new ImportNetworkException(FailureKind.UNSAFE_URL, "hostname resolves to a blocked address");
			}
			return new ResolvedTarget(host, new InetAddress[] {chosen});
		}

		/**
		 * The client resolves DNS itself, so it calls this budgeted overload;
		 * the fake must answer it or every stubbed fetch would hit the real
		 * resolver. Same answer semantics as the unbounded overload.
		 */
		@Override
		ResolvedTarget resolveHost(URI httpUri, long timeoutNanos) {
			if (timeoutNanos <= 0) {
				throw new ImportNetworkException(FailureKind.TIMEOUT, "dns budget exhausted");
			}
			return resolveHost(httpUri);
		}
	}

	/** Connection seam recording every dial; serves a canned raw response. */
	private static final class FakeConnections implements SafeImportHttpClient.ConnectionFactory {

		final List<Recording> dialed = new ArrayList<>();
		private byte[] cannedResponse = new byte[0];
		private IOException failure;
		private long bodyStallMillis;

		void respondWith(String rawResponse) {
			this.cannedResponse = rawResponse.getBytes(StandardCharsets.ISO_8859_1);
		}

		void failWith(IOException failure) {
			this.failure = failure;
		}

		/** Makes the origin stall past the deadline before any byte is served. */
		void stallBodyBeforeRead(long millis) {
			this.bodyStallMillis = millis;
		}

		@Override
		public SafeImportHttpClient.Connection open(InetAddress address, int port, String host, boolean tls, Duration connectTimeout,
				Duration readTimeout) throws IOException {
			if (failure != null) {
				throw failure;
			}
			Recording recording = new Recording(address, port, host, tls, connectTimeout, readTimeout);
			dialed.add(recording);
			return new FakeConnection(recording, new StallingInputStream(cannedResponse, bodyStallMillis));
		}
	}
	private static final class Recording {

		private final InetAddress address;
		private final int port;
		private final String host;
		private final boolean tls;
		private final Duration connectTimeout;
		private final Duration readTimeout;
		private final ByteArrayOutputStream written = new ByteArrayOutputStream();

		Recording(InetAddress address, int port, String host, boolean tls, Duration connectTimeout,
				Duration readTimeout) {
			this.address = address;
			this.port = port;
			this.host = host;
			this.tls = tls;
			this.connectTimeout = connectTimeout;
			this.readTimeout = readTimeout;
		}

		InetAddress address() {
			return address;
		}

		int port() {
			return port;
		}

		String host() {
			return host;
		}

		boolean tls() {
			return tls;
		}

		Duration connectTimeout() {
			return connectTimeout;
		}

		Duration readTimeout() {
			return readTimeout;
		}

		String requestWritten() {
			return written.toString(StandardCharsets.US_ASCII);
		}
	}

	private static final class FakeConnection implements SafeImportHttpClient.Connection {

		private final Recording recording;
		private final InputStream input;
		private boolean closed;

		FakeConnection(Recording recording, InputStream input) {
			this.recording = recording;
			this.input = input;
		}

		@Override
		public InputStream input() {
			return new InputStream() {
				@Override
				public int read() throws IOException {
					return input.read();
				}
			};
		}

		@Override
		public OutputStream output() {
			return new OutputStream() {
				@Override
				public void write(int b) {
					recording.written.write(b);
				}
			};
		}

		@Override
		public void close() {
			closed = true;
		}
	}

	/**
	 * Origin body that stalls before serving its first byte, standing in for a
	 * slow-trickling origin. Only the client's read guard can end such a fetch:
	 * nothing in the transport ever gives up on the socket itself.
	 */
	private static final class StallingInputStream extends InputStream {

		private final InputStream delegate;
		private final long stallMillis;
		private boolean stalled;

		StallingInputStream(byte[] cannedResponse, long stallMillis) {
			this.delegate = new ByteArrayInputStream(cannedResponse);
			this.stallMillis = stallMillis;
		}

		@Override
		public int read() throws IOException {
			if (!stalled) {
				stalled = true;
				try {
					Thread.sleep(stallMillis);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
			}
			return delegate.read();
		}
	}
}
