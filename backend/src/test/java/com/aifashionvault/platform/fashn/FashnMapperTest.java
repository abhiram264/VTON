package com.aifashionvault.platform.fashn;

import java.util.Base64;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aifashionvault.generation.vton.AttemptRedeliveryPolicy;

import com.aifashionvault.generation.vton.GenerationStatus;
import com.aifashionvault.generation.vton.GenerationSubmission;
import com.aifashionvault.generation.vton.InputTransferMethod;
import com.aifashionvault.generation.vton.ItemCategory;
import com.aifashionvault.generation.vton.OutputDeliveryMethod;
import com.aifashionvault.generation.vton.ProviderFailure;
import com.aifashionvault.generation.vton.ProviderFailureCategory;
import com.aifashionvault.generation.vton.ProviderJobId;
import com.aifashionvault.generation.vton.ProviderOutputRef;
import com.aifashionvault.generation.vton.StatusQueryResult;
import com.aifashionvault.generation.vton.SubmissionReplaySafety;
import com.aifashionvault.platform.objectstore.StorageBoundary;
import com.aifashionvault.shared.StorageKey;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class FashnMapperTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final FashnMapper mapper = new FashnMapper();

    @Test
    void capabilitiesAreLocalDeterministicAndUnsafe() {
        var first = mapper.capabilities();
        var second = mapper.capabilities();

        assertThat(first.submissionReplaySafety()).isEqualTo(SubmissionReplaySafety.UNSAFE);
        assertThat(first.inputTransfers()).containsExactlyInAnyOrder(
            InputTransferMethod.BOUNDED_INLINE_DATA, InputTransferMethod.EPHEMERAL_SIGNED_READ);
        assertThat(first.outputDeliveries()).containsExactly(OutputDeliveryMethod.BOUNDED_INLINE_DATA);
        assertThat(first.forMode(FashnMapper.FAST_PREVIEW)).hasValueSatisfying(mode -> {
            assertThat(mode.maxProductAssets()).isEqualTo(1);
            assertThat(mode.instructionsSupported()).isFalse();
            assertThat(mode.supportedCategories()).containsExactlyInAnyOrder(
                new ItemCategory("clothing"), new ItemCategory("tops"), new ItemCategory("bottoms"), new ItemCategory("one-pieces"));
        });
        assertThat(first.forMode(FashnMapper.STANDARD)).hasValueSatisfying(mode -> {
            assertThat(mode.maxProductAssets()).isEqualTo(1);
            assertThat(mode.instructionsSupported()).isTrue();
            assertThat(mode.supportedCategories()).contains(
                new ItemCategory("footwear"), new ItemCategory("jewellery"), new ItemCategory("jewelry"), new ItemCategory("bag"));
        });
        assertThat(first.forMode(FashnMapper.HD).orElseThrow().instructionsSupported()).isTrue();
        assertThat(first).isEqualTo(second);
        assertThat(first.forMode(FashnMapper.FAST_PREVIEW).orElseThrow().maxProductAssets()).isEqualTo(1);
        assertThat(first.forMode(FashnMapper.STANDARD).orElseThrow().maxProductAssets()).isEqualTo(1);
        assertThat(first.forMode(FashnMapper.HD).orElseThrow().maxProductAssets()).isEqualTo(1);
        assertThat(first.submissionReplaySafety()).isNotEqualTo(SubmissionReplaySafety.SAME_KEY_SAFE);
        assertThat(mapper.callbackRecovery()).isFalse();
        assertThat(AttemptRedeliveryPolicy.decide(false, Optional.empty(), true, first.submissionReplaySafety()))
            .isEqualTo(AttemptRedeliveryPolicy.Decision.BLOCK_UNSAFE_REPLAY);
        assertThat(AttemptRedeliveryPolicy.failUnsafeAmbiguityPromptly(mapper.callbackRecovery(), Duration.ZERO)).isTrue();

    }

    @Test
    void fastPreviewRequestUsesV16PerformanceRoute() {
        Map<String, Object> request = mapper.runRequest(
            FashnMapper.FAST_PREVIEW, "avatar", "garment", new ItemCategory("clothing"), Optional.of("ignored"));

        assertThat(request.get("model_name")).isEqualTo("tryon-v1.6");
        @SuppressWarnings("unchecked")
        Map<String, Object> inputs = (Map<String, Object>) request.get("inputs");
        assertThat(inputs).containsEntry("model_image", "avatar")
            .containsEntry("garment_image", "garment")
            .containsEntry("category", "auto")
            .containsEntry("mode", "performance")
            .containsEntry("num_samples", 1)
            .containsEntry("return_base64", true)
            .doesNotContainKeys("product_image", "prompt", "resolution", "generation_mode", "num_images", "webhook_url");
        assertThat(mapper.runRequest(FashnMapper.FAST_PREVIEW, "avatar", "garment", new ItemCategory("tops"), Optional.empty())
            .get("inputs")).isInstanceOfSatisfying(Map.class, mapped -> assertThat(mapped).containsEntry("category", "tops"));
        assertThat(mapper.runRequest(FashnMapper.FAST_PREVIEW, "avatar", "garment", new ItemCategory("bottoms"), Optional.empty())
            .get("inputs")).isInstanceOfSatisfying(Map.class, mapped -> assertThat(mapped).containsEntry("category", "bottoms"));
        assertThat(mapper.runRequest(FashnMapper.FAST_PREVIEW, "avatar", "garment", new ItemCategory("one-pieces"), Optional.empty())
            .get("inputs")).isInstanceOfSatisfying(Map.class, mapped -> assertThat(mapped).containsEntry("category", "one-pieces"));
    }

    @Test
    void standardAndHdRequestUseTryOnMaxRoutes() {
        Map<String, Object> standard = mapper.runRequest(
            FashnMapper.STANDARD, "avatar", "product", new ItemCategory("footwear"), Optional.of("keep logo"));
        Map<String, Object> hd = mapper.runRequest(
            FashnMapper.HD, "avatar", "product", new ItemCategory("bag"), Optional.empty());

        assertThat(standard.get("model_name")).isEqualTo("tryon-max");
        assertThat(hd.get("model_name")).isEqualTo("tryon-max");
        @SuppressWarnings("unchecked")
        Map<String, Object> standardInputs = (Map<String, Object>) standard.get("inputs");
        @SuppressWarnings("unchecked")
        Map<String, Object> hdInputs = (Map<String, Object>) hd.get("inputs");
        assertThat(standardInputs).containsEntry("product_image", "product")
            .containsEntry("generation_mode", "fast")
            .containsEntry("resolution", "1k")
            .containsEntry("num_images", 1)
            .containsEntry("return_base64", true)
            .containsEntry("prompt", "keep logo")
            .doesNotContainKeys("garment_image", "mode", "num_samples");
        assertThat(hdInputs).containsEntry("generation_mode", "balanced")
            .containsEntry("resolution", "2k")
            .doesNotContainKeys("prompt", "garment_image");
        assertThat(hdInputs.get("resolution")).isNotEqualTo("4k");
    }

    @Test
    void runMappingCoversAcceptanceRejectionAndIndeterminate() {
        assertThat(mapper.mapRun(FashnHttpResponse.http(200, "2", JSON.readTree(FashnTestSupport.runSuccess()), false)))
            .isEqualTo(new GenerationSubmission.Accepted(new ProviderJobId(FashnTestSupport.PREDICTION_ID), Optional.empty()));
        assertThat(mapper.mapRun(FashnHttpResponse.http(200, null, JSON.readTree("{\"id\":\"\",\"error\":null}"), false)))
            .isInstanceOf(GenerationSubmission.Indeterminate.class);
        assertThat(mapper.mapRun(FashnHttpResponse.unsent()))
            .isInstanceOfSatisfying(GenerationSubmission.Rejected.class, rejected -> {
                assertThat(rejected.failure().retryable()).isTrue();
                assertThat(rejected.failure().category()).isEqualTo(ProviderFailureCategory.TIMEOUT_NETWORK);
            });
        assertThat(mapper.mapRun(FashnHttpResponse.indeterminate())).isInstanceOf(GenerationSubmission.Indeterminate.class);
        assertRejected(mapper.mapRun(FashnHttpResponse.http(400, null, error("BadRequest"), false)), ProviderFailureCategory.INVALID_REQUEST, false);
        assertRejected(mapper.mapRun(FashnHttpResponse.http(401, null, error("UnauthorizedAccess"), false)), ProviderFailureCategory.AUTH_CONFIGURATION, false);
        assertRejected(mapper.mapRun(FashnHttpResponse.http(429, null, error("RateLimitExceeded"), false)), ProviderFailureCategory.RATE_CAPACITY, true);
        assertRejected(mapper.mapRun(FashnHttpResponse.http(429, null, error("ConcurrencyLimitExceeded"), false)), ProviderFailureCategory.RATE_CAPACITY, true);
        assertRejected(mapper.mapRun(FashnHttpResponse.http(429, null, error("OutOfCredits"), false)), ProviderFailureCategory.QUOTA_BILLING, false);
        assertRejected(mapper.mapRun(FashnHttpResponse.http(500, null, error("InternalServerError"), false)), ProviderFailureCategory.TEMPORARY_UNAVAILABLE, true);
    }

    @Test
    void unknownHttpStatusFailsClosed() {
        assertThat(mapper.mapRun(FashnHttpResponse.http(503, null, error("GatewayFailure"), false)))
            .isInstanceOfSatisfying(GenerationSubmission.Rejected.class, rejected -> {
                assertThat(rejected.failure().category()).isEqualTo(ProviderFailureCategory.UNKNOWN_PROVIDER_FAILURE);
                assertThat(rejected.failure().retryable()).isFalse();
                assertThat(rejected.failure().safeCode()).isEqualTo("UNKNOWN_PROVIDER_FAILURE");
            });
    }

    @Test
    void unknownLifecycleStatusFailsClosed() {
        assertThat(mapper.mapStatus(FashnTestSupport.PREDICTION_ID, httpStatus("warming_up", "null")))
            .isInstanceOfSatisfying(StatusQueryResult.Unavailable.class, unavailable -> {
                assertThat(unavailable.failure().category()).isEqualTo(ProviderFailureCategory.UNKNOWN_PROVIDER_FAILURE);
                assertThat(unavailable.failure().retryable()).isFalse();
                assertThat(unavailable.failure().safeCode()).isEqualTo("UNKNOWN_STATUS");
            });
    }

    @Test
    void retryAfterParserAcceptsDeltaSecondsAndHttpDate() {
        Instant responseTime = Instant.parse("2015-10-21T07:27:00Z");

        assertThat(FashnHttpResponse.parseRetryAfter("120", responseTime))
            .isEqualTo(Instant.parse("2015-10-21T07:29:00Z"));
        assertThat(FashnHttpResponse.parseRetryAfter("Wed, 21 Oct 2015 07:28:00 GMT", responseTime))
            .isEqualTo(Instant.parse("2015-10-21T07:28:00Z"));
    }

    @Test
    void retryAfterParserRejectsMalformedNegativeOverflowAndPastGuidance() {
        Instant responseTime = Instant.parse("2015-10-21T07:27:00Z");

        assertThat(FashnHttpResponse.parseRetryAfter(null, responseTime)).isNull();
        assertThat(FashnHttpResponse.parseRetryAfter("", responseTime)).isNull();
        assertThat(FashnHttpResponse.parseRetryAfter("-1", responseTime)).isNull();
        assertThat(FashnHttpResponse.parseRetryAfter("1.5", responseTime)).isNull();
        assertThat(FashnHttpResponse.parseRetryAfter("9223372036854775808", responseTime)).isNull();
        assertThat(FashnHttpResponse.parseRetryAfter("Tue, 20 Oct 2015 07:28:00 GMT", responseTime)).isNull();
    }

    @Test
    void retryAfterHttpDateResolvesAgainstResponseDateWhenPresent() {
        Instant responseTime = Instant.parse("2015-10-21T07:27:00Z");
        Instant dateHeaderAt = Instant.parse("2015-10-21T07:25:00Z");

        assertThat(FashnHttpResponse.parseRetryAfter("Wed, 21 Oct 2015 07:28:00 GMT", responseTime, dateHeaderAt))
            .isEqualTo(Instant.parse("2015-10-21T07:28:00Z"));
        // Past relative to receipt but future relative to the Date header: kept.
        assertThat(FashnHttpResponse.parseRetryAfter("Wed, 21 Oct 2015 07:26:00 GMT", responseTime, dateHeaderAt))
            .isEqualTo(Instant.parse("2015-10-21T07:26:00Z"));
        // Same value without a Date header resolves against receipt: dropped as past.
        assertThat(FashnHttpResponse.parseRetryAfter("Wed, 21 Oct 2015 07:26:00 GMT", responseTime, null))
            .isNull();
        // Delta-seconds stay anchored to the receipt instant.
        assertThat(FashnHttpResponse.parseRetryAfter("120", responseTime, dateHeaderAt))
            .isEqualTo(Instant.parse("2015-10-21T07:29:00Z"));
        assertThat(FashnHttpResponse.parseRetryAfter("Tue, 20 Oct 2015 07:28:00 GMT", responseTime, dateHeaderAt))
            .isNull();
    }

    @Test
    void everyAdapterFailureCarriesTheMappingContractVersion() {
        String version = FashnMapper.ERROR_MAPPING_CONTRACT_VERSION;
        assertThat(version).isEqualTo("fashn-mapping-v1");

        assertThat(mapper.mapRun(FashnHttpResponse.http(400, null, error("BadRequest"), false)))
            .isInstanceOfSatisfying(GenerationSubmission.Rejected.class, rejected ->
                assertThat(rejected.failure().mappingContractVersion()).isEqualTo(version));
        assertThat(mapper.mapRun(FashnHttpResponse.unsent()))
            .isInstanceOfSatisfying(GenerationSubmission.Rejected.class, rejected ->
                assertThat(rejected.failure().mappingContractVersion()).isEqualTo(version));
        assertThat(mapper.mapRun(FashnHttpResponse.indeterminate()))
            .isInstanceOfSatisfying(GenerationSubmission.Indeterminate.class, indeterminate ->
                assertThat(indeterminate.failure().mappingContractVersion()).isEqualTo(version));
        assertThat(mapper.mapStatus(FashnTestSupport.PREDICTION_ID, httpStatus("failed", "null")))
            .isInstanceOfSatisfying(StatusQueryResult.Observed.class, observed ->
                assertThat(((GenerationStatus.Failed) observed.status()).failure().mappingContractVersion())
                    .isEqualTo(version));
        assertThat(mapper.mapStatus(FashnTestSupport.PREDICTION_ID,
            FashnHttpResponse.http(429, null, error("RateLimitExceeded"), false)))
            .isInstanceOfSatisfying(StatusQueryResult.Unavailable.class, unavailable ->
                assertThat(unavailable.failure().mappingContractVersion()).isEqualTo(version));
        assertThat(mapper.mapApiError(404, JSON.readTree("{\"error\":\"NotFound\"}")).mappingContractVersion())
            .isEqualTo(version);
        assertThat(mapper.mapRuntimeError(JSON.readTree("{\"name\":\"TimeoutError\"}")).mappingContractVersion())
            .isEqualTo(version);
        assertThat(FashnMapper.failure(ProviderFailureCategory.INVALID_REQUEST, false, "MEDIA_TYPE").mappingContractVersion())
            .isEqualTo(version);
    }

    @Test
    void rateLimitCarriesSanitizedRetryAfterIntoNormalizedFailure() {
        Instant retryAfter = Instant.parse("2026-08-28T12:34:56Z");

        GenerationSubmission submission = mapper.mapRun(FashnHttpResponse.http(
            429, null, error("RateLimitExceeded"), false, retryAfter));

        assertThat(submission).isInstanceOfSatisfying(GenerationSubmission.Rejected.class, rejected -> {
            assertThat(rejected.failure().category()).isEqualTo(ProviderFailureCategory.RATE_CAPACITY);
            assertThat(rejected.failure().retryable()).isTrue();
            assertThat(rejected.failure().retryAfterAt()).isEqualTo(retryAfter);
        });

        StatusQueryResult status = mapper.mapStatus(
            FashnTestSupport.PREDICTION_ID,
            FashnHttpResponse.http(429, null, error("RateLimitExceeded"), false, retryAfter));
        assertThat(status).isInstanceOfSatisfying(StatusQueryResult.Unavailable.class, unavailable -> {
            assertThat(unavailable.failure().retryable()).isTrue();
            assertThat(unavailable.failure().retryAfterAt()).isEqualTo(retryAfter);
        });
    }

    @Test
    void statusMappingNormalizesKnownTokensAndLeavesUnknownUnavailable() {
        assertThat(observed(mapper.mapStatus(FashnTestSupport.PREDICTION_ID, httpStatus("starting", "null"))))
            .isInstanceOf(GenerationStatus.Queued.class);
        assertThat(observed(mapper.mapStatus(FashnTestSupport.PREDICTION_ID, httpStatus("in_queue", "null"))))
            .isInstanceOf(GenerationStatus.Queued.class);
        assertThat(observed(mapper.mapStatus(FashnTestSupport.PREDICTION_ID, httpStatus("processing", "null"))))
            .isInstanceOf(GenerationStatus.Running.class);
        assertThat(observed(mapper.mapStatus(FashnTestSupport.PREDICTION_ID, FashnHttpResponse.http(200, "3", JSON.readTree(FashnTestSupport.completedImage()), false))))
            .isInstanceOfSatisfying(GenerationStatus.Succeeded.class, succeeded -> {
                assertThat(succeeded.outputs()).containsExactly(new ProviderOutputRef(FashnTestSupport.PREDICTION_ID + ":0"));
                assertThat(succeeded.outputs().get(0).value()).doesNotContain("data:", "https:");
                assertThat(succeeded.usage()).isEmpty();
            });
        assertThat(observed(mapper.mapStatus(FashnTestSupport.PREDICTION_ID, httpStatus("failed", "null"))))
            .isInstanceOf(GenerationStatus.Failed.class);
        assertThat(mapper.mapStatus(FashnTestSupport.PREDICTION_ID, httpStatus("warming_up", "null")))
            .isInstanceOf(StatusQueryResult.Unavailable.class);
        assertThat(mapper.mapStatus(FashnTestSupport.PREDICTION_ID, FashnHttpResponse.http(200, null, JSON.readTree("{\"id\":\"" + FashnTestSupport.PREDICTION_ID + "\"}"), false)))
            .isInstanceOf(StatusQueryResult.Unavailable.class);
    }

    @Test
    void completedOutputContractFailuresStayTechnical() {
        assertFailedCode(mapper.mapStatus(FashnTestSupport.PREDICTION_ID, httpStatus("completed", "[]")), "OUTPUT_MISSING");
        assertFailedCode(mapper.mapStatus(FashnTestSupport.PREDICTION_ID, httpStatus("completed", "null")), "OUTPUT_MISSING");
        assertFailedCode(mapper.mapStatus(FashnTestSupport.PREDICTION_ID, httpStatus("completed", "[\"" + FashnTestSupport.PNG_DATA_URI + "\",\"" + FashnTestSupport.PNG_DATA_URI + "\"]")), "OUTPUT_COUNT");
        assertFailedCode(mapper.mapStatus(FashnTestSupport.PREDICTION_ID, httpStatus("completed", "[\"https://cdn.fashn.ai/out.png\"]")), "OUTPUT_URL");
        assertFailedCode(mapper.mapStatus(FashnTestSupport.PREDICTION_ID, httpStatus("completed", "[\"_expired\"]")), "OUTPUT_EXPIRED");
    }

    @Test
    void runtimeErrorsMapToNeutralFailuresWithoutProviderNames() {
        assertRuntime("ImageLoadError", ProviderFailureCategory.INVALID_REQUEST, false, "IMAGE_LOAD");
        assertRuntime("InputValidationError", ProviderFailureCategory.INVALID_REQUEST, false, "INPUT_VALIDATION");
        assertRuntime("PoseError", ProviderFailureCategory.INVALID_REQUEST, false, "POSE");
        assertRuntime("ContentModerationError", ProviderFailureCategory.CONTENT_POLICY_REJECTION, false, "CONTENT_POLICY");
        assertRuntime("UnavailableError", ProviderFailureCategory.TEMPORARY_UNAVAILABLE, true, "PROVIDER_UNAVAILABLE");
        assertRuntime("ThirdPartyError", ProviderFailureCategory.UNKNOWN_PROVIDER_FAILURE, false, "UNKNOWN_PROVIDER_FAILURE");
        assertRuntime("PipelineError", ProviderFailureCategory.TEMPORARY_UNAVAILABLE, true, "PIPELINE");
        assertRuntime("TimeoutError", ProviderFailureCategory.TIMEOUT_NETWORK, true, "PROVIDER_TIMEOUT");
        assertThat(mapper.mapRuntimeError(JSON.readTree("{\"name\":\"ImageLoadError\"}")).safeCode())
            .doesNotContain("ImageLoadError");
    }

    @Test
    void thirdPartyErrorIsTransientOnlyWithEvidence() {
        assertThat(mapper.mapRuntimeError(JSON.readTree(
            "{\"name\":\"ThirdPartyError\",\"message\":\"upstream exploded\"}")))
            .satisfies(failure -> {
                assertThat(failure.category()).isEqualTo(ProviderFailureCategory.UNKNOWN_PROVIDER_FAILURE);
                assertThat(failure.retryable()).isFalse();
                assertThat(failure.safeCode()).isEqualTo("UNKNOWN_PROVIDER_FAILURE");
            });
        assertThat(mapper.mapRuntimeError(JSON.readTree(
            "{\"name\":\"ThirdPartyError\",\"message\":\"upstream\",\"cause\":{\"name\":\"TimeoutError\"}}")))
            .satisfies(failure -> {
                assertThat(failure.category()).isEqualTo(ProviderFailureCategory.TEMPORARY_UNAVAILABLE);
                assertThat(failure.retryable()).isTrue();
                assertThat(failure.safeCode()).isEqualTo("THIRD_PARTY");
            });
        assertThat(mapper.mapApiError(502, JSON.readTree("{\"error\":\"ThirdPartyError\",\"message\":\"gateway\"}")))
            .satisfies(failure -> {
                assertThat(failure.category()).isEqualTo(ProviderFailureCategory.TEMPORARY_UNAVAILABLE);
                assertThat(failure.retryable()).isTrue();
                assertThat(failure.safeCode()).isEqualTo("THIRD_PARTY");
            });
        assertThat(mapper.mapApiError(409, JSON.readTree("{\"error\":\"ThirdPartyError\",\"message\":\"gateway\"}")))
            .satisfies(failure -> {
                assertThat(failure.category()).isEqualTo(ProviderFailureCategory.UNKNOWN_PROVIDER_FAILURE);
                assertThat(failure.retryable()).isFalse();
                assertThat(failure.safeCode()).isEqualTo("UNKNOWN_PROVIDER_FAILURE");
            });
    }

    @Test
    void creditsNeverInventUsage() {
        assertThat(mapper.usage("3")).isEmpty();
        assertThat(mapper.usage(null)).isEmpty();
        assertThat(mapper.usage("not-a-number")).isEmpty();
        assertThat(mapper.mapRun(FashnHttpResponse.http(200, "3", JSON.readTree(FashnTestSupport.runSuccess()), false)))
            .isInstanceOfSatisfying(GenerationSubmission.Accepted.class, accepted -> assertThat(accepted.usage()).isEmpty());
    }

    @Test
    void storageKeysResolveToObjectBoundaries() {
        assertThat(mapper.objectRef(new StorageKey("temp/user/uploads/a")).orElseThrow().boundary()).isEqualTo(StorageBoundary.TEMP);
        assertThat(mapper.objectRef(new StorageKey("users/u/avatars/a.jpg")).orElseThrow().boundary()).isEqualTo(StorageBoundary.AVATAR);
        assertThat(mapper.objectRef(new StorageKey("users/u/vault/a.jpg")).orElseThrow().boundary()).isEqualTo(StorageBoundary.VAULT);
        assertThat(mapper.objectRef(new StorageKey("users/u/looks/a.jpg")).orElseThrow().boundary()).isEqualTo(StorageBoundary.LOOK);
        assertThat(mapper.objectRef(new StorageKey("temp/user/avatars/staged")).orElseThrow().boundary()).isEqualTo(StorageBoundary.TEMP);
        assertThat(mapper.objectRef(new StorageKey("users/u/other/a.jpg"))).isEmpty();
    }

    @Test
    void decodedOutputUsesTheApplicationImageMediaPolicy() {
        String unsupported = "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(FashnTestSupport.PNG);

        assertThatExceptionOfType(FashnProviderException.class)
            .isThrownBy(() -> mapper.decodeDataUri(unsupported))
            .satisfies(ex -> assertThat(ex.failure().safeCode()).isEqualTo("OUTPUT_NOT_IMAGE"));
    }

    @Test
    void decodedOutputRequiresAnExactBase64MetadataToken() {
        String disguised = "data:image/png;notbase64," + Base64.getEncoder().encodeToString(FashnTestSupport.PNG);

        assertThatExceptionOfType(FashnProviderException.class)
            .isThrownBy(() -> mapper.decodeDataUri(disguised))
            .satisfies(ex -> assertThat(ex.failure().safeCode()).isEqualTo("OUTPUT_MALFORMED"));
    }

    @Test
    void decodedOutputHonorsExactFourMiBBoundary() {
        byte[] exact = new byte[(int) FashnMapper.MAX_OUTPUT_BYTES];
        String exactDataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(exact);

        assertThat(mapper.decodeDataUri(exactDataUri).bytes()).hasSize((int) FashnMapper.MAX_OUTPUT_BYTES);

        byte[] oversized = new byte[(int) FashnMapper.MAX_OUTPUT_BYTES + 1];
        String oversizedDataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(oversized);
        assertThatExceptionOfType(FashnProviderException.class)
            .isThrownBy(() -> mapper.decodeDataUri(oversizedDataUri))
            .satisfies(ex -> assertThat(ex.failure().safeCode()).isEqualTo("OUTPUT_TOO_LARGE"));
    }

    @Test
    void opaqueOutputRefRoundTripsPredictionId() {
        ProviderOutputRef ref = mapper.outputRef(FashnTestSupport.PREDICTION_ID);
        assertThat(ref.value()).isEqualTo(FashnTestSupport.PREDICTION_ID + ":0");
        assertThat(mapper.predictionId(ref)).isEqualTo(FashnTestSupport.PREDICTION_ID);
        assertThatExceptionOfType(FashnProviderException.class)
            .isThrownBy(() -> mapper.predictionId(new ProviderOutputRef("job:1")));
    }

    private static void assertRejected(GenerationSubmission submission, ProviderFailureCategory category, boolean retryable) {
        assertThat(submission).isInstanceOfSatisfying(GenerationSubmission.Rejected.class, rejected -> {
            assertThat(rejected.failure().category()).isEqualTo(category);
            assertThat(rejected.failure().retryable()).isEqualTo(retryable);
        });
    }

    private static GenerationStatus observed(StatusQueryResult result) {
        assertThat(result).isInstanceOf(StatusQueryResult.Observed.class);
        return ((StatusQueryResult.Observed) result).status();
    }

    private void assertFailedCode(StatusQueryResult result, String code) {
        assertThat(observed(result)).isInstanceOfSatisfying(GenerationStatus.Failed.class, failed ->
            assertThat(failed.failure().safeCode()).isEqualTo(code));
    }

    private void assertRuntime(String name, ProviderFailureCategory category, boolean retryable, String code) {
        ProviderFailure failure = mapper.mapRuntimeError(JSON.readTree("{\"name\":\"" + name + "\",\"message\":\"https://evil.test\"}"));
        assertThat(failure.category()).isEqualTo(category);
        assertThat(failure.retryable()).isEqualTo(retryable);
        assertThat(failure.safeCode()).isEqualTo(code);
        assertThat(failure.safeCode()).doesNotContain(name, "https://", "evil");
    }

    private static tools.jackson.databind.JsonNode error(String name) {
        return JSON.readTree(FashnTestSupport.apiError(name, "https://evil.test/payload"));
    }

    private static FashnHttpResponse httpStatus(String status, String output) {
        return FashnHttpResponse.http(200, null, JSON.readTree(FashnTestSupport.status(status, output)), false);
    }
}
