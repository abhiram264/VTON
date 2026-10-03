package com.aifashionvault.platform.inferhub;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.aifashionvault.generation.vton.GenerationStatus;
import com.aifashionvault.generation.vton.GenerationSubmission;
import com.aifashionvault.generation.vton.ItemCategory;
import com.aifashionvault.generation.vton.ProviderFailure;
import com.aifashionvault.generation.vton.ProviderFailureCategory;
import com.aifashionvault.generation.vton.StatusQueryResult;
import com.aifashionvault.generation.vton.VtonMode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Unit tests for the InferHub wire mapping: request serialization, response
 * parsing of the owner-verified shape, malformed-response classification and
 * API-error retryability. Pure translation logic — no HTTP, no key material.
 */
class InferHubMapperContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final byte[] PNG = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
    private static final String AVATAR_URI = "data:image/png;base64,"
        + Base64.getEncoder().encodeToString(PNG);
    private static final String GARMENT_URI = "data:image/jpeg;base64,"
        + Base64.getEncoder().encodeToString("garment-bytes".getBytes(StandardCharsets.UTF_8));

    private static final VtonMode STANDARD = new VtonMode("standard");
    private static final ItemCategory CLOTHING = new ItemCategory("clothing");

    private final InferHubMapper mapper = new InferHubMapper();

    private static JsonNode body(String json) {
        return MAPPER.readTree(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String successBody(String dataUri) {
        return "{\"choices\":[{\"message\":{\"content\":["
            + "{\"type\":\"text\",\"text\":\"done\"},"
            + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"" + dataUri + "\"}}"
            + "]}}]}";
    }

    // --- request serialization -------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void runRequestPinsModelAndTypedMultimodalContentArray() {
        Map<String, Object> root = mapper.runRequest(STANDARD, AVATAR_URI, GARMENT_URI, CLOTHING, Optional.empty());

        assertThat(InferHubMapper.MODEL).isEqualTo("cb/gemini-3.1-flash-image");
        assertThat(root.get("model")).isEqualTo("cb/gemini-3.1-flash-image");

        List<Object> messages = (List<Object>) root.get("messages");
        assertThat(messages).hasSize(1);
        Map<String, Object> message = (Map<String, Object>) messages.get(0);
        assertThat(message.get("role")).isEqualTo("user");

        // The content must be an ARRAY of typed parts, not a bare string.
        List<Object> content = (List<Object>) message.get("content");
        assertThat(content).hasSize(3);

        Map<String, Object> text = (Map<String, Object>) content.get(0);
        assertThat(text.get("type")).isEqualTo("text");
        assertThat((String) text.get("text")).contains("clothing").contains("standard");

        Map<String, Object> avatarPart = (Map<String, Object>) content.get(1);
        assertThat(avatarPart.get("type")).isEqualTo("image_url");
        assertThat(((Map<String, Object>) avatarPart.get("image_url")).get("url")).isEqualTo(AVATAR_URI);

        Map<String, Object> garmentPart = (Map<String, Object>) content.get(2);
        assertThat(garmentPart.get("type")).isEqualTo("image_url");
        assertThat(((Map<String, Object>) garmentPart.get("image_url")).get("url")).isEqualTo(GARMENT_URI);
    }

    @Test
    @SuppressWarnings("unchecked")
    void runRequestUsesInstructionsAsTheTextPartWhenPresent() {
        Map<String, Object> root = mapper.runRequest(
            STANDARD, AVATAR_URI, GARMENT_URI, CLOTHING, Optional.of("Keep the jacket red."));

        List<Object> content = (List<Object>) ((Map<String, Object>) ((List<Object>) root.get("messages")).get(0)).get("content");
        assertThat(((Map<String, Object>) content.get(0)).get("text")).isEqualTo("Keep the jacket red.");
    }

    // --- response parsing of the owner-verified shape ---------------------------

    @Test
    void mapRunAcceptsTheVerifiedTextThenImageShape() {
        InferHubHttpResponse response = InferHubHttpResponse.http(
            200, body(successBody(AVATAR_URI)), false);

        InferHubMapper.Mapped mapped = mapper.mapRun(response);

        assertThat(mapped.submission()).isInstanceOf(GenerationSubmission.Accepted.class);
        assertThat(mapped.dataUri()).isEqualTo(AVATAR_URI);
    }

    @Test
    void decodeDataUriRoundTripsDecodedPngBytes() {
        InferHubMapper.DecodedImage image = mapper.decodeDataUri(AVATAR_URI);

        assertThat(image.mediaType()).isEqualTo("image/png");
        assertThat(image.bytes()).isEqualTo(PNG);
    }

    @Test
    void decodeDataUriRejectsMalformedValuesAsClassifiedFailures() {
        assertThatExceptionOfType(InferHubProviderException.class)
            .isThrownBy(() -> mapper.decodeDataUri("https://images.test/out.png"))
            .satisfies(ex -> {
                assertThat(ex.failure().category()).isEqualTo(ProviderFailureCategory.PROTOCOL_VIOLATION);
                assertThat(ex.failure().retryable()).isFalse();
                assertThat(ex.failure().safeCode()).isEqualTo("OUTPUT_MALFORMED");
            });
        assertThatExceptionOfType(InferHubProviderException.class)
            .isThrownBy(() -> mapper.decodeDataUri("data:image/png;base64,!!!not-base64!!!"))
            .withMessage("OUTPUT_MALFORMED");
        assertThatExceptionOfType(InferHubProviderException.class)
            .isThrownBy(() -> mapper.decodeDataUri("data:text/plain;base64," + Base64.getEncoder().encodeToString(PNG)))
            .withMessage("OUTPUT_NOT_IMAGE");
    }

    // --- malformed responses: classified ProviderFailure, never a crash ---------

    @Test
    void responseWithoutAnyImagePartIsRejectedAsOutputMissing() {
        String json = "{\"choices\":[{\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"done\"}]}}]}";
        InferHubMapper.Mapped mapped = mapper.mapRun(InferHubHttpResponse.http(200, body(json), false));

        GenerationSubmission.Rejected rejected = (GenerationSubmission.Rejected) mapped.submission();
        assertThat(rejected.failure().category()).isEqualTo(ProviderFailureCategory.PROTOCOL_VIOLATION);
        assertThat(rejected.failure().retryable()).isFalse();
        assertThat(rejected.failure().safeCode()).isEqualTo("OUTPUT_MISSING");
        assertThat(mapped.dataUri()).isNull();
    }

    @Test
    void remoteImageUrlIsRejectedAsOutputUrlAndNeverDereferenced() {
        String json = successBody("https://cdn.inferhub.dev/output/123.png");
        InferHubMapper.Mapped mapped = mapper.mapRun(InferHubHttpResponse.http(200, body(json), false));

        GenerationSubmission.Rejected rejected = (GenerationSubmission.Rejected) mapped.submission();
        assertThat(rejected.failure().category()).isEqualTo(ProviderFailureCategory.PROTOCOL_VIOLATION);
        assertThat(rejected.failure().retryable()).isFalse();
        assertThat(rejected.failure().safeCode()).isEqualTo("OUTPUT_URL");
        // The load-bearing safety property: a non-inline URL is never surfaced as the output
        // payload, so nothing downstream can dereference it.
        assertThat(mapped.dataUri()).isNull();
    }

    @Test
    void emptyChoicesIsRejectedAsOutputMissing() {
        InferHubMapper.Mapped mapped = mapper.mapRun(
            InferHubHttpResponse.http(200, body("{\"choices\":[]}"), false));

        GenerationSubmission.Rejected rejected = (GenerationSubmission.Rejected) mapped.submission();
        assertThat(rejected.failure().safeCode()).isEqualTo("OUTPUT_MISSING");
    }

    @Test
    void missingContentIsRejectedAsOutputMissing() {
        String json = "{\"choices\":[{\"message\":{\"role\":\"assistant\"}}]}";
        InferHubMapper.Mapped mapped = mapper.mapRun(InferHubHttpResponse.http(200, body(json), false));

        GenerationSubmission.Rejected rejected = (GenerationSubmission.Rejected) mapped.submission();
        assertThat(rejected.failure().safeCode()).isEqualTo("OUTPUT_MISSING");
    }

    @Test
    void nullBodyIsRejectedAsOutputMissing() {
        InferHubMapper.Mapped mapped = mapper.mapRun(InferHubHttpResponse.http(200, null, false));

        GenerationSubmission.Rejected rejected = (GenerationSubmission.Rejected) mapped.submission();
        assertThat(rejected.failure().safeCode()).isEqualTo("OUTPUT_MISSING");
    }

    // --- outcome classification --------------------------------------------------

    @Test
    void unsentOutcomeMapsToRetryableNetworkFailure() {
        GenerationSubmission.Rejected rejected =
            (GenerationSubmission.Rejected) mapper.mapRun(InferHubHttpResponse.unsent()).submission();

        assertThat(rejected.failure().category()).isEqualTo(ProviderFailureCategory.TIMEOUT_NETWORK);
        assertThat(rejected.failure().retryable()).isTrue();
    }

    @Test
    void indeterminateOutcomeMapsToRetryableFailure() {
        GenerationSubmission.Indeterminate indeterminate =
            (GenerationSubmission.Indeterminate) mapper.mapRun(InferHubHttpResponse.indeterminate()).submission();

        assertThat(indeterminate.failure().retryable()).isTrue();
    }

    @Test
    void oversizedBodyMapsToRetryableProtocolFailure() {
        GenerationSubmission.Indeterminate indeterminate =
            (GenerationSubmission.Indeterminate) mapper.mapRun(
                InferHubHttpResponse.http(200, body(successBody(AVATAR_URI)), true)).submission();

        assertThat(indeterminate.failure().safeCode()).isEqualTo("OUTPUT_TOO_LARGE");
        assertThat(indeterminate.failure().retryable()).isTrue();
    }

    // --- API-error classification ------------------------------------------------

    @Test
    void upstream502And503AreRetryable() {
        for (int status : new int[] {502, 503}) {
            ProviderFailure failure = mapper.mapApiError(status);
            assertThat(failure.category()).isEqualTo(ProviderFailureCategory.TEMPORARY_UNAVAILABLE);
            assertThat(failure.retryable()).as("status %d", status).isTrue();
        }
    }

    @Test
    void clientErrorsAreNotRetryable() {
        ProviderFailure auth = mapper.mapApiError(401);
        assertThat(auth.category()).isEqualTo(ProviderFailureCategory.AUTH_CONFIGURATION);
        assertThat(auth.retryable()).isFalse();

        ProviderFailure badRequest = mapper.mapApiError(400);
        assertThat(badRequest.category()).isEqualTo(ProviderFailureCategory.INVALID_REQUEST);
        assertThat(badRequest.retryable()).isFalse();
    }

    @Test
    void everyMappedFailureCarriesTheContractVersion() {
        for (ProviderFailure failure : List.of(
            mapper.mapApiError(502),
            mapper.mapApiError(503),
            mapper.mapApiError(401),
            mapper.mapApiError(429),
            mapper.mapApiError(400),
            InferHubMapper.failure(ProviderFailureCategory.PROTOCOL_VIOLATION, false, "X"))) {
            assertThat(failure.mappingContractVersion()).isEqualTo(InferHubMapper.ERROR_MAPPING_CONTRACT_VERSION);
        }
    }

    // --- status/output-ref seam ---------------------------------------------------

    @Test
    void cachedOutputReportsSucceededAndMissReportsFailed() {
        InferHubMapper.Mapped mapped = mapper.mapRun(
            InferHubHttpResponse.http(200, body(successBody(AVATAR_URI)), false));
        var jobId = ((GenerationSubmission.Accepted) mapped.submission()).providerJobId();

        StatusQueryResult cachedResult = mapper.mapStatus(jobId, true);
        StatusQueryResult.Observed cached = (StatusQueryResult.Observed) cachedResult;
        GenerationStatus.Succeeded succeeded = (GenerationStatus.Succeeded) cached.status();
        assertThat(succeeded.outputs()).hasSize(1);
        assertThat(succeeded.outputs().get(0).value()).isEqualTo(jobId.value() + ":0");

        StatusQueryResult.Observed missed = (StatusQueryResult.Observed) mapper.mapStatus(jobId, false);
        GenerationStatus.Failed failed = (GenerationStatus.Failed) missed.status();
        assertThat(failed.failure().safeCode()).isEqualTo("OUTPUT_MISSING");
    }
}
