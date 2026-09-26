package com.aifashionvault.platform.security;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalTokenVerifierJwksFetchBoundTest {

	/**
	 * A real Firebase JWKS document is a few kilobytes, so anything past this is
	 * an origin misbehaving rather than a key set.
	 */
	private static final int OVERSIZED_BODY_BYTES = 512 * 1024 + 1;

	@Test
	void jwksFetchRefusesAnOriginBodyPastTheSizeCap() throws Exception {
		try (JwksOrigin origin = JwksOrigin.start(OVERSIZED_BODY_BYTES)) {
			assertThatThrownBy(() -> fetch(origin.uri()))
				.isInstanceOf(IllegalStateException.class);
		}
	}

	private static String fetch(URI uri) throws Exception {
		Method fetch = ExternalTokenVerifierConfiguration.LazyJwksDocument.class
			.getDeclaredMethod("fetch", URI.class);
		fetch.setAccessible(true);
		try {
			return (String) fetch.invoke(null, uri);
		}
		catch (InvocationTargetException ex) {
			if (ex.getCause() instanceof Exception cause) {
				throw cause;
			}
			throw ex;
		}
	}

	/** Serves a well-formed 200 response that simply keeps sending. */
	private static final class JwksOrigin implements AutoCloseable {

		private final HttpServer server;
		private final int bodyBytes;

		private JwksOrigin(HttpServer server, int bodyBytes) {
			this.server = server;
			this.bodyBytes = bodyBytes;
		}

		private static JwksOrigin start(int bodyBytes) throws IOException {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			JwksOrigin origin = new JwksOrigin(server, bodyBytes);
			server.createContext("/jwks", origin::serve);
			server.start();
			return origin;
		}

		private URI uri() {
			return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/jwks");
		}

		private void serve(HttpExchange exchange) throws IOException {
			byte[] chunk = new byte[8192];
			Arrays.fill(chunk, (byte) 'a');
			exchange.sendResponseHeaders(200, bodyBytes);
			try (OutputStream body = exchange.getResponseBody()) {
				int remaining = bodyBytes;
				while (remaining > 0) {
					int written = Math.min(chunk.length, remaining);
					body.write(chunk, 0, written);
					remaining -= written;
				}
			}
		}

		@Override
		public void close() {
			server.stop(0);
		}
	}
}
