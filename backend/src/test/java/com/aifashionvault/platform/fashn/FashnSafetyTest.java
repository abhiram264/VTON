package com.aifashionvault.platform.fashn;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import com.aifashionvault.generation.vton.GenerationSubmission;
import com.aifashionvault.generation.vton.ItemCategory;
import com.aifashionvault.generation.vton.ProviderKey;
import com.aifashionvault.platform.environment.VaultEnvironmentProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class FashnSafetyTest {
    private static final String SECRET = "super-secret-fashn-key-value";

    @Test
    void fashnConfigurationToStringDoesNotExposeApiKey() {
        VaultEnvironmentProperties.Fashn fashn = new VaultEnvironmentProperties.Fashn(SECRET);
        assertThat(fashn.toString()).isEqualTo("Fashn[]");
        assertThat(fashn.toString()).doesNotContain(SECRET, "Bearer", "api");
        assertThat(new FashnMapper().toString()).doesNotContain(SECRET);
        assertThat(new FashnVtonProvider(new FashnMapper(), new FashnClient(FashnConfig.fashnRestClientBuilder("other-key").build()), new FashnTestSupport.FakeObjectStore()).toString())
            .doesNotContain(SECRET, "other-key", "Bearer");
    }

    @Test
    void providerFailuresAndExceptionsOmitUnsafePayloads() {
        FashnTestSupport.Harness harness = FashnTestSupport.harness();
        harness.server().expect(once(), requestTo(FashnTestSupport.RUN_URL))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(FashnTestSupport.apiError("BadRequest", "see https://evil.test/x?X-Amz-Signature=abc data:image/png;base64,abc")));

        GenerationSubmission submission = harness.provider().submit(FashnTestSupport.request(FashnTestSupport.STANDARD), FashnTestSupport.key());

        assertThat(submission).isInstanceOf(GenerationSubmission.Rejected.class);
        String text = ((GenerationSubmission.Rejected) submission).failure().toString() + submission;
        assertThat(text).doesNotContain("https://evil.test", "X-Amz-Signature", "data:image", "base64,abc", FashnTestSupport.API_KEY, "Bearer");
        harness.server().verify();
    }

    @Test
    void invalidAdapterInputHasNoRemoteOrStorageSideEffects() {
        FashnTestSupport.Harness harness = FashnTestSupport.harness();

        GenerationSubmission submission = harness.provider().submit(
            FashnTestSupport.request(
                FashnTestSupport.FAST,
                new ItemCategory("footwear"),
                Optional.empty()),
            FashnTestSupport.key());

        assertThat(submission).isInstanceOfSatisfying(GenerationSubmission.Rejected.class, rejected ->
            assertThat(rejected.failure().safeCode()).isEqualTo("CATEGORY_UNSUPPORTED"));
        assertThat(harness.store().headCount).isZero();
        assertThat(harness.store().readCount).isZero();
        assertThat(harness.store().presignCount).isZero();
        assertThat(harness.provider().providerKey()).isEqualTo(new ProviderKey("fashn"));
        harness.server().verify();
    }
}
