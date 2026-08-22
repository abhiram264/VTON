package com.aifashionvault.platform.security;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

public final class InProcessJwtFixture {

	static final String PROJECT_ID = "demo-ai-fashion-vault";
	static final String KID = "in-process-rs256";

	private static final String ISSUER = "https://securetoken.google.com/" + PROJECT_ID;
	private static final String SUBJECT = "in-process-external-subject";
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final KeyPair KEYS = generateRsa();
	private static final ExternalTokenVerifier VERIFIER = new JwtExternalTokenVerifier(PROJECT_ID, KEYS.getPublic());

	private InProcessJwtFixture() {
	}

	public static ExternalTokenVerifier verifier() {
		return VERIFIER;
	}

	public static String bearerToken() {
		return mint(defaultHeader(), validClaims(Instant.now().plusSeconds(3600)), KEYS.getPrivate());
	}

	public static String expiredBearerToken() {
		return mint(defaultHeader(), validClaims(Instant.now().minusSeconds(3600)), KEYS.getPrivate());
	}

	public static String issuer() {
		return ISSUER;
	}

	public static String subject() {
		return SUBJECT;
	}

	static ExternalTokenVerifier verifierWithClock(Clock clock) {
		return new JwtExternalTokenVerifier(PROJECT_ID, KEYS.getPublic(), clock);
	}

	static ExternalTokenVerifier verifierWithJwks(Supplier<String> jwks) {
		return new JwtExternalTokenVerifier(PROJECT_ID, jwks);
	}

	static ExternalTokenVerifier verifierWithJwks(Supplier<String> jwks, Clock clock) {
		return new JwtExternalTokenVerifier(PROJECT_ID, jwks, clock);
	}

	static Map<String, Object> defaultHeader() {
		Map<String, Object> header = new LinkedHashMap<>();
		header.put("alg", "RS256");
		header.put("typ", "JWT");
		header.put("kid", KID);
		return header;
	}

	static Map<String, Object> validClaims(Instant exp) {
		Map<String, Object> claims = new LinkedHashMap<>();
		claims.put("iss", ISSUER);
		claims.put("aud", PROJECT_ID);
		claims.put("sub", SUBJECT);
		claims.put("exp", exp.getEpochSecond());
		claims.put("iat", Instant.now().getEpochSecond());
		claims.put("email", "user@example.com");
		claims.put("user_id", SUBJECT);
		return claims;
	}

	static String mint(Map<String, Object> header, Map<String, Object> claims) {
		return mint(header, claims, KEYS.getPrivate());
	}

	static String mint(Map<String, Object> header, Map<String, Object> claims, PrivateKey key) {
		try {
			String headerB64 = base64Url(MAPPER.writeValueAsBytes(header));
			String payloadB64 = base64Url(MAPPER.writeValueAsBytes(claims));
			String signingInput = headerB64 + "." + payloadB64;
			Signature signature = Signature.getInstance("SHA256withRSA");
			signature.initSign(key);
			signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
			return signingInput + "." + base64Url(signature.sign());
		}
		catch (GeneralSecurityException | JacksonException ex) {
			throw new IllegalStateException(ex);
		}
	}

	static String jwks() {
		RSAPublicKey key = (RSAPublicKey) KEYS.getPublic();
		Map<String, Object> jwk = new LinkedHashMap<>();
		jwk.put("kty", "RSA");
		jwk.put("alg", "RS256");
		jwk.put("use", "sig");
		jwk.put("kid", KID);
		jwk.put("n", base64Url(unsigned(key.getModulus())));
		jwk.put("e", base64Url(unsigned(key.getPublicExponent())));
		return MAPPER.writeValueAsString(Map.of("keys", List.of(jwk)));
	}

	static PublicKey publicKey() {
		return KEYS.getPublic();
	}

	private static KeyPair generateRsa() {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			return generator.generateKeyPair();
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static byte[] unsigned(BigInteger value) {
		byte[] bytes = value.toByteArray();
		if (bytes.length > 1 && bytes[0] == 0) {
			byte[] stripped = new byte[bytes.length - 1];
			System.arraycopy(bytes, 1, stripped, 0, stripped.length);
			return stripped;
		}
		return bytes;
	}

	private static String base64Url(byte[] bytes) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
