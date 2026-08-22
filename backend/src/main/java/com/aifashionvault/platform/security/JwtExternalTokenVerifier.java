package com.aifashionvault.platform.security;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class JwtExternalTokenVerifier implements ExternalTokenVerifier {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String RS256 = "RS256";

	private final String expectedIssuer;
	private final String expectedAudience;
	private final PublicKey publicKey;
	private final Supplier<String> jwks;
	private final Clock clock;
	private volatile Map<String, PublicKey> jwksKeys;

	public JwtExternalTokenVerifier(String projectId, PublicKey publicKey) {
		this(projectId, publicKey, Clock.systemUTC());
	}

	public JwtExternalTokenVerifier(String projectId, PublicKey publicKey, Clock clock) {
		this(projectId, requirePublicKey(publicKey), null, clock);
	}

	public JwtExternalTokenVerifier(String projectId, Supplier<String> jwks) {
		this(projectId, jwks, Clock.systemUTC());
	}

	public JwtExternalTokenVerifier(String projectId, Supplier<String> jwks, Clock clock) {
		this(projectId, null, requireJwks(jwks), clock);
	}

	private JwtExternalTokenVerifier(String projectId, PublicKey publicKey, Supplier<String> jwks, Clock clock) {
		if (projectId == null || projectId.isBlank()) {
			throw new IllegalArgumentException("projectId is required");
		}
		if (clock == null) {
			throw new IllegalArgumentException("clock is required");
		}
		this.expectedIssuer = "https://securetoken.google.com/" + projectId;
		this.expectedAudience = projectId;
		this.publicKey = publicKey;
		this.jwks = jwks;
		this.clock = clock;
	}

	@Override
	public VerifiedExternalIdentity verify(String token) {
		try {
			return verifyInternal(token);
		}
		catch (TokenVerificationException ex) {
			throw ex;
		}
		catch (RuntimeException ex) {
			throw fail(TokenVerificationFailure.INVALID);
		}
	}

	private VerifiedExternalIdentity verifyInternal(String token) {
		ParsedJwt jwt = parse(token);
		if (!RS256.equals(jwt.alg)) {
			throw fail(TokenVerificationFailure.INVALID);
		}
		PublicKey key = resolveKey(jwt.kid);
		verifySignature(jwt, key);
		if (!expectedIssuer.equals(jwt.iss) || !expectedAudience.equals(jwt.aud) || jwt.sub == null || jwt.sub.isBlank()) {
			throw fail(TokenVerificationFailure.INVALID);
		}
		if (jwt.exp == null || clock.instant().getEpochSecond() >= jwt.exp) {
			throw fail(jwt.exp == null ? TokenVerificationFailure.INVALID : TokenVerificationFailure.EXPIRED);
		}
		return new VerifiedExternalIdentity(jwt.iss, jwt.sub);
	}

	private static ParsedJwt parse(String token) {
		if (token == null || token.isBlank()) {
			throw fail(TokenVerificationFailure.INVALID);
		}
		int firstDot = token.indexOf('.');
		int lastDot = token.lastIndexOf('.');
		if (firstDot <= 0 || lastDot <= firstDot + 1 || lastDot == token.length() - 1
			|| token.indexOf('.', firstDot + 1) != lastDot) {
			throw fail(TokenVerificationFailure.INVALID);
		}
		String headerB64 = token.substring(0, firstDot);
		String payloadB64 = token.substring(firstDot + 1, lastDot);
		String signatureB64 = token.substring(lastDot + 1);
		byte[] headerJson;
		byte[] payloadJson;
		byte[] signature;
		try {
			headerJson = decode(headerB64);
			payloadJson = decode(payloadB64);
			signature = decode(signatureB64);
		}
		catch (IllegalArgumentException ex) {
			throw fail(TokenVerificationFailure.INVALID);
		}
		JsonNode header;
		JsonNode payload;
		try {
			header = MAPPER.readTree(headerJson);
			payload = MAPPER.readTree(payloadJson);
		}
		catch (JacksonException ex) {
			throw fail(TokenVerificationFailure.INVALID);
		}
		if (header == null || !header.isObject() || payload == null || !payload.isObject()) {
			throw fail(TokenVerificationFailure.INVALID);
		}
		Long exp = null;
		JsonNode expNode = payload.get("exp");
		if (expNode != null && expNode.isNumber()) {
			exp = expNode.asLong();
		}
		return new ParsedJwt(
			headerB64,
			payloadB64,
			signature,
			stringClaim(header, "alg"),
			stringClaim(header, "kid"),
			stringClaim(payload, "iss"),
			stringClaim(payload, "aud"),
			stringClaim(payload, "sub"),
			exp);
	}

	private PublicKey resolveKey(String kid) {
		if (publicKey != null) {
			return publicKey;
		}
		try {
			Map<String, PublicKey> keys = jwksKeys;
			if (keys == null) {
				String document = jwks.get();
				if (document == null || document.isBlank()) {
					throw fail(TokenVerificationFailure.UNVERIFIABLE);
				}
				keys = parseJwks(document);
				jwksKeys = keys;
			}
			if (keys.isEmpty()) {
				throw fail(TokenVerificationFailure.UNVERIFIABLE);
			}
			PublicKey found = kid == null || kid.isBlank()
				? (keys.size() == 1 ? keys.values().iterator().next() : null)
				: keys.get(kid);
			if (found == null) {
				throw fail(TokenVerificationFailure.UNVERIFIABLE);
			}
			return found;
		}
		catch (TokenVerificationException ex) {
			throw ex;
		}
		catch (RuntimeException ex) {
			throw fail(TokenVerificationFailure.UNVERIFIABLE);
		}
	}

	private static Map<String, PublicKey> parseJwks(String document) {
		JsonNode root;
		try {
			root = MAPPER.readTree(document);
		}
		catch (JacksonException ex) {
			throw fail(TokenVerificationFailure.UNVERIFIABLE);
		}
		if (root == null || !root.isObject()) {
			throw fail(TokenVerificationFailure.UNVERIFIABLE);
		}
		JsonNode keysNode = root.get("keys");
		if (keysNode == null || !keysNode.isArray()) {
			throw fail(TokenVerificationFailure.UNVERIFIABLE);
		}
		Map<String, PublicKey> keys = new HashMap<>();
		for (JsonNode key : keysNode) {
			if (key == null || !key.isObject()) {
				continue;
			}
			String kty = stringClaim(key, "kty");
			String n = stringClaim(key, "n");
			String e = stringClaim(key, "e");
			if (!"RSA".equals(kty) || n == null || n.isBlank() || e == null || e.isBlank()) {
				continue;
			}
			try {
				PublicKey publicKey = rsaPublic(n, e);
				String kid = stringClaim(key, "kid");
				if (kid != null && !kid.isBlank()) {
					keys.put(kid, publicKey);
				}
				else if (keysNode.size() == 1) {
					keys.put("", publicKey);
				}
			}
			catch (GeneralSecurityException | IllegalArgumentException ex) {
				// skip unusable key material
			}
		}
		return keys;
	}

	private static void verifySignature(ParsedJwt jwt, PublicKey key) {
		try {
			Signature signature = Signature.getInstance("SHA256withRSA");
			signature.initVerify(key);
			signature.update((jwt.headerB64 + "." + jwt.payloadB64).getBytes(StandardCharsets.US_ASCII));
			if (!signature.verify(jwt.signature)) {
				throw fail(TokenVerificationFailure.INVALID);
			}
		}
		catch (TokenVerificationException ex) {
			throw ex;
		}
		catch (InvalidKeyException | SignatureException | NoSuchAlgorithmException ex) {
			throw fail(TokenVerificationFailure.UNVERIFIABLE);
		}
	}

	private static PublicKey rsaPublic(String n, String e) throws GeneralSecurityException {
		RSAPublicKeySpec spec = new RSAPublicKeySpec(
			new BigInteger(1, decode(n)),
			new BigInteger(1, decode(e)));
		return KeyFactory.getInstance("RSA").generatePublic(spec);
	}

	private static String stringClaim(JsonNode node, String name) {
		JsonNode value = node.get(name);
		if (value == null || !value.isString()) {
			return null;
		}
		return value.stringValue();
	}

	private static byte[] decode(String value) {
		return Base64.getUrlDecoder().decode(value);
	}

	private static PublicKey requirePublicKey(PublicKey publicKey) {
		if (publicKey == null) {
			throw new IllegalArgumentException("publicKey is required");
		}
		return publicKey;
	}

	private static Supplier<String> requireJwks(Supplier<String> jwks) {
		if (jwks == null) {
			throw new IllegalArgumentException("jwks is required");
		}
		return jwks;
	}

	private static TokenVerificationException fail(TokenVerificationFailure failure) {
		return new TokenVerificationException(failure);
	}

	private record ParsedJwt(
		String headerB64,
		String payloadB64,
		byte[] signature,
		String alg,
		String kid,
		String iss,
		String aud,
		String sub,
		Long exp
	) {
	}
}
