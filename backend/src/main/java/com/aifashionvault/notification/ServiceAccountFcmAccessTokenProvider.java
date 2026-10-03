package com.aifashionvault.notification;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.function.Supplier;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Refreshable FCM HTTP v1 credential (C20).
 *
 * <p>The guide forbids a pasted short-lived access token in the production path
 * ("a fixed configured access token is not a durable production credential
 * lifecycle"). This provider implements the durable lifecycle instead: it mints a
 * short-lived OAuth2 access token from a Google service-account credential using the
 * standard {@code urn:ietf:params:oauth:grant-type:jwt-bearer} exchange, caches it, and
 * re-mints before expiry. Nothing here is pasted: the long-lived secret is the service
 * account's private key, and the bearer token is derived from it on demand.
 *
 * <p>Deliberately dependency-free: RS256 is signed with {@link Signature} and the
 * exchange uses the JDK {@link HttpClient}, so no JWT/OAuth library is added.
 *
 * <p>Fail-closed by construction. Every failure (unreadable key, non-2xx token
 * endpoint, missing {@code access_token}) throws {@link FcmCredentialException} rather
 * than returning a blank token, and {@link FcmClient} already refuses a blank token, so a
 * broken credential stops delivery instead of sending an unauthenticated request.
 *
 * <p>The credential is read once at construction; only the derived token is refreshed,
 * so rotation of the short-lived value needs no restart.
 */
public final class ServiceAccountFcmAccessTokenProvider implements Supplier<String> {

	/** The single scope FCM HTTP v1 sends need. */
	static final String FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

	private static final String GRANT_TYPE = "urn:ietf:params:oauth:grant-type:jwt-bearer";
	private static final String JWT_ALGORITHM = "RS256";
	private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
	private static final Duration ASSERTION_LIFETIME = Duration.ofMinutes(60);

	/** Re-mint this long before the reported expiry so a token is never used at its edge. */
	private static final Duration EXPIRY_SKEW = Duration.ofSeconds(60);

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final String clientEmail;
	private final PrivateKey privateKey;
	private final URI tokenUri;
	private final HttpClient httpClient;
	private final Clock clock;

	private volatile CachedToken cached;

	public ServiceAccountFcmAccessTokenProvider(
		String clientEmail,
		PrivateKey privateKey,
		URI tokenUri,
		HttpClient httpClient,
		Clock clock
	) {
		this.clientEmail = requireText(clientEmail, "clientEmail");
		this.privateKey = Objects.requireNonNull(privateKey, "privateKey is required");
		this.tokenUri = Objects.requireNonNull(tokenUri, "tokenUri is required");
		this.httpClient = Objects.requireNonNull(httpClient, "httpClient is required");
		this.clock = Objects.requireNonNull(clock, "clock is required");
	}

	/**
	 * Parses a Google service-account JSON document. Only the three fields the exchange
	 * needs are read; the document itself is never logged or retained.
	 */
	public static ServiceAccountFcmAccessTokenProvider fromServiceAccountJson(
		String serviceAccountJson,
		HttpClient httpClient,
		Clock clock
	) {
		if (serviceAccountJson == null || serviceAccountJson.isBlank()) {
			throw new FcmCredentialException("service account JSON is required");
		}
		JsonNode root;
		try {
			root = MAPPER.readTree(serviceAccountJson);
		}
		catch (RuntimeException ex) {
			throw new FcmCredentialException("service account JSON could not be parsed");
		}
		String clientEmail = textField(root, "client_email");
		String privateKeyPem = textField(root, "private_key");
		String tokenUri = textField(root, "token_uri");
		return new ServiceAccountFcmAccessTokenProvider(
			clientEmail,
			parsePrivateKey(privateKeyPem),
			URI.create(tokenUri),
			httpClient,
			clock);
	}

	@Override
	public String get() {
		CachedToken current = this.cached;
		Instant now = this.clock.instant();
		if (current != null && now.isBefore(current.refreshAt())) {
			return current.token();
		}
		synchronized (this) {
			current = this.cached;
			now = this.clock.instant();
			if (current != null && now.isBefore(current.refreshAt())) {
				return current.token();
			}
			CachedToken minted = mint(now);
			this.cached = minted;
			return minted.token();
		}
	}

	private CachedToken mint(Instant now) {
		String assertion = signedAssertion(now);
		String form = "grant_type=" + encode(GRANT_TYPE) + "&assertion=" + encode(assertion);
		HttpRequest request = HttpRequest.newBuilder()
			.uri(this.tokenUri)
			.timeout(Duration.ofSeconds(10))
			.header("Content-Type", "application/x-www-form-urlencoded")
			.POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
			.build();
		HttpResponse<String> response;
		try {
			response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new FcmCredentialException("token exchange was interrupted");
		}
		catch (IOException ex) {
			throw new FcmCredentialException("token endpoint is unreachable");
		}
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			// The provider's error body can echo request material, so only the status is surfaced.
			throw new FcmCredentialException("token endpoint rejected the exchange: HTTP " + response.statusCode());
		}
		JsonNode body;
		try {
			body = MAPPER.readTree(response.body());
		}
		catch (RuntimeException ex) {
			throw new FcmCredentialException("token endpoint returned an unreadable body");
		}
		String token = textField(body, "access_token");
		long expiresIn = body.path("expires_in").asLong(0L);
		if (expiresIn <= 0L) {
			throw new FcmCredentialException("token endpoint returned no usable lifetime");
		}
		Duration lifetime = Duration.ofSeconds(expiresIn);
		Duration refreshIn = lifetime.compareTo(EXPIRY_SKEW) > 0 ? lifetime.minus(EXPIRY_SKEW) : lifetime;
		return new CachedToken(token, now.plus(refreshIn));
	}

	private String signedAssertion(Instant now) {
		String header = base64Url(
			("{\"alg\":\"" + JWT_ALGORITHM + "\",\"typ\":\"JWT\"}").getBytes(StandardCharsets.UTF_8));
		String claims = base64Url((
			"{\"iss\":\"" + escape(this.clientEmail) + "\","
				+ "\"scope\":\"" + FCM_SCOPE + "\","
				+ "\"aud\":\"" + escape(this.tokenUri.toString()) + "\","
				+ "\"iat\":" + now.getEpochSecond() + ","
				+ "\"exp\":" + now.plus(ASSERTION_LIFETIME).getEpochSecond() + "}")
			.getBytes(StandardCharsets.UTF_8));
		String signingInput = header + "." + claims;
		byte[] signature;
		try {
			Signature signer = Signature.getInstance(SIGNATURE_ALGORITHM);
			signer.initSign(this.privateKey);
			signer.update(signingInput.getBytes(StandardCharsets.UTF_8));
			signature = signer.sign();
		}
		catch (java.security.GeneralSecurityException ex) {
			throw new FcmCredentialException("service account key could not sign the assertion");
		}
		return signingInput + "." + base64Url(signature);
	}

	static PrivateKey parsePrivateKey(String pem) {
		if (pem == null || pem.isBlank()) {
			throw new FcmCredentialException("service account private key is required");
		}
		String body = pem
			.replace("-----BEGIN PRIVATE KEY-----", "")
			.replace("-----END PRIVATE KEY-----", "")
			.replaceAll("\\s", "");
		byte[] der;
		try {
			der = Base64.getDecoder().decode(body);
		}
		catch (IllegalArgumentException ex) {
			throw new FcmCredentialException("service account private key is not valid base64");
		}
		try {
			return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
		}
		catch (java.security.GeneralSecurityException ex) {
			throw new FcmCredentialException("service account private key is not a usable PKCS#8 RSA key");
		}
	}

	private static String textField(JsonNode node, String field) {
		JsonNode value = node == null ? null : node.get(field);
		if (value == null || !value.isString() || value.stringValue().isBlank()) {
			throw new FcmCredentialException("service account JSON is missing " + field);
		}
		return value.stringValue().strip();
	}

	private static String requireText(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new FcmCredentialException(name + " is required");
		}
		return value.strip();
	}

	private static String base64Url(byte[] bytes) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static String escape(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	@Override
	public String toString() {
		return "ServiceAccountFcmAccessTokenProvider[]";
	}

	/** A minted token and the instant it stops being used. */
	private record CachedToken(String token, Instant refreshAt) {
	}
}
