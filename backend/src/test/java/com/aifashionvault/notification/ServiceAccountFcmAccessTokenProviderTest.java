package com.aifashionvault.notification;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the C20 refreshable credential lifecycle against a local fixture token
 * endpoint: the assertion is a real RS256 JWT-bearer grant, the minted token is cached
 * and re-minted before expiry, and every failure mode is fail-closed. No network egress,
 * no Google call, no pasted token.
 */
class ServiceAccountFcmAccessTokenProviderTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static KeyPair rsaKeyPair() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		return generator.generateKeyPair();
	}

	private static String pem(KeyPair pair) {
		String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
			.encodeToString(pair.getPrivate().getEncoded());
		return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";
	}

	private static String serviceAccountJson(KeyPair pair, String tokenUri) {
		return "{\"type\":\"service_account\",\"project_id\":\"test-project\","
			+ "\"private_key_id\":\"kid-1\",\"client_email\":\"vault@test-project.iam.gserviceaccount.com\","
			+ "\"token_uri\":\"" + tokenUri + "\",\"private_key\":\"" + pem(pair).replace("\n", "\\n") + "\"}";
	}

	@Test
	void mintsAnRs256JwtBearerAssertionAndCachesTheToken() throws Exception {
		KeyPair pair = rsaKeyPair();
		try (TokenEndpoint endpoint = TokenEndpoint.start(200, "{\"access_token\":\"minted-token\",\"expires_in\":3600}")) {
			MutableClock clock = new MutableClock(Instant.parse("2026-05-01T10:00:00Z"));
			ServiceAccountFcmAccessTokenProvider provider = ServiceAccountFcmAccessTokenProvider
				.fromServiceAccountJson(serviceAccountJson(pair, endpoint.tokenUri()), HttpClient.newHttpClient(), clock);

			assertThat(provider.get()).isEqualTo("minted-token");
			// Cached: a second read inside the lifetime must not re-exchange.
			assertThat(provider.get()).isEqualTo("minted-token");
			assertThat(endpoint.requestCount()).isEqualTo(1);

			// The exchange is a real jwt-bearer grant over the standard form encoding.
			String form = endpoint.bodies().getFirst();
			assertThat(form).contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer");
			String assertion = parameter(form, "assertion");

			String[] parts = assertion.split("\\.");
			assertThat(parts).hasSize(3);
			JsonNode header = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[0]));
			assertThat(header.path("alg").stringValue()).isEqualTo("RS256");
			assertThat(header.path("typ").stringValue()).isEqualTo("JWT");

			JsonNode claims = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
			assertThat(claims.path("iss").stringValue())
				.isEqualTo("vault@test-project.iam.gserviceaccount.com");
			assertThat(claims.path("aud").stringValue()).isEqualTo(endpoint.tokenUri());
			assertThat(claims.path("scope").stringValue())
				.isEqualTo("https://www.googleapis.com/auth/firebase.messaging");
			assertThat(claims.path("exp").longValue() - claims.path("iat").longValue()).isEqualTo(3600L);

			// The assertion is genuinely signed by the service-account key.
			Signature verifier = Signature.getInstance("SHA256withRSA");
			verifier.initVerify(pair.getPublic());
			verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
			assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
		}
	}

	@Test
	void reMintsTheTokenOnceTheCachedValueNearsExpiry() throws Exception {
		KeyPair pair = rsaKeyPair();
		try (TokenEndpoint endpoint = TokenEndpoint.start(200, "{\"access_token\":\"first\",\"expires_in\":120}")) {
			MutableClock clock = new MutableClock(Instant.parse("2026-05-01T10:00:00Z"));
			ServiceAccountFcmAccessTokenProvider provider = ServiceAccountFcmAccessTokenProvider
				.fromServiceAccountJson(serviceAccountJson(pair, endpoint.tokenUri()), HttpClient.newHttpClient(), clock);

			assertThat(provider.get()).isEqualTo("first");
			// 120s lifetime minus the 60s skew leaves 60s of use; just inside it is cached.
			clock.set(clock.instant().plus(Duration.ofSeconds(59)));
			assertThat(provider.get()).isEqualTo("first");
			assertThat(endpoint.requestCount()).isEqualTo(1);

			// Past the refresh point the provider exchanges again instead of using a stale token.
			endpoint.script("{\"access_token\":\"second\",\"expires_in\":120}");
			clock.set(clock.instant().plus(Duration.ofSeconds(2)));
			assertThat(provider.get()).isEqualTo("second");
			assertThat(endpoint.requestCount()).isEqualTo(2);
		}
	}

	@Test
	void failsClosedWhenTheTokenEndpointRejectsTheExchange() throws Exception {
		KeyPair pair = rsaKeyPair();
		try (TokenEndpoint endpoint = TokenEndpoint.start(400, "{\"error\":\"invalid_grant\"}")) {
			ServiceAccountFcmAccessTokenProvider provider = ServiceAccountFcmAccessTokenProvider
				.fromServiceAccountJson(
					serviceAccountJson(pair, endpoint.tokenUri()),
					HttpClient.newHttpClient(),
					Clock.systemUTC());

			assertThatExceptionOfType(FcmCredentialException.class)
				.isThrownBy(provider::get)
				.withMessageContaining("HTTP 400")
				// The provider error body may echo request material, so it must not leak.
				.withMessageNotContaining("invalid_grant");
		}
	}

	@Test
	void failsClosedWhenTheTokenEndpointIsUnreachable() throws Exception {
		KeyPair pair = rsaKeyPair();
		// Port 1 on loopback has no listener: the exchange cannot complete.
		ServiceAccountFcmAccessTokenProvider provider = ServiceAccountFcmAccessTokenProvider
			.fromServiceAccountJson(
				serviceAccountJson(pair, "http://127.0.0.1:1/token"),
				HttpClient.newHttpClient(),
				Clock.systemUTC());

		assertThatExceptionOfType(FcmCredentialException.class)
			.isThrownBy(provider::get)
			.withMessageContaining("unreachable");
	}

	@Test
	void failsClosedWhenTheTokenResponseCarriesNoUsableLifetime() throws Exception {
		KeyPair pair = rsaKeyPair();
		try (TokenEndpoint endpoint = TokenEndpoint.start(200, "{\"access_token\":\"t\"}")) {
			ServiceAccountFcmAccessTokenProvider provider = ServiceAccountFcmAccessTokenProvider
				.fromServiceAccountJson(
					serviceAccountJson(pair, endpoint.tokenUri()),
					HttpClient.newHttpClient(),
					Clock.systemUTC());

			assertThatExceptionOfType(FcmCredentialException.class)
				.isThrownBy(provider::get)
				.withMessageContaining("no usable lifetime");
		}
	}

	@Test
	void rejectsAnIncompleteOrUnusableServiceAccountDocument() throws Exception {
		KeyPair pair = rsaKeyPair();
		HttpClient client = HttpClient.newHttpClient();
		Clock clock = Clock.systemUTC();

		assertThatThrownBy(() -> ServiceAccountFcmAccessTokenProvider.fromServiceAccountJson("", client, clock))
			.isInstanceOf(FcmCredentialException.class);
		assertThatThrownBy(() -> ServiceAccountFcmAccessTokenProvider.fromServiceAccountJson("not json", client, clock))
			.isInstanceOf(FcmCredentialException.class)
			.hasMessageContaining("could not be parsed");
		assertThatThrownBy(() -> ServiceAccountFcmAccessTokenProvider.fromServiceAccountJson(
			"{\"token_uri\":\"http://127.0.0.1:1/token\",\"private_key\":\"x\"}", client, clock))
			.isInstanceOf(FcmCredentialException.class)
			.hasMessageContaining("client_email");
		assertThatThrownBy(() -> ServiceAccountFcmAccessTokenProvider.fromServiceAccountJson(
			"{\"client_email\":\"a@b\",\"token_uri\":\"http://127.0.0.1:1/token\",\"private_key\":\"not-a-pem\"}",
			client, clock))
			.isInstanceOf(FcmCredentialException.class)
			.hasMessageContaining("base64");

		// A public key is not a private key, so a wrong-material PEM is refused.
		String publicPem = "-----BEGIN PRIVATE KEY-----\n"
			+ Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
				.encodeToString(pair.getPublic().getEncoded())
			+ "\n-----END PRIVATE KEY-----\n";
		assertThatThrownBy(() -> ServiceAccountFcmAccessTokenProvider.parsePrivateKey(publicPem))
			.isInstanceOf(FcmCredentialException.class);
	}

	@Test
	void toStringNeverDisclosesKeyMaterial() throws Exception {
		KeyPair pair = rsaKeyPair();
		ServiceAccountFcmAccessTokenProvider provider = ServiceAccountFcmAccessTokenProvider
			.fromServiceAccountJson(
				serviceAccountJson(pair, "http://127.0.0.1:1/token"),
				HttpClient.newHttpClient(),
				Clock.systemUTC());

		assertThat(provider.toString()).isEqualTo("ServiceAccountFcmAccessTokenProvider[]");
	}

	private static String parameter(String form, String name) {
		for (String pair : form.split("&")) {
			int eq = pair.indexOf('=');
			if (eq > 0 && pair.substring(0, eq).equals(name)) {
				return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
			}
		}
		throw new AssertionError("missing form parameter " + name);
	}

	/** Mutable clock so the caching window can be crossed without waiting. */
	static final class MutableClock extends Clock {

		private volatile Instant instant;

		private MutableClock(Instant instant) {
			this.instant = instant;
		}

		private void set(Instant instant) {
			this.instant = instant;
		}

		@Override
		public ZoneOffset getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return Clock.fixed(this.instant, zone);
		}

		@Override
		public Instant instant() {
			return this.instant;
		}
	}

	/** Local fixture OAuth token endpoint. Never contacts a real provider. */
	static final class TokenEndpoint implements AutoCloseable {

		private final HttpServer server;
		private final List<String> bodies = new ArrayList<>();
		private final AtomicInteger requests = new AtomicInteger();
		private volatile String responseBody;
		private volatile int responseStatus;

		private TokenEndpoint(HttpServer server, int status, String body) {
			this.server = server;
			this.responseStatus = status;
			this.responseBody = body;
		}

		static TokenEndpoint start(int status, String body) {
			try {
				HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
				TokenEndpoint endpoint = new TokenEndpoint(server, status, body);
				server.createContext("/token", endpoint::handle);
				server.start();
				return endpoint;
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		}

		private void script(String body) {
			this.responseStatus = 200;
			this.responseBody = body;
		}

		private String tokenUri() {
			return "http://127.0.0.1:" + this.server.getAddress().getPort() + "/token";
		}

		private int requestCount() {
			return this.requests.get();
		}

		private List<String> bodies() {
			return this.bodies;
		}

		private void handle(HttpExchange exchange) throws IOException {
			this.bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			this.requests.incrementAndGet();
			byte[] body = this.responseBody.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(this.responseStatus, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		}

		@Override
		public void close() {
			this.server.stop(0);
		}
	}
}
