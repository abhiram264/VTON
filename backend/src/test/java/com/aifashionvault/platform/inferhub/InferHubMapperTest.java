package com.aifashionvault.platform.inferhub;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.aifashionvault.generation.vton.GenerationSubmission;
import com.aifashionvault.generation.vton.ProviderFailureCategory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Malformed-shape hardening for the InferHub submit path.
 *
 * <p>Scope is deliberately narrow: {@link InferHubMapperContractTest} already pins the
 * primary wire shapes (verified text-then-image content, OUTPUT_MISSING, OUTPUT_URL,
 * status/outcome classification, contract version, cached-output status). This class
 * covers only the shapes that one suite would otherwise leave unpinned — the
 * non-object/blank variants that reach the same classifier through a different guard —
 * so the two files complement rather than duplicate each other.
 *
 * <p>Why these matter: every malformed shape must end as a CLASSIFIED outcome. An
 * exception escaping {@code mapRun} is caught by the worker and misrouted to
 * submission-indeterminate, which re-queues a permanent protocol violation until the
 * recovery deadline instead of terminating it.
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

    private static void assertOutputMissing(GenerationSubmission submission) {
        assertThat(submission).isInstanceOf(GenerationSubmission.Rejected.class);
        GenerationSubmission.Rejected rejected = (GenerationSubmission.Rejected) submission;
        assertThat(rejected.failure().category()).isEqualTo(ProviderFailureCategory.PROTOCOL_VIOLATION);
        assertThat(rejected.failure().safeCode()).isEqualTo("OUTPUT_MISSING");
        assertThat(rejected.failure().retryable()).isFalse();
    }

    /** choices[0] present but not an object: must not throw (the isObject guard). */
    @Test
    void nonObjectFirstChoiceIsOutputMissing() {
        assertOutputMissing(submit("{\"choices\":[\"not-an-object\"]}"));
    }

    /** image_url present but not an object (a bare string). */
    @Test
    void nonObjectImageUrlIsOutputMissing() {
        assertOutputMissing(submit(content(
            "[{\"type\":\"image_url\",\"image_url\":\"https://cdn.example.com/out.png\"}]")));
    }

    /** A blank url is a malformed part, not a URL classification. */
    @Test
    void blankUrlIsOutputMissing() {
        assertOutputMissing(submit(content(
            "[{\"type\":\"image_url\",\"image_url\":{\"url\":\"   \"}}]")));
    }

    /** content present but not an array (distinct from content absent). */
    @Test
    void nonArrayContentIsOutputMissing() {
        assertOutputMissing(submit(content("\"just a string\"")));
    }

    /**
     * A non-data, non-http scheme (file:, ftp:) is still a URL rather than an inline image,
     * so it takes the permanent OUTPUT_URL classification and never reaches the decoder.
     */
    @Test
    void nonDataNonHttpSchemeIsAUrlRejection() {
        for (String url : new String[] {"file:///tmp/out.png", "ftp://cdn.example.com/out.png"}) {
            GenerationSubmission submission = submit(content(
                "[{\"type\":\"image_url\",\"image_url\":{\"url\":\"" + url + "\"}}]"));
            assertThat(submission).as("scheme of %s", url)
                .isInstanceOf(GenerationSubmission.Rejected.class);
            GenerationSubmission.Rejected rejected = (GenerationSubmission.Rejected) submission;
            assertThat(rejected.failure().category()).isEqualTo(ProviderFailureCategory.PROTOCOL_VIOLATION);
            assertThat(rejected.failure().safeCode()).as("scheme of %s", url).isEqualTo("OUTPUT_URL");
            assertThat(rejected.failure().retryable()).as("scheme of %s", url).isFalse();
            assertThat(submission).as("scheme of %s", url)
                .isNotInstanceOf(GenerationSubmission.Indeterminate.class);
        }
    }

    /** No malformed shape may escape as an exception (the misrouting regression). */
    @Test
    void everyMalformedShapeIsClassifiedRatherThanThrown() {
        String[] malformed = {
            "{\"choices\":[\"not-an-object\"]}",
            "{\"choices\":[{}]}",
            "{\"choices\":[]}",
            "{}",
            content("\"just a string\""),
            content("[]"),
            content("[{\"type\":\"image_url\"}]"),
            content("[{\"type\":\"image_url\",\"image_url\":{}}]"),
            content("[{\"type\":\"image_url\",\"image_url\":{\"url\":\"\"}}]"),
            content("[{\"type\":\"image_url\",\"image_url\":{\"url\":\"file:///tmp/x.png\"}}]"),
        };
        for (String body : malformed) {
            assertThat(submit(body)).as("shape: %s", body)
                .isInstanceOf(GenerationSubmission.Rejected.class);
        }
    }
}
