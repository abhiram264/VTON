package com.aifashionvault.platform.fashn;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.URI;
import java.time.Instant;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.aifashionvault.generation.vton.AttemptRedeliveryPolicy;
import com.aifashionvault.generation.vton.GenerationStatus;
import com.aifashionvault.generation.vton.GenerationSubmission;
import com.aifashionvault.generation.vton.ItemCategory;
import com.aifashionvault.generation.vton.PrivateMediaRef;
import com.aifashionvault.generation.vton.ProviderFailure;
import com.aifashionvault.generation.vton.ProviderFailureCategory;
import com.aifashionvault.generation.vton.ProviderJobId;
import com.aifashionvault.generation.vton.ProviderKey;
import com.aifashionvault.generation.vton.ProviderMediaSource;
import com.aifashionvault.generation.vton.ProviderOutputRef;
import com.aifashionvault.generation.vton.ProviderUsage;
import com.aifashionvault.generation.vton.StatusQueryResult;
import com.aifashionvault.generation.vton.SubmissionReplaySafety;

import com.aifashionvault.generation.vton.SubmissionKey;
import com.aifashionvault.generation.vton.VtonMode;
import com.aifashionvault.generation.vton.VtonProductAsset;
import com.aifashionvault.generation.vton.VtonRequest;
import com.aifashionvault.platform.objectstore.ObjectStoreExceptions;
import com.aifashionvault.shared.StorageKey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FashnVtonProviderTest {
    @Test
    void providerIdentityAndCapabilitiesStayOffline() {
        FashnTestSupport.Harness harness = FashnTestSupport.harness();

        assertThat(harness.provider().providerKey()).isEqualTo(new ProviderKey("fashn"));
        assertThat(harness.provider().capabilities().submissionReplaySafety().name()).isEqualTo("UNSAFE");
        assertThat(harness.provider().capabilities().submissionReplaySafety())
            .isEqualTo(SubmissionReplaySafety.UNSAFE);
        assertThat(harness.provider().capabilities().forMode(FashnTestSupport.STANDARD).orElseThrow().maxProductAssets())
            .isEqualTo(1);
        assertThat(AttemptRedeliveryPolicy.decide(
            false, Optional.empty(), true, harness.provider().capabilities().submissionReplaySafety()))
            .isEqualTo(AttemptRedeliveryPolicy.Decision.BLOCK_UNSAFE_REPLAY);


        assertThat(java.util.Arrays.stream(harness.provider().getClass().getDeclaredMethods()).map(java.lang.reflect.Method::getName))
            .doesNotContain("generate");
        harness.server().verify();
    }

    @Test
    void submitAcceptsUsableRunId() {
        FashnTestSupport.Harness harness = FashnTestSupport.harness();
        expectRun(harness).andRespond(withSuccess(FashnTestSupport.runSuccess(), MediaType.APPLICATION_JSON));

        GenerationSubmission submission = harness.provider().submit(FashnTestSupport.request(FashnTestSupport.STANDARD), FashnTestSupport.key());

        assertThat(submission).isEqualTo(new GenerationSubmission.Accepted(new ProviderJobId(FashnTestSupport.PREDICTION_ID), Optional.empty()));
        harness.server().verify();
    }

    @Test
    void submitWithoutJobIdIsIndeterminate() {
        FashnTestSupport.Harness harness = FashnTestSupport.harness();
        expectRun(harness).andRespond(withSuccess("{\"id\":\"\",\"error\":null}", MediaType.APPLICATION_JSON));

        assertThat(harness.provider().submit(FashnTestSupport.request(FashnTestSupport.STANDARD), FashnTestSupport.key()))
            .isInstanceOf(GenerationSubmission.Indeterminate.class);
        harness.server().verify();
    }

    @Test
    void oversizedErrorBodyKeepsKnownHttpRejectionClassification() {
        FashnTestSupport.Harness harness = FashnTestSupport.harness();
        expectRun(harness).andRespond(withStatus(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.APPLICATION_JSON)
            .body(paddedBody(FashnTestSupport.apiError("BadRequest", "rejected"), FashnClient.MAX_BODY_BYTES + 1)));

        GenerationSubmission submission = harness.provider().submit(
            FashnTestSupport.request(FashnTestSupport.STANDARD),
            FashnTestSupport.key());

        assertThat(submission).isInstanceOfSatisfying(GenerationSubmission.Rejected.class, rejected -> {
            assertThat(rejected.failure().category()).isEqualTo(ProviderFailureCategory.INVALID_REQUEST);
            assertThat(rejected.failure().retryable()).isFalse();
        });
        harness.server().verify();
    }

    @Test
    void knownInputRejectionIsNonRetryableAndSafe() {
        assertApiRejection(400, "BadRequest", "https://evil.test/data:image/png;base64,abc", ProviderFailureCategory.INVALID_REQUEST, false);
    }

    @Test
    void authenticationFailureIsNonRetryable() {
        assertApiRejection(401, "UnauthorizedAccess", "bad key https://evil.test", ProviderFailureCategory.AUTH_CONFIGURATION, false);
    }

    @Test
    void rateLimitIsRetryable() {
        assertApiRejection(429, "RateLimitExceeded", "slow down", ProviderFailureCategory.RATE_CAPACITY, true);
    }

    @Test
    void validRetryAfterIsNormalizedWithoutClientRetry() {
        FashnTestSupport.Harness harness = FashnTestSupport.harness();
        expectRun(harness).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
            .header("Retry-After", "120")
            .contentType(MediaType.APPLICATION_JSON)
            .body(FashnTestSupport.apiError("RateLimitExceeded", "slow down")));

        Instant lowerBound = Instant.now().plusSeconds(119);
        GenerationSubmission submission = harness.provider().submit(
            FashnTestSupport.request(FashnTestSupport.STANDARD), FashnTestSupport.key());

        assertThat(submission).isInstanceOfSatisfying(GenerationSubmission.Rejected.class, rejected -> {
            assertThat(rejected.failure().retryable()).isTrue();
            assertThat(rejected.failure().category()).isEqualTo(ProviderFailureCategory.RATE_CAPACITY);
            assertThat(rejected.failure().retryAfterAt()).isNotNull();
            assertThat(rejected.failure().retryAfterAt()).isAfterOrEqualTo(lowerBound);
            assertThat(rejected.failure().retryAfterAt()).isBeforeOrEqualTo(Instant.now().plusSeconds(121));
        });
        harness.server().verify();
    }

    @Test
    void malformedRetryAfterIsDroppedWithoutClientRetry() {
        FashnTestSupport.Harness harness = FashnTestSupport.harness();
        expectRun(harness).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
            .header("Retry-After", "-1")
            .contentType(MediaType.APPLICATION_JSON)
            .body(FashnTestSupport.apiError("RateLimitExceeded", "slow down")));

        GenerationSubmission submission = harness.provider().submit(
            FashnTestSupport.request(FashnTestSupport.STANDARD), FashnTestSupport.key());

        assertThat(submission).isInstanceOfSatisfying(GenerationSubmission.Rejected.class, rejected -> {
            assertThat(rejected.failure().retryable()).isTrue();
            assertThat(rejected.failure().category()).isEqualTo(ProviderFailureCategory.RATE_CAPACITY);
            assertThat(rejected.failure().retryAfterAt()).isNull();
        });
        harness.server().verify();
    }

    @Test
    void httpDateRetryAfterResolvesAgainstResponseDateHeader() {
        FashnTestSupport.Harness harness = FashnTestSupport.harness();
        expectRun(harness).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
            .header("Date", "Wed, 21 Oct 2015 07:25:00 GMT")
            .header("Retry-After", "Wed, 21 Oct 2015 07:26:00 GMT")
            .contentType(MediaType.APPLICATION_JSON)
            .body(FashnTestSupport.apiError("RateLimitExceeded", "slow down")));

        GenerationSubmission submission = harness.provider().submit(
            FashnTestSupport.request(FashnTestSupport.STANDARD), FashnTestSupport.key());

        // 07:26 is past relative to the backend receipt clock but future relative
        // to the response Date header, so the Date header anchors the comparison.
        assertThat(submission).isInstanceOfSatisfying(GenerationSubmission.Rejected.class, rejected -> {
            assertThat(rejected.failure().retryAfterAt()).isEqualTo(Instant.parse("2015-10-21T07:26:00Z"));
            assertThat(rejected.failure().mappingContractVersion())
                .isEqualTo(FashnMapper.ERROR_MAPPING_CONTRACT_VERSION);
        });
        harness.server().verify();
    }

    @Test
    void httpDateRetryAfterInTheDateHeadersPastIsDropped() {
        FashnTestSupport.Harness harness = FashnTestSupport.harness();
        expectRun(harness).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
            .header("Date", "Wed, 21 Oct 2015 07:30:00 GMT")
            .header("Retry-After", "Wed, 21 Oct 2015 07:26:00 GMT")
            .contentType(MediaType.APPLICATION_JSON)
            .body(FashnTestSupport.apiError("RateLimitExceeded", "slow down")));

        GenerationSubmission submission = harness.provider().submit(
            FashnTestSupport.request(FashnTestSupport.STANDARD), FashnTestSupport.key());

        assertThat(submission).isInstanceOfSatisfying(GenerationSubmission.Rejected.class, rejected -> {
            assertThat(rejected.failure().retryable()).isTrue();
            assertThat(rejected.failure().retryAfterAt()).isNull();
            assertThat(rejected.failure().mappingContractVersion())
                .isEqualTo(FashnMapper.ERROR_MAPPING_CONTRACT_VERSION);
        });
        harness.server().verify();
    }

    @Test
    void concurrencyLimitIsRetryable() {
        assertApiRejection(429, "ConcurrencyLimitExceeded", "busy", ProviderFailureCategory.RATE_CAPACITY, true);
    }

    @Test
    void outOfCreditsIsNonRetryableQuota() {
        assertApiRejection(429, "OutOfCredits", "pay us https://billing.test", ProviderFailureCategory.QUOTA_BILLING, false);
    }

    @Test
    void providerInternalErrorIsRetryable() {
        assertApiRejection(500, "InternalServerError", "boom", ProviderFailureCategory.TEMPORARY_UNAVAILABLE, true);
    }

    @Test
    void definitePreSendFailureIsRetryableRejection() {
        AtomicInteger attempts = new AtomicInteger();
        RestClient restClient = RestClient.builder()
            .baseUrl(FashnConfig.BASE_URL)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + FashnTestSupport.API_KEY)
            .requestFactory(connectFailureFactory(attempts))
            .build();
        FashnTestSupport.FakeObjectStore store = new FashnTestSupport.FakeObjectStore();
        store.put(FashnTestSupport.AVATAR_KEY, FashnTestSupport.PNG, "image/png");
        store.put(FashnTestSupport.PRODUCT_KEY, FashnTestSupport.PNG, "image/png");
        FashnVtonProvider provider = new FashnVtonProvider(new FashnMapper(), new FashnClient(restClient), store);

        GenerationSubmission submission = provider.submit(FashnTestSupport.request(FashnTestSupport.STANDARD), FashnTestSupport.key());

        assertThat(submission).isInstanceOfSatisfying(GenerationSubmission.Rejected.class, rejected -> {
            assertThat(rejected.failure().retryable()).isTrue();
            assertThat(rejected.failure().category()).isEqualTo(ProviderFailureCategory.TIMEOUT_NETWORK);
        });
        assertThat(attempts).hasValue(1);
    }

    @Test
    void requestTimeoutIsIndeterminateAndNotRetried() {
        AtomicInteger attempts = new AtomicInteger();
        RestClient restClient = RestClient.builder()
            .baseUrl(FashnConfig.BASE_URL)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + FashnTestSupport.API_KEY)
            .requestFactory(countingTimeoutFactory(attempts))
            .build();
        FashnTestSupport.FakeObjectStore store = new FashnTestSupport.FakeObjectStore();
        store.put(FashnTestSupport.AVATAR_KEY, FashnTestSupport.PNG, "image/png");
        store.put(FashnTestSupport.PRODUCT_KEY, FashnTestSupport.PNG, "image/png");
        FashnVtonProvider provider = new FashnVtonProvider(new FashnMapper(), new FashnClient(restClient), store);

        assertThat(provider.submit(FashnTestSupport.request(FashnTestSupport.STANDARD), FashnTestSupport.key()))
            .isInstanceOf(GenerationSubmission.Indeterminate.class);
        assertThat(attempts).hasValue(1);
    }

    @Test
    void queuedAndRunningStatusesNormalizeWithoutPolling() {
        FashnTestSupport.Harness starting = FashnTestSupport.harness();
        expectStatus(starting, "starting", "null");
        assertThat(observed(starting.provider().getStatus(job()))).isInstanceOf(GenerationStatus.Queued.class);
        starting.server().verify();

        FashnTestSupport.Harness queued = FashnTestSupport.harness();
        expectStatus(queued, "in_queue", "null");
        assertThat(observed(queued.provider().getStatus(job()))).isInstanceOf(GenerationStatus.Queued.class);
        queued.server().verify();

        FashnTestSupport.Harness running = FashnTestSupport.harness();
        expectStatus(running, "processing", "null");
        assertThat(observed(running.provider().getStatus(job()))).isInstanceOf(GenerationStatus.Running.class);
        running.server().verify();
    }

    @Test
    void completedStatusExposesOpaqueOutputOnly() {
        FashnTestSupport.Harness harness = FashnTestSupport.harness();
        expectStatusBody(harness, FashnTestSupport.completedImage());

        GenerationStatus status = observed(harness.provider().getStatus(job()));

        assertThat(status).isInstanceOfSatisfying(GenerationStatus.Succeeded.class, succeeded -> {
            assertThat(succeeded.outputs()).containsExactly(new ProviderOutputRef(FashnTestSupport.PREDICTION_ID + ":0"));
            assertThat(succeeded.outputs().getFirst().value()).doesNotContain("data:", "https:", "base64");
        });
        harness.server().verify();
    }

    @Test
    void failedRuntimeStatusMapsWithoutLeakingProviderText() {
        FashnTestSupport.Harness harness = FashnTestSupport.harness();
        expectStatusBody(harness, FashnTestSupport.runtimeFailed("PoseError", "https://signed.test/data:image/png;base64,abc"));

        GenerationStatus status = observed(harness.provider().getStatus(job()));

        assertThat(status).isInstanceOfSatisfying(GenerationStatus.Failed.class, failed -> {
            assertThat(failed.failure().category()).isEqualTo(ProviderFailureCategory.INVALID_REQUEST);
            assertThat(failed.failure().retryable()).isFalse();
            assertThat(failed.failure().safeCode()).isEqualTo("POSE");
            assertThat(failed.failure().safeCode() + failed).doesNotContain("PoseError", "https://", "data:", "base64");
        });
        harness.server().verify();
    }

    @Test
    void providerTimeoutStatusIsRetryable() {
        FashnTestSupport.Harness harness = FashnTestSupport.harness();
        expectStatusBody(harness, FashnTestSupport.runtimeFailed("TimeoutError", "took too long"));

        assertThat(observed(harness.provider().getStatus(job()))).isInstanceOfSatisfying(GenerationStatus.Failed.class, failed -> {
            assertThat(failed.failure().retryable()).isTrue();
            assertThat(failed.failure().category()).isEqualTo(ProviderFailureCategory.TIMEOUT_NETWORK);
        });
        harness.server().verify();
    }

    @Test
    void thirdPartyErrorWithoutTransientEvidenceFailsClosedOverTheWire() {
        FashnTestSupport.Harness harness = FashnTestSupport.harness();
        expectStatusBody(harness, FashnTestSupport.runtimeFailed("ThirdPartyError", "upstream exploded https://vendor.test"));

        assertThat(observed(harness.provider().getStatus(job()))).isInstanceOfSatisfying(GenerationStatus.Failed.class, failed -> {
            assertThat(failed.failure().category()).isEqualTo(ProviderFailureCategory.UNKNOWN_PROVIDER_FAILURE);
            assertThat(failed.failure().retryable()).isFalse();
            assertThat(failed.failure().safeCode()).isEqualTo("UNKNOWN_PROVIDER_FAILURE");
            assertThat(failed.failure().safeCode()).doesNotContain("ThirdPartyError", "vendor.test");
        });
        harness.server().verify();
    }

    @Test
    void thirdPartyErrorWithNestedTimeoutCauseIsTransient() {
        FashnTestSupport.Harness harness = FashnTestSupport.harness();
        expectStatusBody(harness, "{\"id\":\"" + FashnTestSupport.PREDICTION_ID + "\",\"status\":\"failed\","
            + "\"error\":{\"name\":\"ThirdPartyError\",\"message\":\"upstream\",\"cause\":{\"name\":\"TimeoutError\"}},\"output\":null}");

        assertThat(observed(harness.provider().getStatus(job()))).isInstanceOfSatisfying(GenerationStatus.Failed.class, failed -> {
            assertThat(failed.failure().category()).isEqualTo(ProviderFailureCategory.TEMPORARY_UNAVAILABLE);
            assertThat(failed.failure().retryable()).isTrue();
            assertThat(failed.failure().safeCode()).isEqualTo("THIRD_PARTY");
        });
        harness.server().verify();
    }

    @Test
    void unknownOrMissingStatusIsUnavailable() {
        FashnTestSupport.Harness unknown = FashnTestSupport.harness();
        expectStatus(unknown, "warming_up", "null");
        assertThat(unknown.provider().getStatus(job())).isInstanceOf(StatusQueryResult.Unavailable.class);
        unknown.server().verify();

        FashnTestSupport.Harness missing = FashnTestSupport.harness();
        expectStatusBody(missing, "{\"id\":\"" + FashnTestSupport.PREDICTION_ID + "\",\"error\":null}");
        assertThat(missing.provider().getStatus(job())).isInstanceOf(StatusQueryResult.Unavailable.class);
        missing.server().verify();
    }

    @Test
    void completedWithoutUsableOutputIsTechnicalFailure() {
        FashnTestSupport.Harness missing = FashnTestSupport.harness();
        expectStatus(missing, "completed", "[]");
        assertThat(observed(missing.provider().getStatus(job()))).isInstanceOf(GenerationStatus.Failed.class);
        missing.server().verify();

        FashnTestSupport.Harness count = FashnTestSupport.harness();
        expectStatus(count, "completed", "[\"" + FashnTestSupport.PNG_DATA_URI + "\",\"" + FashnTestSupport.PNG_DATA_URI + "\"]");
        assertThat(observed(count.provider().getStatus(job()))).isInstanceOfSatisfying(GenerationStatus.Failed.class, failed ->
            assertThat(failed.failure().safeCode()).isEqualTo("OUTPUT_COUNT"));
        count.server().verify();
    }

    @Test
    void unexpectedUrlOutputIsRejectedWithoutFetching() {
        FashnTestSupport.Harness harness = FashnTestSupport.harness();
        expectStatus(harness, "completed", "[\"https://cdn.fashn.ai/out.png\"]");

        assertThat(observed(harness.provider().getStatus(job()))).isInstanceOfSatisfying(GenerationStatus.Failed.class, failed ->
            assertThat(failed.failure().safeCode()).isEqualTo("OUTPUT_URL"));
        harness.server().verify();
    }

    @Test
    void openOutputRequeriesAndReturnsBoundedImage() throws Exception {
        FashnTestSupport.Harness harness = FashnTestSupport.harness();
        expectStatusBody(harness, FashnTestSupport.completedImage());

        try (ProviderMediaSource source = harness.provider().openOutput(outputRef())) {
            assertThat(source.mediaType()).isEqualTo("image/png");
            assertThat(source.contentLength()).hasValue(FashnTestSupport.PNG.length);
            assertThat(source.maxBytes()).isEqualTo(FashnMapper.MAX_OUTPUT_BYTES);
            assertThat(source.stream().readAllBytes()).isEqualTo(FashnTestSupport.PNG);
        }
        harness.server().verify();
    }

    @Test
    void malformedNonImageAndOversizedOutputsFailWithoutResubmit() {
        assertOpenOutputFailure("{\"id\":\"" + FashnTestSupport.PREDICTION_ID + "\",\"status\":\"completed\",\"output\":[\"data:image/png;base64,%%%\"],\"error\":null}", "OUTPUT_MALFORMED");
        assertOpenOutputFailure("{\"id\":\"" + FashnTestSupport.PREDICTION_ID + "\",\"status\":\"completed\",\"output\":[\"data:application/json;base64,e30=\"],\"error\":null}", "OUTPUT_NOT_IMAGE");

        byte[] oversized = new byte[(int) FashnMapper.MAX_OUTPUT_BYTES + 1];
        String huge = "data:image/png;base64," + Base64.getEncoder().encodeToString(oversized);
        assertOpenOutputFailure("{\"id\":\"" + FashnTestSupport.PREDICTION_ID + "\",\"status\":\"completed\",\"output\":[\"" + huge + "\"],\"error\":null}", "OUTPUT_TOO_LARGE");
    }

    @Test
    void httpBodyLimitAcceptsExactEightMiBAndRejectsOneByteOver() {
        FashnTestSupport.Harness exact = FashnTestSupport.harness();
        expectStatusBody(exact, paddedCompletedBody(FashnClient.MAX_BODY_BYTES));
        assertThat(observed(exact.provider().getStatus(job()))).isInstanceOf(GenerationStatus.Succeeded.class);
        exact.server().verify();

        FashnTestSupport.Harness oversized = FashnTestSupport.harness();
        expectStatusBody(oversized, paddedCompletedBody(FashnClient.MAX_BODY_BYTES + 1));
        assertThat(observed(oversized.provider().getStatus(job())))
            .isInstanceOfSatisfying(GenerationStatus.Failed.class, failed ->
                assertThat(failed.failure().safeCode()).isEqualTo("OUTPUT_TOO_LARGE"));
        oversized.server().verify();
    }

    @Test
    void expiredOutputFailsRetrievalWithoutResubmission() {
        FashnTestSupport.Harness status = FashnTestSupport.harness();
        expectStatus(status, "completed", "[\"_expired\"]");
        assertThat(observed(status.provider().getStatus(job()))).isInstanceOfSatisfying(GenerationStatus.Failed.class, failed ->
            assertThat(failed.failure().safeCode()).isEqualTo("OUTPUT_EXPIRED"));
        status.server().verify();

        assertOpenOutputFailure(FashnTestSupport.status("completed", "[\"_expired\"]"), "OUTPUT_EXPIRED");
    }

    @Test
    void consumedCreditsFlowIntoUsageAndInvalidHeadersStayAbsent() {
        FashnTestSupport.Harness harness = FashnTestSupport.harness();
        expectRun(harness).andRespond(withSuccess(FashnTestSupport.runSuccess(), MediaType.APPLICATION_JSON)
            .header(FashnMapper.CREDITS_HEADER, "1.5"));

        assertThat(harness.provider().submit(FashnTestSupport.request(FashnTestSupport.STANDARD), FashnTestSupport.key()))
            .isInstanceOfSatisfying(GenerationSubmission.Accepted.class, accepted ->
                assertThat(accepted.usage()).contains(new ProviderUsage(new BigDecimal("1.5"), new BigDecimal("1.5"), "CRD")));
        harness.server().verify();

        FashnTestSupport.Harness status = FashnTestSupport.harness();
        status.server().expect(once(), requestTo(FashnTestSupport.STATUS_URL))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(FashnTestSupport.completedImage(), MediaType.APPLICATION_JSON).header(FashnMapper.CREDITS_HEADER, "not-a-number"));
        assertThat(observed(status.provider().getStatus(job()))).isInstanceOfSatisfying(GenerationStatus.Succeeded.class, succeeded ->
            assertThat(succeeded.usage()).isEmpty());
        status.server().verify();
    }

    @Test
    void fastPreviewEmitsV16BodyAndNeverMaxFields() {
        FashnTestSupport.Harness harness = FashnTestSupport.harness();
        harness.server().expect(once(), requestTo(FashnTestSupport.RUN_URL))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + FashnTestSupport.API_KEY))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.model_name").value("tryon-v1.6"))
            .andExpect(jsonPath("$.inputs.model_image").exists())
            .andExpect(jsonPath("$.inputs.garment_image").exists())
            .andExpect(jsonPath("$.inputs.mode").value("performance"))
            .andExpect(jsonPath("$.inputs.num_samples").value(1))
            .andExpect(jsonPath("$.inputs.return_base64").value(true))
            .andExpect(jsonPath("$.inputs.product_image").doesNotExist())
            .andExpect(jsonPath("$.inputs.prompt").doesNotExist())
            .andExpect(jsonPath("$.inputs.resolution").doesNotExist())
            .andExpect(jsonPath("$.inputs.generation_mode").doesNotExist())
            .andExpect(jsonPath("$.webhook_url").doesNotExist())
            .andExpect(request -> {
                assertThat(request.getHeaders().get("Idempotency-Key")).isNull();
                assertThat(new String(request.getBody().toString().getBytes())).doesNotContain(FashnTestSupport.key().value().toString());
            })
            .andRespond(withSuccess(FashnTestSupport.runSuccess(), MediaType.APPLICATION_JSON));

        harness.provider().submit(FashnTestSupport.request(FashnTestSupport.FAST), FashnTestSupport.key());
        harness.server().verify();
    }

    @Test
    void standardAndHdEmitTryOnMaxRoutes() {
        FashnTestSupport.Harness standard = FashnTestSupport.harness();
        standard.server().expect(once(), requestTo(FashnTestSupport.RUN_URL))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.model_name").value("tryon-max"))
            .andExpect(jsonPath("$.inputs.product_image").exists())
            .andExpect(jsonPath("$.inputs.garment_image").doesNotExist())
            .andExpect(jsonPath("$.inputs.generation_mode").value("fast"))
            .andExpect(jsonPath("$.inputs.resolution").value("1k"))
            .andExpect(jsonPath("$.inputs.num_images").value(1))
            .andExpect(jsonPath("$.inputs.return_base64").value(true))
            .andExpect(jsonPath("$.inputs.prompt").value("keep logo"))
            .andRespond(withSuccess(FashnTestSupport.runSuccess(), MediaType.APPLICATION_JSON));
        standard.provider().submit(FashnTestSupport.request(FashnTestSupport.STANDARD, FashnTestSupport.CLOTHING, Optional.of("keep logo")), FashnTestSupport.key());
        standard.server().verify();

        FashnTestSupport.Harness hd = FashnTestSupport.harness();
        hd.server().expect(once(), requestTo(FashnTestSupport.RUN_URL))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.model_name").value("tryon-max"))
            .andExpect(jsonPath("$.inputs.generation_mode").value("balanced"))
            .andExpect(jsonPath("$.inputs.resolution").value("2k"))
            .andExpect(jsonPath("$.inputs.resolution").value(org.hamcrest.Matchers.not("4k")))
            .andRespond(withSuccess(FashnTestSupport.runSuccess(), MediaType.APPLICATION_JSON));
        hd.provider().submit(FashnTestSupport.request(FashnTestSupport.HD), FashnTestSupport.key());
        hd.server().verify();
    }

    @Test
    void instructionsAndCategoriesAndCountsFailLocally() {
        FashnTestSupport.Harness harness = FashnTestSupport.harness();

        assertThat(harness.provider().submit(
            FashnTestSupport.request(FashnTestSupport.FAST, FashnTestSupport.CLOTHING, Optional.of("keep logo")),
            FashnTestSupport.key())).isInstanceOfSatisfying(GenerationSubmission.Rejected.class, rejected ->
            assertThat(rejected.failure().safeCode()).isEqualTo("INSTRUCTIONS_UNSUPPORTED"));
        assertThat(harness.provider().submit(
            FashnTestSupport.request(FashnTestSupport.FAST, new ItemCategory("footwear"), Optional.empty()),
            FashnTestSupport.key())).isInstanceOfSatisfying(GenerationSubmission.Rejected.class, rejected ->
            assertThat(rejected.failure().safeCode()).isEqualTo("CATEGORY_UNSUPPORTED"));
        assertThatIllegalArgumentException().isThrownBy(() -> new VtonRequest(
            new PrivateMediaRef(new StorageKey(FashnTestSupport.AVATAR_KEY), OptionalLong.of(8)),
            List.of(),
            Optional.empty(),
            FashnTestSupport.STANDARD));
        VtonRequest multi = new VtonRequest(
            new PrivateMediaRef(new StorageKey(FashnTestSupport.AVATAR_KEY), OptionalLong.of(8)),
            List.of(
                new VtonProductAsset(new PrivateMediaRef(new StorageKey(FashnTestSupport.PRODUCT_KEY), OptionalLong.of(8)), FashnTestSupport.CLOTHING),
                new VtonProductAsset(new PrivateMediaRef(new StorageKey("temp/user/uploads/second"), OptionalLong.of(8)), FashnTestSupport.CLOTHING)),
            Optional.empty(),
            FashnTestSupport.STANDARD);
        assertThat(harness.provider().submit(multi, FashnTestSupport.key()))
            .isInstanceOfSatisfying(GenerationSubmission.Rejected.class, rejected ->
                assertThat(rejected.failure().safeCode()).isEqualTo("PRODUCT_COUNT_UNSUPPORTED"));
        harness.server().verify();
    }

    @Test
    void generationOwnedProductUsesPrivateTransferBoundary() {
        FashnTestSupport.Harness harness = FashnTestSupport.harness();
        String productKey = "users/11111111-1111-1111-1111-111111111111/generation-inputs/"
            + "22222222-2222-2222-2222-222222222222/product";
        harness.store().put(productKey, FashnTestSupport.PNG, "image/png");
        expectRun(harness).andRespond(withSuccess(FashnTestSupport.runSuccess(), MediaType.APPLICATION_JSON));

        GenerationSubmission submission = harness.provider().submit(
            FashnTestSupport.request(
                FashnTestSupport.STANDARD,
                FashnTestSupport.CLOTHING,
                Optional.empty(),
                OptionalLong.of(FashnTestSupport.PNG.length),
                FashnTestSupport.AVATAR_KEY,
                productKey),
            FashnTestSupport.key());

        assertThat(submission).isInstanceOf(GenerationSubmission.Accepted.class);
        harness.server().verify();
    }

    @Test
    void unsupportedImageMediaTypeFailsBeforeProviderIo() {
        FashnTestSupport.Harness harness = FashnTestSupport.harness();
        harness.store().put(FashnTestSupport.AVATAR_KEY, FashnTestSupport.PNG, "image/svg+xml");

        GenerationSubmission submission = harness.provider().submit(
            FashnTestSupport.request(FashnTestSupport.STANDARD),
            FashnTestSupport.key());

        assertThat(submission).isInstanceOfSatisfying(GenerationSubmission.Rejected.class, rejected ->
            assertThat(rejected.failure().safeCode()).isEqualTo("MEDIA_TYPE"));
        assertThat(harness.store().readCount).isZero();
        assertThat(harness.store().presignCount).isZero();
        harness.server().verify();
    }

    @Test
    void inlineTransferUsesTransientDataUriOnlyOnTheWire() {
        FashnTestSupport.Harness harness = FashnTestSupport.harness();
        harness.server().expect(once(), requestTo(FashnTestSupport.RUN_URL))
            .andExpect(jsonPath("$.inputs.model_image").value(FashnTestSupport.PNG_DATA_URI))
            .andExpect(jsonPath("$.inputs.product_image").value(FashnTestSupport.PNG_DATA_URI))
            .andExpect(jsonPath("$.userId").doesNotExist())
            .andExpect(jsonPath("$.orgId").doesNotExist())
            .andRespond(withSuccess(FashnTestSupport.runSuccess(), MediaType.APPLICATION_JSON));

        GenerationSubmission submission = harness.provider().submit(FashnTestSupport.request(FashnTestSupport.STANDARD), FashnTestSupport.key());

        assertThat(submission.toString()).doesNotContain("data:", "base64", FashnTestSupport.PNG_DATA_URI);
        assertThat(harness.store().readCount).isEqualTo(2);
        assertThat(harness.store().presignCount).isZero();
        harness.server().verify();
    }

    @Test
    void signedReadUsesEphemeralStoreUriNotCallerUrl() {
        FashnTestSupport.Harness harness = FashnTestSupport.harness();
        long signedSize = FashnMapper.MAX_INLINE_BYTES + 1024;
        harness.store().putSized(FashnTestSupport.AVATAR_KEY, FashnTestSupport.PNG, "image/png", signedSize);
        harness.store().putSized(FashnTestSupport.PRODUCT_KEY, FashnTestSupport.PNG, "image/png", signedSize);
        harness.server().expect(once(), requestTo(FashnTestSupport.RUN_URL))
            .andExpect(jsonPath("$.inputs.model_image").value(FashnTestSupport.SIGNED_URI))
            .andExpect(jsonPath("$.inputs.product_image").value(FashnTestSupport.SIGNED_URI))
            .andRespond(withSuccess(FashnTestSupport.runSuccess(), MediaType.APPLICATION_JSON));

        VtonRequest request = FashnTestSupport.request(
            FashnTestSupport.STANDARD,
            FashnTestSupport.CLOTHING,
            Optional.empty(),
            OptionalLong.of(signedSize),
            FashnTestSupport.AVATAR_KEY,
            FashnTestSupport.PRODUCT_KEY);
        GenerationSubmission submission = harness.provider().submit(request, FashnTestSupport.key());

        assertThat(submission.toString()).doesNotContain("https://objects.test", "X-Amz-Signature", FashnTestSupport.SIGNED_URI);
        assertThat(harness.store().presignCount).isEqualTo(2);
        assertThat(harness.store().lastTtl).isEqualTo(Duration.ofMinutes(5));
        assertThat(harness.store().lastTtl.compareTo(Duration.ofHours(1)) < 0).isTrue();
        assertThat(harness.store().readCount).isZero();
        harness.server().verify();
    }

    @Test
    void exactInputSizeBoundariesChooseInlineThenSignedTransfer() {
        FashnTestSupport.Harness inline = FashnTestSupport.harness();
        inline.store().putSized(FashnTestSupport.AVATAR_KEY, FashnTestSupport.PNG, "image/png", FashnMapper.MAX_INLINE_BYTES);
        inline.store().putSized(FashnTestSupport.PRODUCT_KEY, FashnTestSupport.PNG, "image/png", FashnMapper.MAX_INLINE_BYTES);
        expectRun(inline).andRespond(withSuccess(FashnTestSupport.runSuccess(), MediaType.APPLICATION_JSON));

        GenerationSubmission inlineSubmission = inline.provider().submit(
            FashnTestSupport.request(
                FashnTestSupport.STANDARD,
                FashnTestSupport.CLOTHING,
                Optional.empty(),
                OptionalLong.of(FashnMapper.MAX_INLINE_BYTES),
                FashnTestSupport.AVATAR_KEY,
                FashnTestSupport.PRODUCT_KEY),
            FashnTestSupport.key());

        assertThat(inlineSubmission).isInstanceOf(GenerationSubmission.Accepted.class);
        assertThat(inline.store().readCount).isEqualTo(2);
        assertThat(inline.store().presignCount).isZero();
        inline.server().verify();

        FashnTestSupport.Harness signed = FashnTestSupport.harness();
        signed.store().putSized(FashnTestSupport.AVATAR_KEY, FashnTestSupport.PNG, "image/png", FashnMapper.MAX_ASSET_BYTES);
        signed.store().putSized(FashnTestSupport.PRODUCT_KEY, FashnTestSupport.PNG, "image/png", FashnMapper.MAX_ASSET_BYTES);
        signed.server().expect(once(), requestTo(FashnTestSupport.RUN_URL))
            .andExpect(jsonPath("$.inputs.model_image").value(FashnTestSupport.SIGNED_URI))
            .andExpect(jsonPath("$.inputs.product_image").value(FashnTestSupport.SIGNED_URI))
            .andRespond(withSuccess(FashnTestSupport.runSuccess(), MediaType.APPLICATION_JSON));

        GenerationSubmission signedSubmission = signed.provider().submit(
            FashnTestSupport.request(
                FashnTestSupport.STANDARD,
                FashnTestSupport.CLOTHING,
                Optional.empty(),
                OptionalLong.of(FashnMapper.MAX_ASSET_BYTES),
                FashnTestSupport.AVATAR_KEY,
                FashnTestSupport.PRODUCT_KEY),
            FashnTestSupport.key());

        assertThat(signedSubmission).isInstanceOf(GenerationSubmission.Accepted.class);
        assertThat(signed.store().readCount).isZero();
        assertThat(signed.store().presignCount).isEqualTo(2);
        assertThat(signed.store().lastTtl).isEqualTo(Duration.ofMinutes(5));
        signed.server().verify();
    }

    @Test
    void metadataAboveMaximumRejectsEvenWhenRequestUnderreportsSize() {
        FashnTestSupport.Harness harness = FashnTestSupport.harness();
        long oversized = FashnMapper.MAX_ASSET_BYTES + 1;
        harness.store().putSized(FashnTestSupport.AVATAR_KEY, FashnTestSupport.PNG, "image/png", oversized);

        GenerationSubmission submission = harness.provider().submit(
            FashnTestSupport.request(FashnTestSupport.STANDARD),
            FashnTestSupport.key());

        assertThat(submission).isInstanceOfSatisfying(GenerationSubmission.Rejected.class, rejected ->
            assertThat(rejected.failure().safeCode()).isEqualTo("INPUT_SIZE_EXCEEDED"));
        assertThat(harness.store().readCount).isZero();
        assertThat(harness.store().presignCount).isZero();
        harness.server().verify();
    }

    @Test
    void objectStoreMetadataControlsTransferWhenRequestSizeIsStale() {
        FashnTestSupport.Harness harness = FashnTestSupport.harness();
        long storedSize = FashnMapper.MAX_INLINE_BYTES + 1;
        harness.store().putSized(FashnTestSupport.AVATAR_KEY, FashnTestSupport.PNG, "image/png", storedSize);
        harness.store().putSized(FashnTestSupport.PRODUCT_KEY, FashnTestSupport.PNG, "image/png", storedSize);
        harness.server().expect(once(), requestTo(FashnTestSupport.RUN_URL))
            .andExpect(jsonPath("$.inputs.model_image").value(FashnTestSupport.SIGNED_URI))
            .andExpect(jsonPath("$.inputs.product_image").value(FashnTestSupport.SIGNED_URI))
            .andRespond(withSuccess(FashnTestSupport.runSuccess(), MediaType.APPLICATION_JSON));

        GenerationSubmission submission = harness.provider().submit(
            FashnTestSupport.request(FashnTestSupport.STANDARD),
            FashnTestSupport.key());

        assertThat(submission).isInstanceOf(GenerationSubmission.Accepted.class);
        assertThat(harness.store().readCount).isZero();
        assertThat(harness.store().presignCount).isEqualTo(2);
        assertThat(harness.store().lastTtl).isEqualTo(Duration.ofMinutes(5));
        harness.server().verify();
    }

    @Test
    void unresolvedOrDeletedMediaFailsBeforeHttp() {
        FashnTestSupport.Harness missing = FashnTestSupport.harness();
        missing.store().headFailure = new ObjectStoreExceptions.StagedObjectMissingException("gone");
        assertThat(missing.provider().submit(FashnTestSupport.request(FashnTestSupport.STANDARD), FashnTestSupport.key()))
            .isInstanceOfSatisfying(GenerationSubmission.Rejected.class, rejected ->
                assertThat(rejected.failure().safeCode()).isEqualTo("MEDIA_MISSING"));
        missing.server().verify();

        FashnTestSupport.Harness unresolved = FashnTestSupport.harness();
        VtonRequest bad = FashnTestSupport.request(
            FashnTestSupport.STANDARD,
            FashnTestSupport.CLOTHING,
            Optional.empty(),
            OptionalLong.of(8),
            "users/u/other/avatar.png",
            FashnTestSupport.PRODUCT_KEY);
        assertThat(unresolved.provider().submit(bad, FashnTestSupport.key()))
            .isInstanceOfSatisfying(GenerationSubmission.Rejected.class, rejected ->
                assertThat(rejected.failure().safeCode()).isEqualTo("MEDIA_UNRESOLVABLE"));
        unresolved.server().verify();
    }

    @Test
    void sameSubmissionKeyIsNotDeduped() {
        FashnTestSupport.Harness harness = FashnTestSupport.harness();
        harness.server().expect(times(2), requestTo(FashnTestSupport.RUN_URL))
            .andRespond(withSuccess(FashnTestSupport.runSuccess(), MediaType.APPLICATION_JSON));
        SubmissionKey key = FashnTestSupport.key();

        harness.provider().submit(FashnTestSupport.request(FashnTestSupport.STANDARD), key);
        harness.provider().submit(FashnTestSupport.request(FashnTestSupport.STANDARD), key);
        harness.server().verify();
    }

    private static void assertApiRejection(int status, String error, String message, ProviderFailureCategory category, boolean retryable) {
        FashnTestSupport.Harness harness = FashnTestSupport.harness();
        expectRun(harness).andRespond(withStatus(HttpStatus.valueOf(status))
            .body(FashnTestSupport.apiError(error, message))
            .contentType(MediaType.APPLICATION_JSON));

        GenerationSubmission submission = harness.provider().submit(FashnTestSupport.request(FashnTestSupport.STANDARD), FashnTestSupport.key());

        assertThat(submission).isInstanceOfSatisfying(GenerationSubmission.Rejected.class, rejected -> {
            ProviderFailure failure = rejected.failure();
            assertThat(failure.category()).isEqualTo(category);
            assertThat(failure.retryable()).isEqualTo(retryable);
            assertThat(failure.safeCode() + rejected).doesNotContain(message, "https://", "data:", "base64");
        });
        harness.server().verify();
    }

    private static void assertOpenOutputFailure(String body, String code) {
        FashnTestSupport.Harness harness = FashnTestSupport.harness();
        expectStatusBody(harness, body);
        assertThatExceptionOfType(FashnProviderException.class)
            .isThrownBy(() -> harness.provider().openOutput(outputRef()))
            .satisfies(ex -> {
                assertThat(ex.failure().safeCode()).isEqualTo(code);
                assertThat(ex.getMessage()).isEqualTo(code);
                assertThat(ex.getMessage()).doesNotContain("data:", "https://", "base64");
            });
        harness.server().verify();
    }

    private static org.springframework.test.web.client.ResponseActions expectRun(FashnTestSupport.Harness harness) {
        return harness.server().expect(once(), requestTo(FashnTestSupport.RUN_URL))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + FashnTestSupport.API_KEY))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    private static void expectStatus(FashnTestSupport.Harness harness, String status, String output) {
        expectStatusBody(harness, FashnTestSupport.status(status, output));
    }

    private static void expectStatusBody(FashnTestSupport.Harness harness, String body) {
        harness.server().expect(once(), requestTo(FashnTestSupport.STATUS_URL))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + FashnTestSupport.API_KEY))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private static String paddedCompletedBody(int bytes) {
        return paddedBody(FashnTestSupport.completedImage(), bytes);
    }

    private static String paddedBody(String body, int bytes) {
        String prefix = body.substring(0, body.length() - 1) + ",\"padding\":\"";
        String suffix = "\"}";
        int padding = bytes - prefix.length() - suffix.length();
        if (padding < 0) {
            throw new IllegalArgumentException("requested body is too small");
        }
        String padded = prefix + "x".repeat(padding) + suffix;
        assertThat(padded).hasSize(bytes);
        return padded;
    }

    private static GenerationStatus observed(StatusQueryResult result) {
        assertThat(result).isInstanceOf(StatusQueryResult.Observed.class);
        return ((StatusQueryResult.Observed) result).status();
    }

    private static ProviderJobId job() {
        return new ProviderJobId(FashnTestSupport.PREDICTION_ID);
    }

    private static ProviderOutputRef outputRef() {
        return new ProviderOutputRef(FashnTestSupport.PREDICTION_ID + ":0");
    }

    private static ClientHttpRequestFactory connectFailureFactory(AtomicInteger attempts) {
        return (uri, method) -> {
            attempts.incrementAndGet();
            throw new ConnectException("Connection refused");
        };
    }

    private static ClientHttpRequestFactory countingTimeoutFactory(AtomicInteger attempts) {
        ClientHttpRequestFactory inner = FashnTestSupport.timeoutFactory();
        return (uri, method) -> {
            attempts.incrementAndGet();
            return inner.createRequest(uri, method);
        };
    }
}
