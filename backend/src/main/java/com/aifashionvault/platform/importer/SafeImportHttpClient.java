package com.aifashionvault.platform.importer;

import java.io.ByteArrayInputStream;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import com.aifashionvault.importer.ImportResolverLimits;

/**
 * Dedicated SSRF-hardened HTTP client for share-import resolution only. Never
 * shared with other outbound clients: every request re-runs admission policy
 * and DNS all-record address validation, and per-hop timeouts are frozen at
 * the import limits. Redirects are disabled here; the resolver follows them
 * manually so every hop is revalidated.
 *
 * DNS-rebinding defense: the connection is dialed directly to one of the
 * validated {@link InetAddress}es from {@link ImportDns#resolveHost(URI)} —
 * never to a hostname — so no second, unvalidated resolution can occur at
 * connect time. TLS (when used) verifies the certificate against the ORIGINAL
 * hostname and sends that hostname as SNI; the Host header carries the
 * original authority.
 */
public final class SafeImportHttpClient implements AutoCloseable, ImportHttpFetcher {

	private final ImportDns dns;
	private final ConnectionFactory connections;

	/**
	 * Project safety policy: caps on origin-supplied header bytes. A single
	 * header line (or chunk-size line) longer than {@code MAX_HEADER_LINE_BYTES},
	 * or a header block larger than {@code MAX_TOTAL_HEADER_BYTES}, is a policy
	 * failure — the origin is malicious or unusable. Without these caps an
	 * origin streaming an unbounded header line (every byte arriving within the
	 * per-read timeout) grows the read buffer without limit.
	 */
	private static final int MAX_HEADER_LINE_BYTES = 8 * 1024;
	private static final int MAX_TOTAL_HEADER_BYTES = 64 * 1024;

	public SafeImportHttpClient() {
		this(new ImportDns());
	}

	public SafeImportHttpClient(ImportDns dns) {
		this(dns, new SocketConnectionFactory());
	}

	SafeImportHttpClient(ImportDns dns, ConnectionFactory connections) {
		this.dns = dns;
		this.connections = connections;
	}

	/**
	 * Single validated GET with no redirect following. The body is buffered
	 * with a hard cap; a body larger than {@code maxBytes} sets
	 * {@code truncated} instead of being read in full.
	 */
	@Override
	public ImportHttpResponse get(URI target, long maxBytes) throws IOException {
		return get(target, maxBytes, null);
	}

	/**
	 * Deadline-aware variant: when {@code deadline} is non-null, every read —
	 * status line, headers, and body — is guarded so a slow-trickle origin can
	 * never hold the calling thread past the total resolution budget. The
	 * per-read socket timeout alone does not bound the total: each delivered
	 * byte resets it.
	 */
	@Override
	public ImportHttpResponse get(URI target, long maxBytes, Instant deadline) throws IOException {
		SafeHttpUri.requireSafe(target);
		// Resolve + validate every A/AAAA record once; the dial below pins to
		// one of these addresses so a short-TTL rebind between check and
		// connect is impossible. The lookup is budget-bound: the budgeted
		// overload, never the unbounded one, so a black-holing resolver
		// cannot park this thread past the total resolution deadline.
		ImportDns.ResolvedTarget resolved = dns.resolveHost(target, dnsBudgetNanos(deadline));
		InetAddress pinned = resolved.addresses()[0];
		String host = target.getHost();
		int port = SafeHttpUri.effectivePort(target);
		boolean tls = target.getScheme().equalsIgnoreCase("https");

		try (Connection connection = connections.open(pinned, port, host, tls,
				ImportResolverLimits.CONNECT_TIMEOUT, ImportResolverLimits.READ_TIMEOUT)) {
			writeRequest(connection, target);
			InputStream raw = deadline == null ? connection.input()
					: new DeadlineGuardedInputStream(connection.input(), deadline);
			return readResponse(new BufferedInputStream(raw), target, maxBytes);
		}
		catch (java.net.SocketTimeoutException ex) {
			throw new ImportHttpFetcher.TransportException(ImportHttpFetcher.TransportFailure.TIMEOUT,
					ex.getMessage() != null && ex.getMessage().contains("connect") ? "connect timeout" : "read timeout");
		}
		catch (java.net.ConnectException | java.net.NoRouteToHostException ex) {
			throw new ImportHttpFetcher.TransportException(ImportHttpFetcher.TransportFailure.TRANSPORT,
					"transport failure");
		}
		catch (UnknownHostException ex) {
			// unreachable when pinning, kept for defense in depth
			throw new ImportHttpFetcher.TransportException(ImportHttpFetcher.TransportFailure.DNS,
					"dns lookup failed");
		}
		catch (IOException ex) {
			if (ex instanceof javax.net.ssl.SSLException) {
				throw new ImportHttpFetcher.TransportException(ImportHttpFetcher.TransportFailure.TRANSPORT,
						"tls failure");
			}
			if (ex instanceof ImportHttpFetcher.TransportException transported) {
				throw transported;
			}
			throw new ImportHttpFetcher.TransportException(classifyIo(ex), "transport failure");
		}
	}
	/**
	 * Remaining resolution budget for the point-of-use DNS lookup. The caller's
	 * deadline wins, clamped to the project ceiling; a caller that supplies no
	 * deadline still gets a bound rather than an unbounded native getaddrinfo.
	 */
	private static long dnsBudgetNanos(Instant deadline) {
		long ceiling = ImportResolverLimits.TOTAL_RESOLUTION_TIMEOUT.toNanos();
		return deadline == null ? ceiling : Math.min(ceiling, Duration.between(Instant.now(), deadline).toNanos());
	}

	private static void writeRequest(Connection connection, URI target) throws IOException {
		String authority = target.getPort() == -1 ? target.getHost()
				: target.getHost() + ":" + target.getPort();
		String request = "GET " + requestTarget(target) + " HTTP/1.1\r\n"
				+ "Host: " + authority + "\r\n"
				+ "User-Agent: aifashionvault-import/1.0\r\n"
				+ "Accept: */*\r\n"
				+ "Connection: close\r\n"
				+ "\r\n";
		connection.output().write(request.getBytes(StandardCharsets.US_ASCII));
		connection.output().flush();
	}

	private static String requestTarget(URI target) {
		String raw = target.getRawPath();
		if (raw == null || raw.isEmpty()) {
			raw = "/";
		}
		String query = target.getRawQuery();
		return query == null ? raw : raw + "?" + query;
	}

	private ImportHttpResponse readResponse(InputStream raw, URI target, long maxBytes) throws IOException {
		String statusLine = readLine(raw);
		if (statusLine == null || !statusLine.startsWith("HTTP/")) {
			throw new ImportHttpFetcher.TransportException(ImportHttpFetcher.TransportFailure.TRANSPORT,
					"malformed status line");
		}
		int statusCode = parseStatusCode(statusLine);
		if (statusCode < 0) {
			throw new ImportHttpFetcher.TransportException(ImportHttpFetcher.TransportFailure.TRANSPORT,
					"malformed status code");
		}
		String contentType = null;
		Long contentLength = null;
		String location = null;
		boolean chunked = false;
		int headerBytes = 0;
		String line;
		while ((line = readLine(raw)) != null && !line.isEmpty()) {
			headerBytes += line.length() + 2;
			if (headerBytes > MAX_TOTAL_HEADER_BYTES) {
				throw new ImportHttpFetcher.TransportException(ImportHttpFetcher.TransportFailure.TRANSPORT,
						"header block exceeds the size cap");
			}
			int colon = line.indexOf(':');
			if (colon <= 0) {
				continue;
			}
			String name = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
			String value = line.substring(colon + 1).strip();
			switch (name) {
				case "content-type" -> contentType = value;
				case "content-length" -> {
					try {
						contentLength = Long.parseLong(value);
					}
					catch (NumberFormatException ignored) {
						contentLength = null;
					}
				}
				case "location" -> location = value;
				case "transfer-encoding" -> chunked = value.toLowerCase(Locale.ROOT).contains("chunked");
				default -> {
				}
			}
		}
		if (contentLength != null && contentLength > maxBytes) {
			// oversized by declaration: do not stream the body
			return new ImportHttpResponse(statusCode, contentType, contentLength, true,
					new ByteArrayInputStream(new byte[0]), target, location);
		}
		InputStream bodyStream = chunked ? new ChunkedInputStream(raw) : raw;
		BoundedBody body = readBounded(bodyStream, maxBytes);
		boolean truncated = body.truncated() || (contentLength != null && contentLength > maxBytes);
		return new ImportHttpResponse(statusCode, contentType, contentLength, truncated, body.stream(), target,
				location);
	}

	/**
	 * Reads one header line byte-wise without read-ahead so the first body
	 * byte is never consumed by header parsing. Capped at
	 * {@link #MAX_HEADER_LINE_BYTES}.
	 */
	private static String readLine(InputStream in) throws IOException {
		StringBuilder sb = new StringBuilder();
		int c = in.read();
		if (c == -1) {
			return null;
		}
		while (c != -1 && c != '\n') {
			if (c != '\r') {
				sb.append((char) c);
				if (sb.length() > MAX_HEADER_LINE_BYTES) {
					throw new ImportHttpFetcher.TransportException(ImportHttpFetcher.TransportFailure.TRANSPORT,
							"header line exceeds the size cap");
				}
			}
			c = in.read();
		}
		return sb.toString();
	}

	private static int parseStatusCode(String statusLine) {
		String[] parts = statusLine.split("\\s+");
		if (parts.length < 2) {
			return -1;
		}
		try {
			return Integer.parseInt(parts[1]);
		}
		catch (NumberFormatException ex) {
			return -1;
		}
	}

	@Override
	public void close() {
		// connections are per-request and closed by get(); nothing to close
	}

	private static ImportHttpFetcher.TransportFailure classifyIo(IOException ex) {
		if (ex instanceof UnknownHostException) {
			return ImportHttpFetcher.TransportFailure.DNS;
		}
		return ImportHttpFetcher.TransportFailure.TRANSPORT;
	}

	private static BoundedBody readBounded(InputStream raw, long maxBytes) throws IOException {
		if (raw == null) {
			return new BoundedBody(new ByteArrayInputStream(new byte[0]), false);
		}
		byte[] data = new byte[(int) Math.min(maxBytes, Integer.MAX_VALUE)];
		int used = 0;
		boolean truncated = false;
		try (raw) {
			byte[] buffer = new byte[8192];
			int read;
			while ((read = raw.read(buffer)) != -1) {
				int take = Math.min(data.length - used, read);
				System.arraycopy(buffer, 0, data, used, take);
				used += take;
				if (take < read) {
					truncated = true;
					break;
				}
			}
		}
		return new BoundedBody(new ByteArrayInputStream(data, 0, used), truncated);
	}

	/**
	 * Fails with a timeout classification once the total-budget deadline has
	 * passed, before any further blocking read. The read timeout on the socket
	 * resets per delivered byte, so only this guard bounds a slow-trickle
	 * origin against the resolution deadline.
	 */
	private static final class DeadlineGuardedInputStream extends InputStream {

		private final InputStream delegate;
		private final Instant deadline;

		DeadlineGuardedInputStream(InputStream delegate, Instant deadline) {
			this.delegate = delegate;
			this.deadline = deadline;
		}

		@Override
		public int read() throws IOException {
			if (Instant.now().isAfter(deadline)) {
				throw new ImportHttpFetcher.TransportException(ImportHttpFetcher.TransportFailure.TIMEOUT,
						"total resolution budget exhausted");
			}
			return delegate.read();
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			if (Instant.now().isAfter(deadline)) {
				throw new ImportHttpFetcher.TransportException(ImportHttpFetcher.TransportFailure.TIMEOUT,
						"total resolution budget exhausted");
			}
			return delegate.read(b, off, len);
		}
	}

	/** Minimal HTTP/1.1 chunked decoding for the bounded body reader. */
	private static final class ChunkedInputStream extends InputStream {

		private final InputStream in;
		private int remaining;
		private boolean done;

		ChunkedInputStream(InputStream in) {
			this.in = in;
		}

		@Override
		public int read() throws IOException {
			byte[] one = new byte[1];
			int n = read(one, 0, 1);
			return n == -1 ? -1 : one[0] & 0xFF;
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			if (done) {
				return -1;
			}
			if (remaining == 0) {
				String size = readChunkHeader();
				if (size == null || size.isBlank()) {
					done = true;
					return -1;
				}
				try {
					remaining = Integer.parseInt(size.split(";", 2)[0].strip(), 16);
				}
				catch (NumberFormatException ex) {
					throw new IOException("malformed chunk size");
				}
				if (remaining < 0) {
					throw new IOException("malformed chunk size");
				}
				if (remaining == 0) {
					done = true;
					return -1;
				}
			}
			int n = in.read(b, off, Math.min(len, remaining));
			if (n == -1) {
				done = true;
				return -1;
			}
			remaining -= n;
			if (remaining == 0) {
				consumeCrlf();
			}
			return n;
		}

		private String readChunkHeader() throws IOException {
			StringBuilder sb = new StringBuilder();
			int c;
			while ((c = in.read()) != -1) {
				if (c == '\n') {
					break;
				}
				if (c != '\r') {
					sb.append((char) c);
					if (sb.length() > MAX_HEADER_LINE_BYTES) {
						throw new IOException("chunk header exceeds the size cap");
					}
				}
			}
			return sb.length() == 0 && c == -1 ? null : sb.toString();
		}

		private void consumeCrlf() throws IOException {
			int first = in.read();
			if (first == '\r') {
				in.read();
			}
		}
	}

	private record BoundedBody(InputStream stream, boolean truncated) {
	}

	/** One opened, buffered connection dialed to a pinned validated address. */
	interface Connection extends AutoCloseable {

		InputStream input() throws IOException;

		java.io.OutputStream output() throws IOException;

		@Override
		void close();
	}

	/**
	 * Seam for tests: produces connections dialed to the exact validated
	 * address. Production dials raw sockets (TLS with SNI + hostname
	 * verification against the original host when https).
	 */
	interface ConnectionFactory {

		Connection open(InetAddress address, int port, String host, boolean tls, Duration connectTimeout,
				Duration readTimeout) throws IOException;
	}

	private static final class SocketConnectionFactory implements ConnectionFactory {

		@Override
		public Connection open(InetAddress address, int port, String host, boolean tls, Duration connectTimeout,
				Duration readTimeout) throws IOException {
			Socket socket = new Socket();
			try {
				socket.connect(new InetSocketAddress(address, port), (int) connectTimeout.toMillis());
				socket.setSoTimeout((int) readTimeout.toMillis());
				if (tls) {
					SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
					SSLSocket sslSocket = (SSLSocket) factory.createSocket(socket, host, port, true);
					SSLParameters params = sslSocket.getSSLParameters();
					params.setServerNames(List.of(new javax.net.ssl.SNIHostName(host)));
					// verify the certificate against the ORIGINAL hostname, not the dialed IP
					params.setEndpointIdentificationAlgorithm("HTTPS");
					sslSocket.setSSLParameters(params);
					sslSocket.startHandshake();
					return new SocketConnection(sslSocket);
				}
				return new SocketConnection(socket);
			}
			catch (IOException | RuntimeException ex) {
				try {
					socket.close();
				}
				catch (IOException ignored) {
				}
				throw ex;
			}
		}
	}

	private record SocketConnection(Socket socket) implements Connection {

		@Override
		public InputStream input() throws IOException {
			return socket.getInputStream();
		}

		@Override
		public java.io.OutputStream output() throws IOException {
			return socket.getOutputStream();
		}

		@Override
		public void close() {
			try {
				socket.close();
			}
			catch (IOException ignored) {
			}
		}
	}
}
