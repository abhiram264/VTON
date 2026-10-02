package com.aifashionvault.platform.inferhub;

import java.nio.charset.StandardCharsets;
import java.util.List;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import com.aifashionvault.generation.vton.GenerationSubmission;
import com.aifashionvault.generation.vton.ProviderFailure;
import com.aifashionvault.generation.vton.ProviderFailureCategory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * t300/U82: the transient-vs-permanent retry classification of the InferHub adapter,
 * pinned as observed output rather than as a copy of the mapper's constants.
 *
 * <p>Only the facts the six landed InferHub classes leave UNPINNED are asserted here;
 * see the class-level notes per test for what is already covered elsewhere:
 * <ul>
 *   <li>502/503 -> {@code TEMPORARY_UNAVAILABLE} + retryable: already pinned by
 *       {@code InferHubMapperTest:139-148} and {@code InferHubMapperContractTest:222-229},
 *       so NOT re-asserted here. What is NOT pinned there, and IS pinned here, is that the
 *       classification is taken off the HTTP STATUS and therefore survives an unparseable
 *       body (U51 shape (k), §3).</li>
 *   <li>429 -> {@code RATE_CAPACITY} + retryable: {@code InferHubMapperContractTest:242-253}
 *       only touches 429 to assert the contract version, never the category or retryability.
 *       Pinned here for the first time.</li>
 *   <li>401/403 -> {@code AUTH_CONFIGURATION} + NOT retryable: 401 is pinned at
 *       {@code InferHubMapperContractTest:232-240} and {@code InferHubVtonProviderTest:424-432};
 *       403 is not. Both are pinned here together because the pair is one branch
 *       ({@code InferHubMapper.java:189-191}) and an asymmetric regression of only 403
 *       would otherwise go unnoticed.</li>
 * </ul>
 *
 * <p>Deliberately NOT asserted: any {@code Retry-After} value. Per U51 §6.1 the InferHub
 * adapter classifies 429 from the status int alone ({@code mapApiError(int)}, no body, no
 * headers), unlike FASHN which parses the header at {@code FashnClient.java:99-100}. Asserting
 * a {@code Retry-After} effect here would pin behavior the adapter does not have.
 */
class InferHubRetryClassificationTest {

    private static final InferHubMapper MAPPER = new InferHubMapper();

    /** Fail-fast predicate mirroring the production filter at GenerationWorkProcessor:2223-2231. */
    private static final List<ProviderFailureCategory> RETRYABLE_FAMILIES = List.of(
        ProviderFailureCategory.TIMEOUT_NETWORK,
        ProviderFailureCategory.RATE_CAPACITY,
        ProviderFailureCategory.TEMPORARY_UNAVAILABLE);

    private static ProviderFailure failureOf(GenerationSubmission submission) {
        assertThat(submission).isInstanceOf(GenerationSubmission.Rejected.class);
        return ((GenerationSubmission.Rejected) submission).failure();
    }

    private static boolean retriedByProcessor(ProviderFailure failure) {
        return failure.retryable() && RETRYABLE_FAMILIES.contains(failure.category());
    }

    /**
     * Case 1 (unpinned variant): {@code mapRun} takes the status branch before it ever looks at
     * the body. Each body below goes through the SAME parse the client performs
     * ({@code InferHubClient.parse:78-88}, which swallows a Jackson failure into a null body)
     * and the result is handed to the mapper, so an HTML error page, a truncated stream, an
     * empty body and a JSON null all reach {@code mapRun} the way production delivers them.
     *
     * <p>Fails if the status check is ever moved behind the body parse, or if an unparseable
     * body is allowed to downgrade a retryable classification to a protocol violation.
     */
    @Test
    void anUnparseableBodyDoesNotDowngradeATransientStatus() {
        String[] garbageBodies = {
            "<html>502 Bad Gateway</html>",
            "{\"error\":{\"message\":\"upstream",   // truncated mid-object
            "",                                      // empty
            "null"};                                 // JSON null

        for (int status : new int[] {502, 503}) {
            for (String body : garbageBodies) {
                JsonNode parsed = parseAsClientDoes(body);
                assertThat(parsed).as("body %q must not parse to a node", body).isNull();

                ProviderFailure failure = failureOf(MAPPER.mapRun(
                    InferHubHttpResponse.http(status, parsed, false)).submission());
                assertThat(failure.category())
                    .as("status %d with body %q", status, body)
                    .isEqualTo(ProviderFailureCategory.TEMPORARY_UNAVAILABLE);
                assertThat(failure.retryable()).isTrue();
                assertThat(failure.safeCode()).isEqualTo("PROVIDER_INTERNAL");
            }
        }
    }

    /**
     * Case 1 (parseable but image-less 502 body): the opposite failure mode — a body that
     * parses cleanly into a well-formed JSON error envelope must not be mistaken for a
     * protocol violation. Fails if the status branch ever falls through to the success path.
     */
    @Test
    void aWellFormedErrorBodyOnATransientStatusIsStillTransient() {
        String envelope = "{\"error\":{\"message\":\"upstream model overloaded\",\"type\":\"server_error\"}}";

        for (int status : new int[] {502, 503}) {
            ProviderFailure failure = failureOf(MAPPER.mapRun(
                InferHubHttpResponse.http(status, json(envelope), false)).submission());

            assertThat(failure.category()).isEqualTo(ProviderFailureCategory.TEMPORARY_UNAVAILABLE);
            assertThat(failure.retryable()).isTrue();
            assertThat(retriedByProcessor(failure)).isTrue();
        }
    }

    /**
     * Case 5: 429 is the rate-capacity family. Nothing landed asserts this. Fails if 429
     * falls into the 4xx tail (UNKNOWN_PROVIDER_FAILURE / not retryable), which would silently
     * convert a throttle into a permanent failure and drop the job instead of backing off.
     */
    @Test
    void rateLimitIsRetryableRateCapacity() {
        ProviderFailure failure = failureOf(MAPPER.mapRun(
            InferHubHttpResponse.http(429, json("{\"error\":\"too many requests\"}"), false)).submission());

        assertThat(failure.category()).isEqualTo(ProviderFailureCategory.RATE_CAPACITY);
        assertThat(failure.retryable()).isTrue();
        assertThat(failure.safeCode()).isEqualTo("RATE_LIMIT");
        assertThat(retriedByProcessor(failure)).isTrue();
    }

    /**
     * Case 4: 401 AND 403 both fail fast. Nothing is scheduled for an auth configuration
     * failure. Fails if either status is mapped retryable, or if it escapes the auth family —
     * a retried bad key burns the paid-attempt budget on a failure that can never succeed.
     */
    @Test
    void authFailuresFailFastAndAreNeverRetried() {
        for (int status : new int[] {401, 403}) {
            ProviderFailure failure = failureOf(MAPPER.mapRun(
                InferHubHttpResponse.http(status, json("{\"error\":\"unauthorized\"}"), false)).submission());

            assertThat(failure.category())
                .as("status %d", status)
                .isEqualTo(ProviderFailureCategory.AUTH_CONFIGURATION);
            assertThat(failure.retryable()).isFalse();
            assertThat(failure.safeCode()).isEqualTo("AUTH_CONFIGURATION");
            assertThat(retriedByProcessor(failure)).isFalse();
        }
    }

    /**
     * The permanent/transient split is symmetric: every 5xx retries, and the 4xx that are
     * NOT auth do NOT. Pins the boundary so widening 5xx to "all non-2xx retry" or narrowing
     * the transient band both fail here.
     */
    @Test
    void theTransientBandIsExactly5xxPlus429() {
        assertThat(MAPPER.mapApiError(500).category())
            .isEqualTo(ProviderFailureCategory.TEMPORARY_UNAVAILABLE);
        assertThat(MAPPER.mapApiError(504).category())
            .isEqualTo(ProviderFailureCategory.TEMPORARY_UNAVAILABLE);
        assertThat(MAPPER.mapApiError(429).category())
            .isEqualTo(ProviderFailureCategory.RATE_CAPACITY);
        // 400 is already pinned by InferHubMapperContractTest:237-239; the rest of the
        // permanent 4xx family is not.
        for (int status : new int[] {404, 422}) {
            ProviderFailure failure = MAPPER.mapApiError(status);
            assertThat(failure.category()).as("status %d", status)
                .isEqualTo(ProviderFailureCategory.INVALID_REQUEST);
            assertThat(failure.retryable()).isFalse();
        }
    }

    /** The client's own parse contract: empty, null and unparseable bodies all become null. */
    private static JsonNode parseAsClientDoes(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0) {
            return null;
        }
        try {
            JsonNode node = new ObjectMapper().readTree(bytes);
            return node == null || node.isNull() ? null : node;
        } catch (JacksonException ex) {
            return null;
        }
    }

    private static JsonNode json(String value) {
        return new ObjectMapper().readTree(value.getBytes(StandardCharsets.UTF_8));
    }
}
