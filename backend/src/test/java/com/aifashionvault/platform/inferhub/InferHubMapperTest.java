package com.aifashionvault.platform.inferhub;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.aifashionvault.generation.vton.GenerationSubmission;
import com.aifashionvault.generation.vton.ProviderFailureCategory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wire-shape classification for the InferHub submit path. Every malformed provider
 * shape must end as a CLASSIFIED outcome, never as an escaping exception: an exception
 * from {@code mapRun} is caught by the worker and misrouted to submission-indeterminate,
 * which re-queues a permanent protocol violation until the recovery deadline.
 *
 * <p>Regression pin for the OUTPUT_URL defect: a plain http(s) image_url is a permanent,
 * non-retryable protocol violation and must be classified as such.
 */
class InferHubMapperTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final InferHubMapper mapper = new InferHubMapper();

    private static JsonNode json(String body) {
        return JSON.readTree(body.getBytes(StandardCharsets.UTF_8));
    }

    private static String content(String contentArray) {
        return "{\"choices\":[{\"message\":{\"content\":" + contentArray + "}}]}";
    }

    private static GenerationSubmission submit(String body) {
        return mapper.mapRun(InferHubHttpResponse.http(200, json(body), false)).submission();
    }

    private static void assertRejected(GenerationSubmission submission, String safeCode, boolean retryable) {
        assertThat(submission).isInstanceOf(GenerationSubmission.Rejected.class);
        GenerationSubmission.Rejected rejected = (GenerationSubmission.Rejected) submission;
        assertThat(rejected.failure().category()).isEqualTo(ProviderFailureCategory.PROTOCOL_VIOLATION);
        assertThat(rejected.failure().safeCode()).isEqualTo(safeCode);
        assertThat(rejected.failure().retryable()).isEqualTo(retryable);
    }

    @Test
    void inlineDataUriIsAccepted() {
        String body = content("[{\"type\":\"text\",\"text\":\"here\"},"
            + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,iVBORw0KGgo=\"}}]");
        InferHubMapper.Mapped mapped = mapper.mapRun(InferHubHttpResponse.http(200, json(body), false));
        assertThat(mapped.submission()).isInstanceOf(GenerationSubmission.Accepted.class);
        assertThat(mapped.dataUri()).startsWith("data:image/png;base64,");
    }

    /** The defect this test exists for: a URL is a permanent protocol violation, not an ambiguity. */
    @Test
    void plainHttpsUrlIsAPermanentRejectionNotAnAmbiguity() {
        for (String url : new String[] {
            "https://cdn.example.com/out.png",
            "http://cdn.example.com/out.png"}) {
            GenerationSubmission submission = submit(content(
                "[{\"type\":\"text\"},{\"type\":\"image_url\",\"image_url\":{\"url\":\"" + url + "\"}}]"));
            assertRejected(submission, "OUTPUT_URL", false);
            assertThat(submission).isNotInstanceOf(GenerationSubmission.Indeterminate.class);
        }
    }

    @Test
    void nonDataNonHttpSchemeIsAlsoAUrlRejection() {
        assertRejected(submit(content(
            "[{\"type\":\"image_url\",\"image_url\":{\"url\":\"file:///tmp/out.png\"}}]")),
            "OUTPUT_URL", false);
    }

    @Test
    void missingImagePartIsOutputMissing() {
        assertRejected(submit(content("[{\"type\":\"text\",\"text\":\"no image here\"}]")), "OUTPUT_MISSING", false);
    }

    @Test
    void blankUrlIsOutputMissing() {
        assertRejected(submit(content(
            "[{\"type\":\"image_url\",\"image_url\":{\"url\":\"   \"}}]")), "OUTPUT_MISSING", false);
    }

    @Test
    void nonObjectImageUrlIsOutputMissing() {
        assertRejected(submit(content(
            "[{\"type\":\"image_url\",\"image_url\":\"https://cdn.example.com/out.png\"}]")), "OUTPUT_MISSING", false);
    }

    @Test
    void nonArrayContentIsOutputMissing() {
        assertRejected(submit(content("\"just a string\"")), "OUTPUT_MISSING", false);
    }

    @Test
    void emptyChoicesIsOutputMissing() {
        assertRejected(submit("{\"choices\":[]}"), "OUTPUT_MISSING", false);
    }

    /** choices[0] present but not an object: must not throw (latent-trap hardening). */
    @Test
    void nonObjectFirstChoiceIsOutputMissing() {
        assertRejected(submit("{\"choices\":[\"not-an-object\"]}"), "OUTPUT_MISSING", false);
    }

    @Test
    void missingChoicesIsOutputMissing() {
        assertRejected(submit("{}"), "OUTPUT_MISSING", false);
    }

    @Test
    void missingMessageIsOutputMissing() {
        assertRejected(submit("{\"choices\":[{}]}"), "OUTPUT_MISSING", false);
    }

    @Test
    void oversizedBodyIsIndeterminate() {
        GenerationSubmission submission = mapper.mapRun(
            InferHubHttpResponse.http(200, json(content("[]")), true)).submission();
        assertThat(submission).isInstanceOf(GenerationSubmission.Indeterminate.class);
        assertThat(((GenerationSubmission.Indeterminate) submission).failure().safeCode())
            .isEqualTo("OUTPUT_TOO_LARGE");
    }

    @Test
    void unsentAndIndeterminateOutcomesClassifyAsRetryableNetworkFailures() {
        GenerationSubmission unsent = mapper.mapRun(InferHubHttpResponse.unsent()).submission();
        assertThat(((GenerationSubmission.Rejected) unsent).failure().safeCode()).isEqualTo("CONNECTION_FAILED");
        assertThat(((GenerationSubmission.Rejected) unsent).failure().retryable()).isTrue();
        assertThat(mapper.mapRun(InferHubHttpResponse.indeterminate()).submission())
            .isInstanceOf(GenerationSubmission.Indeterminate.class);
    }

    /** 502/503 are transient upstream: retryable, never permanent. */
    @Test
    void transientStatusesAreRetryable() {
        for (int status : new int[] {502, 503}) {
            GenerationSubmission submission = mapper.mapRun(
                InferHubHttpResponse.http(status, json("{}"), false)).submission();
            assertThat(submission).isInstanceOf(GenerationSubmission.Rejected.class);
            assertThat(((GenerationSubmission.Rejected) submission).failure().retryable())
                .as("status " + status + " must be retryable").isTrue();
        }
    }

    @Test
    void nonRetryableStatusIsPermanent() {
        GenerationSubmission submission = mapper.mapRun(
            InferHubHttpResponse.http(400, json("{}"), false)).submission();
        assertThat(((GenerationSubmission.Rejected) submission).failure().retryable()).isFalse();
    }
}
