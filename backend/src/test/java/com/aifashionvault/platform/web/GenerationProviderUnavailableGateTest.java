package com.aifashionvault.platform.web;

import java.time.Clock;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import com.aifashionvault.platform.fashn.FashnConfig;
import com.aifashionvault.platform.environment.VaultEnvironmentProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The owner constraint: with no provider configured the default provider must be
 * HONESTLY UNAVAILABLE, not silently selected. Both directions are proven here.
 */
class GenerationProviderUnavailableGateTest {


	private GenerationCreationService service(boolean providerAvailable) {
		// null JdbcTemplate and null store: the refusal must happen before avatar
		// resolution (:84 resolveCurrentAvatar) and before the idempotency replay
		// loop, so neither is ever touched on the refusal path. If the gate moved
		// after either, this test would blow up instead of passing.
		return new GenerationCreationService(
			null, null, new GenerationApiProperties(), Clock.systemUTC(), null, null, null, providerAvailable);
	}

	@Test
	void unconfiguredProviderIsRefusedWithAnExplicitOutageCode() {
		assertThatThrownBy(() -> service(false).create(new com.aifashionvault.shared.UserId(UUID.randomUUID()), "k-1",
			new GenerationRequest(UUID.randomUUID())))
			.isInstanceOf(GenerationCreationService.Problem.class)
			.satisfies(thrown -> {
				GenerationCreationService.Problem problem = (GenerationCreationService.Problem) thrown;
				assertThat(problem.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
				assertThat(problem.code()).isEqualTo("GENERATION_PROVIDER_UNAVAILABLE");
			});
	}

	@Test
	void configuredProviderIsNotRefusedByTheGate() {
		// Bidirectional: with a provider present the gate must not fire. A configured
		// provider gets past the gate and on to real work, so the observable proof
		// that the gate did NOT fire is that we fail somewhere LATER (avatar lookup
		// on the null JdbcTemplate) rather than with the provider code.
		Throwable thrown = catchThrowable(() -> service(true).create(
			new com.aifashionvault.shared.UserId(UUID.randomUUID()), "k-2",
			new GenerationRequest(UUID.randomUUID())));

		// The gate did not fire: execution ran PAST it and died later, in
		// resolveCurrentAvatar, on the deliberately null JdbcTemplate. That is the
		// observable proof of admission — the provider code never appears.
		assertThat(thrown)
			.isNotNull()
			.isNotInstanceOf(GenerationCreationService.Problem.class);
		assertThat(thrown).hasStackTraceContaining("resolveCurrentAvatar");
	}

	@Test
	void providerCodeIsDistinctFromTheMediaAndAdmissionCodes() {
		// A provider outage must never be reported as a media fault or an operator
		// pause: the client classifies these differently (t206).
		assertThat(GenerationCreationService.GENERATION_PROVIDER_UNAVAILABLE)
			.isNotEqualTo(GenerationCreationService.GENERATION_MEDIA_UNAVAILABLE)
			.isNotEqualTo(GenerationCreationService.ADMISSION_PAUSED);
	}

	@Test
	void blankOrAbsentApiKeyMeansNoProvider() {
		assertThat(FashnConfig.providerConfigured(null)).isFalse();
		assertThat(FashnConfig.providerConfigured(new VaultEnvironmentProperties(null, null, null, null, null,
			new VaultEnvironmentProperties.Fashn(null), new VaultEnvironmentProperties.Inferhub(null), null,
			null))).isFalse();
		assertThat(FashnConfig.providerConfigured(new VaultEnvironmentProperties(null, null, null, null, null,
			new VaultEnvironmentProperties.Fashn("   "), new VaultEnvironmentProperties.Inferhub(null), null,
			null))).isFalse();
	}

	/**
	 * t220: the create-path admission gate is now the provider-agnostic
	 * {@code anyProviderConfigured}, so an InferHub-only setup is admitted and a
	 * blank/absent key on either provider is never mistaken for a configured one.
	 */
	@Test
	void anyProviderConfiguredAdmitsEitherProviderAndRefusesBlank() {
		assertThat(FashnConfig.anyProviderConfigured(null)).isFalse();
		assertThat(FashnConfig.anyProviderConfigured(new VaultEnvironmentProperties(null, null, null, null, null,
			new VaultEnvironmentProperties.Fashn(null), new VaultEnvironmentProperties.Inferhub("   "), null,
			null))).isFalse();
		assertThat(FashnConfig.anyProviderConfigured(new VaultEnvironmentProperties(null, null, null, null, null,
			new VaultEnvironmentProperties.Fashn(null), new VaultEnvironmentProperties.Inferhub("test-inferhub-key"),
			null, null))).isTrue();
		assertThat(FashnConfig.anyProviderConfigured(new VaultEnvironmentProperties(null, null, null, null, null,
			new VaultEnvironmentProperties.Fashn("test-fashn-key"), new VaultEnvironmentProperties.Inferhub(null),
			null, null))).isTrue();
	}

}
