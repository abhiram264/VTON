package com.aifashionvault.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.aifashionvault.integrity.AppIntegrityFailureReason;
import com.aifashionvault.integrity.AppIntegrityVerifier;
import com.aifashionvault.integrity.AppIntegrityVerdict;
import com.aifashionvault.integrity.AppIntegrityVerdictStatus;
import com.aifashionvault.platform.security.ExternalTokenVerifier;
import com.aifashionvault.platform.security.InProcessJwtFixture;

@SpringBootTest(properties = {
    "vault.generation.attestation-required=true",
    "spring.security.user.name=integrity-test",
    "spring.security.user.password=integrity-test",
    "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
    "vault.avatar.attestation-required=true"
})
@AutoConfigureMockMvc(addFilters = false)
class AppIntegrityBoundaryTest {
    @Autowired MockMvc mockMvc;
    @Autowired RecordingVerifier verifier;

    @BeforeEach void reset() { verifier.reset(); }

    @Test void requiredGenerationIntegrityBlocksMissingDeniedAndIndeterminateBeforeService() throws Exception {
        assertBlocked("/generations", "{\"productUploadId\":\"" + UUID.randomUUID() + "\"}", null);
        verifier.verdict = new AppIntegrityVerdict(AppIntegrityVerdictStatus.DENIED, AppIntegrityFailureReason.PROVIDER_REJECTED);
        assertBlocked("/generations", "{\"productUploadId\":\"" + UUID.randomUUID() + "\"}", "secret");
        verifier.verdict = new AppIntegrityVerdict(AppIntegrityVerdictStatus.INDETERMINATE, AppIntegrityFailureReason.PROVIDER_UNAVAILABLE);
        assertBlocked("/generations", "{\"productUploadId\":\"" + UUID.randomUUID() + "\"}", "secret");
    }

    @Test void requiredAvatarIntegrityBlocksMissingDeniedAndIndeterminateBeforeWorkflow() throws Exception {
        assertBlocked("/avatars", "{\"uploadId\":\"" + UUID.randomUUID() + "\"}", null);
        verifier.verdict = new AppIntegrityVerdict(AppIntegrityVerdictStatus.DENIED, AppIntegrityFailureReason.PROVIDER_REJECTED);
        assertBlocked("/avatars", "{\"uploadId\":\"" + UUID.randomUUID() + "\"}", "secret");
        verifier.verdict = new AppIntegrityVerdict(AppIntegrityVerdictStatus.INDETERMINATE, AppIntegrityFailureReason.PROVIDER_UNAVAILABLE);
        assertBlocked("/avatars", "{\"uploadId\":\"" + UUID.randomUUID() + "\"}", "secret");
    }
    @Test void requiredAvatarReplaceIntegrityBlocksBeforeRevisionLookup() throws Exception {
        String path = "/avatars/" + UUID.randomUUID();
        String body = "{\"uploadId\":\"" + UUID.randomUUID() + "\"}";
        assertPatchBlocked(path, body, null);
        verifier.verdict = new AppIntegrityVerdict(AppIntegrityVerdictStatus.DENIED, AppIntegrityFailureReason.PROVIDER_REJECTED);
        assertPatchBlocked(path, body, "secret");
    }

    @Test void requiredGenerationIntegrityAlsoBlocksLookRegenerate() throws Exception {
        assertBlocked("/looks/" + UUID.randomUUID() + "/regenerate", null, null);
        verifier.verdict = new AppIntegrityVerdict(AppIntegrityVerdictStatus.DENIED, AppIntegrityFailureReason.PROVIDER_REJECTED);
        assertBlocked("/looks/" + UUID.randomUUID() + "/regenerate", null, "secret");
        verifier.verdict = new AppIntegrityVerdict(AppIntegrityVerdictStatus.INDETERMINATE, AppIntegrityFailureReason.PROVIDER_UNAVAILABLE);
        assertBlocked("/looks/" + UUID.randomUUID() + "/regenerate", null, "secret");
    }

    @Test void requiredIntegrityRejectsInProcessRegenerateWithNoRequestAsForbidden() {
        GenerationApiProperties enabled = new GenerationApiProperties();
        enabled.setAttestationRequired(true);

        // No servlet request means no evidence, so the gate must answer its own 403 +
        // APP_INTEGRITY_REQUIRED rather than dereferencing null into a 500. Nothing below
        // the boundary is reached: the look lookup would raise NotFound instead.
        AppIntegrityBoundary.Problem blocked = catchIntegrityProblem(() ->
            new LooksController(emptyProviders(), emptyProviders(), emptyProviders(), emptyProviders(), verifier, enabled)
                .regenerate(new com.aifashionvault.shared.UserId(UUID.randomUUID()), UUID.randomUUID().toString(), "key"));
        assertThat(blocked).isNotNull();
        assertThat(blocked.status()).isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN);
        assertThat(blocked.code()).isEqualTo("APP_INTEGRITY_REQUIRED");

        // With the flag off the boundary is a no-op, so the same call falls past the gate
        // and fails in the controller's own look lookup instead. That difference is what
        // proves the 403 above came from the gate and not from a missing look.
        assertThatThrownBy(() ->
            new LooksController(emptyProviders(), emptyProviders(), emptyProviders(), emptyProviders(), verifier,
                new GenerationApiProperties())
                .regenerate(new com.aifashionvault.shared.UserId(UUID.randomUUID()), UUID.randomUUID().toString(), "key"))
            .isNotInstanceOf(AppIntegrityBoundary.Problem.class)
            .isInstanceOf(RuntimeException.class);
    }

    private static <T> ObjectProvider<T> emptyProviders() {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = org.mockito.Mockito.mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    private static AppIntegrityBoundary.Problem catchIntegrityProblem(Runnable call) {
        try {
            call.run();
            return null;
        }
        catch (AppIntegrityBoundary.Problem ex) {
            return ex;
        }
        catch (RuntimeException ex) {
            throw new AssertionError("expected the integrity 403, but the call threw " + ex, ex);
        }
    }



    @Test void verifiedAvatarReplaceProceedsPastIntegrityBoundaryWithoutExposure() throws Exception {
        verifier.verdict = new AppIntegrityVerdict(AppIntegrityVerdictStatus.VERIFIED, AppIntegrityFailureReason.NONE);
        mockMvc.perform(patchRequest("/avatars/" + UUID.randomUUID(),
                "{\"uploadId\":\"" + UUID.randomUUID() + "\"}", "secret"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().string("{\"code\":\"AVATAR_MEDIA_MISSING\"}"));
        assertThat(verifier.calls).hasValue(1);
    }


    @Test void verifiedEvidenceProceedsPastIntegrityBoundaryWithoutExposure() throws Exception {
        verifier.verdict = new AppIntegrityVerdict(AppIntegrityVerdictStatus.VERIFIED, AppIntegrityFailureReason.NONE);
        mockMvc.perform(request("/generations", "{\"productUploadId\":\"" + UUID.randomUUID() + "\"}", "secret"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().string("{\"code\":\"GENERATION_PROVIDER_UNAVAILABLE\"}"));
        mockMvc.perform(request("/avatars", "{\"uploadId\":\"" + UUID.randomUUID() + "\"}", "secret"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().string("{\"code\":\"AVATAR_MEDIA_MISSING\"}"));
        assertThat(verifier.calls).hasValue(2);
    }

    private void assertBlocked(String path, String body, String evidence) throws Exception {
        var action = request(path, body, evidence);
        mockMvc.perform(action).andExpect(status().isForbidden())
            .andExpect(content().string("{\"code\":\"APP_INTEGRITY_REQUIRED\"}"));
    }
    private void assertPatchBlocked(String path, String body, String evidence) throws Exception {
        mockMvc.perform(patchRequest(path, body, evidence)).andExpect(status().isForbidden())
            .andExpect(content().string("{\"code\":\"APP_INTEGRITY_REQUIRED\"}"));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder patchRequest(
        String path, String body, String evidence
    ) {
        var request = patch(path)
            .with(SecurityMockMvcRequestPostProcessors.user("integrity-test"))
            .with(SecurityMockMvcRequestPostProcessors.httpBasic("integrity-test", "integrity-test"))
            .header("X-App-Integrity-Platform", "ANDROID")
            .header("X-App-Integrity-Nonce", "nonce")
            .header(HttpHeaders.IF_MATCH, "\"1\"")
            .contentType(MediaType.APPLICATION_JSON).content(body);
        return evidence == null ? request : request.header("X-App-Integrity-Evidence", evidence);
    }


    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(
        String path, String body, String evidence
    ) {
        var request = post(path)
            .with(SecurityMockMvcRequestPostProcessors.user("integrity-test"))
            .with(SecurityMockMvcRequestPostProcessors.httpBasic("integrity-test", "integrity-test"))
            .header("X-App-Integrity-Platform", "ANDROID")
            .header("X-App-Integrity-Nonce", "nonce")
            .contentType(MediaType.APPLICATION_JSON);
        if (body != null) request.content(body);
        return evidence == null ? request : request.header("X-App-Integrity-Evidence", evidence);
    }

    @TestConfiguration
    static class Configuration {
        @Bean @Primary ExternalTokenVerifier tokenVerifier() { return InProcessJwtFixture.verifier(); }
        @Bean("recordingAppIntegrityVerifier") @Primary RecordingVerifier recordingVerifier() { return new RecordingVerifier(); }

    }

    static final class RecordingVerifier implements AppIntegrityVerifier {
        final AtomicInteger calls = new AtomicInteger();
        AppIntegrityVerdict verdict;
        void reset() { calls.set(0); verdict = null; }
        @Override public AppIntegrityVerdict verify(com.aifashionvault.integrity.AppIntegrityEvidence evidence) {
            calls.incrementAndGet();
            return verdict == null
                ? new AppIntegrityVerdict(AppIntegrityVerdictStatus.DENIED, AppIntegrityFailureReason.MISSING_EVIDENCE)
                : verdict;
        }
    }
}
