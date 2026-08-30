package com.aifashionvault.generation.vton;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ProviderFailureRetryAfterTest {

    @Test
    void legacyConstructorLeavesRetryAfterGuidanceAbsent() {
        var failure = new ProviderFailure(ProviderFailureCategory.RATE_CAPACITY, true, "RATE_LIMITED");

        assertThat(failure.retryAfterAt()).isNull();
    }

    @Test
    void fourArgumentConstructorRetainsSuppliedRetryAfterInstant() {
        Instant retryAfter = Instant.parse("2026-08-28T12:34:56Z");

        var failure = new ProviderFailure(ProviderFailureCategory.RATE_CAPACITY, true, "RATE_LIMITED", retryAfter);

        assertThat(failure.retryAfterAt()).isSameAs(retryAfter);
    }

    @Test
    void trailingContractVersionDelegatesAsNullFromLegacyConstructors() {
        var threeArgument = new ProviderFailure(ProviderFailureCategory.RATE_CAPACITY, true, "RATE_LIMITED");
        var fourArgument = new ProviderFailure(
            ProviderFailureCategory.RATE_CAPACITY,
            true,
            "RATE_LIMITED",
            Instant.parse("2026-08-28T12:34:56Z"));

        assertThat(threeArgument.mappingContractVersion()).isNull();
        assertThat(fourArgument.mappingContractVersion()).isNull();
        assertThat(threeArgument.retryAfterAt()).isNull();
        assertThat(fourArgument.retryAfterAt()).isEqualTo(Instant.parse("2026-08-28T12:34:56Z"));
    }

    @Test
    void trailingContractVersionValidatesNormalizedAdapterToken() {
        var stamped = new ProviderFailure(
            ProviderFailureCategory.RATE_CAPACITY,
            true,
            "RATE_LIMITED",
            Instant.parse("2026-08-28T12:34:56Z"),
            "fashn-mapping-v1");

        assertThat(stamped.mappingContractVersion()).isEqualTo("fashn-mapping-v1");
        assertThat(stamped.retryAfterAt()).isEqualTo(Instant.parse("2026-08-28T12:34:56Z"));

        assertThatIllegalArgumentException().isThrownBy(() -> new ProviderFailure(
            ProviderFailureCategory.RATE_CAPACITY, true, "RATE_LIMITED", null, "provider body leak https://x.test"));
        assertThatIllegalArgumentException().isThrownBy(() -> new ProviderFailure(
            ProviderFailureCategory.RATE_CAPACITY, true, "RATE_LIMITED", null, "-leading-dash"));
    }

    @Test
    void fourArgumentConstructorKeepsSafeCodeValidation() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new ProviderFailure(
                ProviderFailureCategory.RATE_CAPACITY,
                true,
                "https://provider.example/body",
                Instant.parse("2026-08-28T12:34:56Z")));
    }
}
