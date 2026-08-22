package com.aifashionvault.platform.security;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenVerificationExceptionTest {

	@Test
	void failureValuesAreExactlyInvalidExpiredUnverifiable() {
		assertThat(TokenVerificationFailure.values()).hasSize(3);
		assertThat(TokenVerificationFailure.values())
			.extracting(Enum::name)
			.containsExactly("INVALID", "EXPIRED", "UNVERIFIABLE");
	}

	@Test
	void messageAndToStringEqualCategoryNameOnly() {
		String token = "eyJhbGciOiJIUzI1NiJ9.payload.sig";
		String header = "Authorization: Bearer " + token;

		for (TokenVerificationFailure failure : TokenVerificationFailure.values()) {
			TokenVerificationException exception = new TokenVerificationException(failure);

			assertThat(exception.failure()).isEqualTo(failure);
			assertThat(exception.getMessage()).isEqualTo(failure.name());
			assertThat(exception.toString()).isEqualTo(failure.name());
			assertThat(exception.getMessage()).doesNotContain(token, header, "payload");
			assertThat(exception.toString()).doesNotContain(token, header, "payload");
		}

		TokenVerificationException invalid = new TokenVerificationException(TokenVerificationFailure.INVALID);
		assertThat(invalid.getMessage()).isEqualTo("INVALID");
		assertThat(invalid.toString()).isEqualTo("INVALID");
	}

	@Test
	void constructorDoesNotAcceptTokenHeaderOrPayload() {
		assertThat(TokenVerificationException.class.getDeclaredConstructors()).hasSize(1);
		assertThat(TokenVerificationException.class.getDeclaredConstructors()[0].getParameterTypes())
			.containsExactly(TokenVerificationFailure.class);
		assertThat(RuntimeException.class.isAssignableFrom(TokenVerificationException.class)).isTrue();
		assertThat(Modifier.isFinal(TokenVerificationException.class.getModifiers())).isTrue();
		assertThatThrownBy(() -> TokenVerificationException.class.getConstructor(TokenVerificationFailure.class, String.class))
			.isInstanceOf(NoSuchMethodException.class);
		assertThatThrownBy(() -> TokenVerificationException.class.getConstructor(String.class))
			.isInstanceOf(NoSuchMethodException.class);
	}

	@Test
	void verifyIsTheOnlyExternalTokenVerifierMethod() {
		assertThat(ExternalTokenVerifier.class.isInterface()).isTrue();
		Method[] methods = ExternalTokenVerifier.class.getDeclaredMethods();
		assertThat(methods).hasSize(1);
		assertThat(methods[0].getName()).isEqualTo("verify");
		assertThat(methods[0].getParameterTypes()).containsExactly(String.class);
		assertThat(methods[0].getReturnType()).isEqualTo(VerifiedExternalIdentity.class);
	}
}
