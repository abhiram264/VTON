package com.aifashionvault.platform.security;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.aifashionvault.platform.environment.AppEnv;
import com.aifashionvault.platform.environment.VaultEnvironmentProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalTokenVerificationTest {

	private static final Instant NOW = Instant.parse("2026-08-22T12:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	@Test
	void validRs256TokenResolvesIssuerAndJwtSubject() {
		VerifiedExternalIdentity identity = InProcessJwtFixture.verifier().verify(InProcessJwtFixture.bearerToken());

		assertThat(identity).isNotNull();
		assertThat(identity).isEqualTo(new VerifiedExternalIdentity(
			InProcessJwtFixture.issuer(),
			InProcessJwtFixture.subject()));
		assertThat(identity.issuer()).isEqualTo("https://securetoken.google.com/demo-ai-fashion-vault");
		assertThat(identity.subject()).isEqualTo("in-process-external-subject");
		assertThat(identity.subject()).isNotEqualTo("11111111-1111-1111-1111-111111111111");
	}

	@Test
	void expiredTokenThrowsExpired() {
		assertFailure(
			InProcessJwtFixture.verifier(),
			InProcessJwtFixture.expiredBearerToken(),
			TokenVerificationFailure.EXPIRED);
	}

	@Test
	void invalidSignatureThrowsInvalid() {
		String token = InProcessJwtFixture.bearerToken();
		int lastDot = token.lastIndexOf('.');
		byte[] signature = Base64.getUrlDecoder().decode(token.substring(lastDot + 1));
		signature[0] ^= 0x01;
		String tampered = token.substring(0, lastDot + 1)
			+ Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
		assertFailure(InProcessJwtFixture.verifier(), tampered, TokenVerificationFailure.INVALID);
	}

	@Test
	void wrongIssuerThrowsInvalid() {
		Map<String, Object> claims = InProcessJwtFixture.validClaims(NOW.plusSeconds(60));
		claims.put("iss", "https://securetoken.google.com/other-project");
		assertFailure(InProcessJwtFixture.mint(InProcessJwtFixture.defaultHeader(), claims), TokenVerificationFailure.INVALID);
	}

	@Test
	void wrongAudienceThrowsInvalid() {
		Map<String, Object> claims = InProcessJwtFixture.validClaims(NOW.plusSeconds(60));
		claims.put("aud", "other-project");
		assertFailure(InProcessJwtFixture.mint(InProcessJwtFixture.defaultHeader(), claims), TokenVerificationFailure.INVALID);
	}

	@Test
	void blankSubThrowsInvalid() {
		Map<String, Object> blank = InProcessJwtFixture.validClaims(NOW.plusSeconds(60));
		blank.put("sub", "");
		assertFailure(InProcessJwtFixture.mint(InProcessJwtFixture.defaultHeader(), blank), TokenVerificationFailure.INVALID);

		Map<String, Object> whitespace = InProcessJwtFixture.validClaims(NOW.plusSeconds(60));
		whitespace.put("sub", "   ");
		assertFailure(InProcessJwtFixture.mint(InProcessJwtFixture.defaultHeader(), whitespace), TokenVerificationFailure.INVALID);
	}

	@Test
	void missingSubThrowsInvalid() {
		Map<String, Object> claims = InProcessJwtFixture.validClaims(NOW.plusSeconds(60));
		claims.remove("sub");
		assertFailure(InProcessJwtFixture.mint(InProcessJwtFixture.defaultHeader(), claims), TokenVerificationFailure.INVALID);
	}

	@Test
	void nonRs256AndNoneAlgThrowInvalid() {
		Map<String, Object> hs256 = InProcessJwtFixture.defaultHeader();
		hs256.put("alg", "HS256");
		assertFailure(
			InProcessJwtFixture.mint(hs256, InProcessJwtFixture.validClaims(NOW.plusSeconds(60))),
			TokenVerificationFailure.INVALID);

		Map<String, Object> none = InProcessJwtFixture.defaultHeader();
		none.put("alg", "none");
		assertFailure(
			InProcessJwtFixture.mint(none, InProcessJwtFixture.validClaims(NOW.plusSeconds(60))),
			TokenVerificationFailure.INVALID);
	}

	@Test
	void malformedTokensThrowInvalid() {
		ExternalTokenVerifier verifier = InProcessJwtFixture.verifier();
		assertFailure(verifier, null, TokenVerificationFailure.INVALID);
		assertFailure(verifier, "", TokenVerificationFailure.INVALID);
		assertFailure(verifier, "   ", TokenVerificationFailure.INVALID);
		assertFailure(verifier, "not-a-jwt", TokenVerificationFailure.INVALID);
		assertFailure(verifier, "only.two", TokenVerificationFailure.INVALID);
		assertFailure(verifier, "a.b.c.d", TokenVerificationFailure.INVALID);
	}

	@Test
	void missingExpThrowsInvalid() {
		Map<String, Object> claims = InProcessJwtFixture.validClaims(NOW.plusSeconds(60));
		claims.remove("exp");
		assertFailure(InProcessJwtFixture.mint(InProcessJwtFixture.defaultHeader(), claims), TokenVerificationFailure.INVALID);
	}

	@Test
	void expiredTokenWithWrongIssuerIsInvalid() {
		Map<String, Object> claims = InProcessJwtFixture.validClaims(NOW.minusSeconds(60));
		claims.put("iss", "https://securetoken.google.com/other-project");
		assertFailure(InProcessJwtFixture.mint(InProcessJwtFixture.defaultHeader(), claims), TokenVerificationFailure.INVALID);
	}

	@Test
	void missingKeyAndUnusableJwksThrowUnverifiable() {
		String token = InProcessJwtFixture.bearerToken();
		assertFailure(InProcessJwtFixture.verifierWithJwks(() -> "{\"keys\":[]}"), token, TokenVerificationFailure.UNVERIFIABLE);
		assertFailure(InProcessJwtFixture.verifierWithJwks(() -> "not-json"), token, TokenVerificationFailure.UNVERIFIABLE);
		assertFailure(
			InProcessJwtFixture.verifierWithJwks(() -> "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"in-process-rs256\"}]}"),
			token,
			TokenVerificationFailure.UNVERIFIABLE);
	}

	@Test
	void unknownKidThrowsUnverifiable() {
		assertFailure(
			InProcessJwtFixture.verifierWithJwks(() -> "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"other-key\",\"n\":\"AQAB\",\"e\":\"AQAB\"}]}"),
			InProcessJwtFixture.bearerToken(),
			TokenVerificationFailure.UNVERIFIABLE);
	}

	@Test
	void stubJwksVerifiesMatchingToken() {
		VerifiedExternalIdentity identity = InProcessJwtFixture.verifierWithJwks(InProcessJwtFixture::jwks)
			.verify(InProcessJwtFixture.bearerToken());

		assertThat(identity).isEqualTo(new VerifiedExternalIdentity(
			InProcessJwtFixture.issuer(),
			InProcessJwtFixture.subject()));
	}

	@Test
	void jwksSupplierIsNotInvokedAtConstruction() {
		AtomicInteger fetches = new AtomicInteger();
		ExternalTokenVerifier verifier = InProcessJwtFixture.verifierWithJwks(() -> {
			fetches.incrementAndGet();
			return InProcessJwtFixture.jwks();
		});

		assertThat(fetches.get()).isZero();
		assertThat(verifier.verify(InProcessJwtFixture.bearerToken()).subject()).isEqualTo(InProcessJwtFixture.subject());
		assertThat(fetches.get()).isEqualTo(1);
	}

	@Test
	void expBoundaryUsesInjectableClock() {
		ExternalTokenVerifier verifier = InProcessJwtFixture.verifierWithClock(CLOCK);

		Map<String, Object> atNow = InProcessJwtFixture.validClaims(NOW);
		assertFailure(verifier, InProcessJwtFixture.mint(InProcessJwtFixture.defaultHeader(), atNow), TokenVerificationFailure.EXPIRED);

		Map<String, Object> oneSecondPast = InProcessJwtFixture.validClaims(NOW.minusSeconds(1));
		assertFailure(
			verifier,
			InProcessJwtFixture.mint(InProcessJwtFixture.defaultHeader(), oneSecondPast),
			TokenVerificationFailure.EXPIRED);

		Map<String, Object> oneSecondFuture = InProcessJwtFixture.validClaims(NOW.plusSeconds(1));
		VerifiedExternalIdentity identity = verifier.verify(InProcessJwtFixture.mint(InProcessJwtFixture.defaultHeader(), oneSecondFuture));
		assertThat(identity.subject()).isEqualTo(InProcessJwtFixture.subject());
	}

	@Test
	void failureMessageIsCategoryNameWithoutToken() {
		String token = InProcessJwtFixture.expiredBearerToken();
		assertThatThrownBy(() -> InProcessJwtFixture.verifier().verify(token))
			.isInstanceOf(TokenVerificationException.class)
			.satisfies(thrown -> {
				TokenVerificationException exception = (TokenVerificationException) thrown;
				assertThat(exception.failure()).isEqualTo(TokenVerificationFailure.EXPIRED);
				assertThat(exception.getMessage()).isEqualTo("EXPIRED");
				assertThat(exception.toString()).isEqualTo("EXPIRED");
				assertThat(exception.getMessage()).doesNotContain(token);
				assertThat(exception.toString()).doesNotContain(token);
			});

		String invalid = "not-a-jwt";
		assertThatThrownBy(() -> InProcessJwtFixture.verifier().verify(invalid))
			.isInstanceOf(TokenVerificationException.class)
			.satisfies(thrown -> {
				TokenVerificationException exception = (TokenVerificationException) thrown;
				assertThat(exception.getMessage()).isEqualTo("INVALID");
				assertThat(exception.toString()).isEqualTo("INVALID");
				assertThat(exception.getMessage()).doesNotContain(invalid);
			});
	}

	@Test
	void fixtureExposesPublicHelpersForLaterHttpTests() throws Exception {
		assertThat(Modifier.isPublic(InProcessJwtFixture.class.getModifiers())).isTrue();
		assertThat(InProcessJwtFixture.class.getMethod("verifier").getReturnType()).isEqualTo(ExternalTokenVerifier.class);
		assertThat(InProcessJwtFixture.class.getMethod("bearerToken").getReturnType()).isEqualTo(String.class);
		assertThat(InProcessJwtFixture.class.getMethod("expiredBearerToken").getReturnType()).isEqualTo(String.class);
		assertThat(InProcessJwtFixture.class.getMethod("issuer").getReturnType()).isEqualTo(String.class);
		assertThat(InProcessJwtFixture.class.getMethod("subject").getReturnType()).isEqualTo(String.class);
		assertThat(InProcessJwtFixture.issuer()).isEqualTo("https://securetoken.google.com/demo-ai-fashion-vault");
		assertThat(InProcessJwtFixture.subject()).isEqualTo("in-process-external-subject");
		assertThat(InProcessJwtFixture.bearerToken()).isNotBlank().doesNotStartWith("Bearer ");
	}

	@Test
	void configurationExposesVerifierBeanWithoutFetchingJwks() {
		new ApplicationContextRunner()
			.withUserConfiguration(ExternalTokenVerifierConfiguration.class)
			.withBean(VaultEnvironmentProperties.class, ExternalTokenVerificationTest::testProperties)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(ExternalTokenVerifier.class);
				assertThat(context.getBean(ExternalTokenVerifier.class)).isInstanceOf(JwtExternalTokenVerifier.class);
			});

		Method[] beans = Arrays.stream(ExternalTokenVerifierConfiguration.class.getDeclaredMethods())
			.filter(method -> method.getAnnotation(Bean.class) != null)
			.toArray(Method[]::new);
		assertThat(ExternalTokenVerifierConfiguration.class.getAnnotation(Configuration.class)).isNotNull();
		assertThat(beans).hasSize(1);
		assertThat(beans[0].getReturnType()).isEqualTo(ExternalTokenVerifier.class);
	}

	private static void assertFailure(String token, TokenVerificationFailure expected) {
		assertFailure(InProcessJwtFixture.verifier(), token, expected);
	}

	private static void assertFailure(ExternalTokenVerifier verifier, String token, TokenVerificationFailure expected) {
		assertThatThrownBy(() -> verifier.verify(token))
			.isInstanceOf(TokenVerificationException.class)
			.satisfies(thrown -> {
				TokenVerificationException exception = (TokenVerificationException) thrown;
				assertThat(exception.failure()).isEqualTo(expected);
				assertThat(exception.getMessage()).isEqualTo(expected.name());
				assertThat(exception.toString()).isEqualTo(expected.name());
				if (token != null && !token.isBlank()) {
					assertThat(exception.getMessage()).doesNotContain(token);
					assertThat(exception.toString()).doesNotContain(token);
				}
			});
	}

	private static VaultEnvironmentProperties testProperties() {
		return new VaultEnvironmentProperties(
			AppEnv.development,
			null,
			null,
			null,
			null,
			null,
			new VaultEnvironmentProperties.Firebase("demo-ai-fashion-vault"),
			null);
	}
}
