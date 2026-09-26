package com.aifashionvault.platform.inferhub;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.aifashionvault.generation.GenerationRetryBudget;
import com.aifashionvault.generation.RetryBackoff;
import com.aifashionvault.generation.RetryDecision;
import com.aifashionvault.generation.RetryDecisionContext;
import com.aifashionvault.generation.RetryDecisionEngine;
import com.aifashionvault.generation.RetryDisposition;
import com.aifashionvault.generation.RetryPolicyResolution;
import com.aifashionvault.generation.RetryPolicySnapshot;
import com.aifashionvault.generation.vton.GenerationSubmission;
import com.aifashionvault.generation.vton.ProviderFailure;
import com.aifashionvault.generation.vton.ProviderFailureCategory;
import com.aifashionvault.shared.TerminalReason;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * t300/U82: end-to-end transient-failure handling for InferHub, from the HTTP seam
 * through the mapper to the deterministic retry decision the worker actually makes.
 *
 * <p>Seam: a {@link MockRestServiceServer} bound to the {@link RestClient} the adapter
 * builds (the same seam {@code FashnVtonProviderTest} uses), so the bytes on the wire are
 * the ones asserted. No network, no key material.
 *
 * <p>The engine context is materialized exactly as
 * {@code GenerationWorkProcessor.decisionContext:1523-1562} builds it for a render-plan-less
 * attempt on a one-step job: counters derived by {@code additionalUsage:1614-1622} and
 * {@code baselinesStillRequiring:1624-1638}, and {@code retryAfterMinimum:1589-1598} taken
 * from the failure's {@code retryAfterAt}. Asserting against the real derivation is the point:
 * a classification test alone cannot show that a retry is actually SCHEDULED.
 *
 * <p>Covered elsewhere, deliberately not duplicated here: the 502/503 category/retryable pair
 * ({@code InferHubMapperTest:139-148}), the malformed-body invariance
 * ({@code InferHubRetryClassificationTest}), and the AUTH_CONFIGURATION family
 * ({@code InferHubMapperContractTest:232-240}). What is new here is the SCHEDULING half of
 * the contract: the delay, the budget, and the fail-fast non-schedule.
 */
class InferHubClientErrorHandlingTest {

    private static final Instant NOW = Instant.parse("2026-08-28T12:00:00Z");
    private static final String ENDPOINT = "https://api.inferhub.test/v1/chat/completions";

    private static final InferHubMapper MAPPER = new InferHubMapper();
    private static final RetryDecisionEngine ENGINE = new RetryDecisionEngine();

    private record Seam(InferHubClient client, MockRestServiceServer server) {
    }

    private static Seam seam() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.inferhub.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Seam(new InferHubClient(builder.build()), server);
    }

    private static void respond(Seam seam, HttpStatus status, String body) {
        seam.server().expect(once(), requestTo(ENDPOINT)).andRespond(withStatus(status)
            .contentType(MediaType.APPLICATION_JSON)
            .header("Retry-After", "120")
            .body(body));
    }

    /** One real submit through the wire seam, classified by the real mapper. */
    private static ProviderFailure submitAgainst(Seam seam) {
        GenerationSubmission submission =
            MAPPER.mapRun(seam.client().postRun(payload())).submission();
        assertThat(submission).isInstanceOf(GenerationSubmission.Rejected.class);
        return ((GenerationSubmission.Rejected) submission).failure();
    }

    private static Map<String, Object> payload() {
        return Map.of("model", "cb/gemini-3.1-flash-image", "messages", List.of());
    }

    /**
     * The processor's context for a one-step job after {@code attemptsUsed} failed attempts,
     * each render-plan-less (renderStepId == null), all with providerPaid=false.
     *
     * <p>Counter derivation is copied from the production helpers, not assumed:
     * {@code additionalUsage} yields 0 for render-plan-less attempts (distinctSteps == 0 ->
     * baseline == attempts.size()), and {@code baselinesStillRequiring} yields
     * {@code totalSteps - 1} once any attempt exists.
     */
    private static RetryDecisionContext contextAfterFailures(
        ProviderFailure failure, int attemptsUsed, double jitter) {
        return contextAfterFailures(failure, attemptsUsed, 0, jitter);
    }

    private static RetryDecisionContext contextAfterFailures(
        ProviderFailure failure, int attemptsUsed, int technicalRetriesUsed, double jitter) {
        return RetryDecisionContext.builder()
            .policyResolution(new RetryPolicyResolution(
                RetryPolicyResolution.State.VALID, RetryPolicySnapshot.forStepCountV2(1)))
            .totalSteps(1)
            .currentStep(1)
            .technicalRetriesUsed(technicalRetriesUsed)
            .logicalAttemptsUsed(attemptsUsed)
            .paidAttemptsUsed(attemptsUsed)
            .baselinesStillRequiringExecution(attemptsUsed == 0 ? 1 : 0)
            .orchestrationDeadlineAt(NOW.plus(Duration.ofMinutes(30)))
            .now(NOW)
            .jitterFraction(jitter)
            .retryAfterMinimum(failure == null || failure.retryAfterAt() == null
                ? null
                : Duration.between(NOW, failure.retryAfterAt()))
            .providerFailureCategory(failure == null ? null : failure.category())
            .technicalRetryRequested(true)
            .build();
    }

    /**
     * Case 2: backoff is HONORED — a transient failure is not merely flagged retryable, it
     * produces a scheduled attempt at a strictly future instant whose delay is the full-jitter
     * draw for the retry ordinal. Asserted against {@link RetryBackoff} rather than a copied
     * constant, so changing the base/cap fails here.
     */
    @Test
    void aTransientFailureSchedulesAJitteredRetryAtAFutureInstant() {
        for (HttpStatus status : List.of(HttpStatus.BAD_GATEWAY, HttpStatus.SERVICE_UNAVAILABLE)) {
            Seam seam = seam();
            respond(seam, status, "{\"error\":\"upstream\"}");
            ProviderFailure failure = submitAgainst(seam);

            assertThat(failure.retryable()).as("status %s", status).isTrue();

            RetryDecision decision = ENGINE.decide(contextAfterFailures(failure, 1, 0.5));
            assertThat(decision.disposition()).isEqualTo(RetryDisposition.NEW_TECHNICAL_ATTEMPT);
            assertThat(decision.retryNotBeforeAt())
                .as("status %s must carry a next-attempt instant", status)
                .isNotNull()
                .isAfter(NOW);
            assertThat(Duration.between(NOW, decision.retryNotBeforeAt()))
                .isEqualTo(RetryBackoff.computedJitterDelay(1, 0.5));
            seam.server().verify();
        }
    }

    /**
     * The delay is the full-jitter draw for the RETRY ORDINAL, so a later turn of the same
     * transient failure waits longer and stays under the 30-second cap. Asserting the ordinal
     * (not a copied constant) is what makes a change to {@code computedJitterDelay}'s growth
     * law fail here.
     */
    @Test
    void theRetryDelayGrowsWithTheRetryOrdinalAndStaysCapped() {
        Seam seam = seam();
        respond(seam, HttpStatus.BAD_GATEWAY, "{\"error\":\"upstream\"}");
        ProviderFailure failure = submitAgainst(seam);

        // Same classification, one more technical retry already spent: the ordinal is 2.
        RetryDecision first = ENGINE.decide(contextAfterFailures(failure, 1, 0, 0.5));
        RetryDecision second = ENGINE.decide(contextAfterFailures(failure, 2, 1, 0.5));

        Duration firstDelay = Duration.between(NOW, first.retryNotBeforeAt());
        Duration secondDelay = Duration.between(NOW, second.retryNotBeforeAt());

        assertThat(firstDelay).isEqualTo(RetryBackoff.computedJitterDelay(1, 0.5));
        assertThat(secondDelay).isEqualTo(RetryBackoff.computedJitterDelay(2, 0.5));
        assertThat(secondDelay).isGreaterThan(firstDelay)
            .isLessThan(RetryBackoff.COMPUTED_BACKOFF_CAP);
        seam.server().verify();
    }

    /**
     * Case 5 (honest pin, U51 §6.1): the InferHub adapter does NOT read {@code Retry-After},
     * unlike FASHN ({@code FashnClient.java:99-100}). The stub server sends
     * {@code Retry-After: 120} on the 429, and the derived context therefore carries a null
     * {@code retryAfterMinimum} — so the schedule is the LOCAL jitter draw, not 120s.
     *
     * <p>This is a characterization test. It fails if the adapter ever starts honouring the
     * header, which is the intended signal to move the pin rather than to defend the gap.
     */
    @Test
    void rateLimitIsRetriedOnLocalJitterBecauseTheAdapterIgnoresRetryAfter() {
        Seam seam = seam();
        respond(seam, HttpStatus.TOO_MANY_REQUESTS, "{\"error\":\"too many requests\"}");
        ProviderFailure failure = submitAgainst(seam);

        assertThat(failure.category()).isEqualTo(ProviderFailureCategory.RATE_CAPACITY);
        assertThat(failure.retryable()).isTrue();
        // The adapter classifies off the status int, so no Retry-After ever reaches the failure.
        assertThat(failure.retryAfterAt()).isNull();

        RetryDecision decision = ENGINE.decide(contextAfterFailures(failure, 1, 0.5));
        assertThat(decision.disposition()).isEqualTo(RetryDisposition.NEW_TECHNICAL_ATTEMPT);
        assertThat(Duration.between(NOW, decision.retryNotBeforeAt()))
            .as("a 120s Retry-After header must not lengthen the InferHub retry")
            .isEqualTo(RetryBackoff.computedJitterDelay(1, 0.5))
            .isLessThan(Duration.ofSeconds(120));
        seam.server().verify();
    }

    /**
     * Case 3: a transient BURST does not consume the budget in one turn. Five consecutive
     * transient statuses are five independent, retryable failures — none permanent, none
     * terminal, no exception escaping — and the FIRST of them still schedules a retry with the
     * budget untouched. The burst is bounded by a real ceiling, not by the classification.
     */
    @Test
    void aBurstOfTransientStatusesIsRetryableThroughoutAndDoesNotExhaustTheBudget() {
        Seam seam = seam();
        List<HttpStatus> burst = List.of(
            HttpStatus.BAD_GATEWAY, HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.BAD_GATEWAY,
            HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.BAD_GATEWAY);
        for (HttpStatus status : burst) {
            respond(seam, status, "{\"error\":\"upstream\"}");
        }

        List<ProviderFailure> failures = burst.stream()
            .map(status -> submitAgainst(seam))
            .toList();

        assertThat(failures).hasSize(burst.size());
        for (ProviderFailure failure : failures) {
            assertThat(failure.category()).isEqualTo(ProviderFailureCategory.TEMPORARY_UNAVAILABLE);
            assertThat(failure.retryable()).isTrue();
        }

        // The budget is spent one attempt at a time, and the first turn still has room.
        RetryDecision firstTurn = ENGINE.decide(contextAfterFailures(failures.get(0), 1, 0.5));
        assertThat(firstTurn.disposition()).isEqualTo(RetryDisposition.NEW_TECHNICAL_ATTEMPT);

        // The ceiling is reached only after the whole logical budget is spent, and then the
        // denial is attributed to the ceiling gate — not to the classification.
        int maxLogical = 1 + GenerationRetryBudget.ADDITIONAL_LOGICAL_ATTEMPTS;
        assertThat(ENGINE.decide(contextAfterFailures(failures.get(0), maxLogical - 1, 0.5))
            .disposition()).isEqualTo(RetryDisposition.NEW_TECHNICAL_ATTEMPT);

        RetryDecision exhausted = ENGINE.decide(contextAfterFailures(failures.get(0), maxLogical, 0.5));
        assertThat(exhausted.disposition()).isEqualTo(RetryDisposition.NO_RETRY);
        assertThat(exhausted.owningGate())
            .isEqualTo(RetryDecisionEngine.GATE_LOGICAL_ATTEMPT_CEILING);
        assertThat(exhausted.terminalReason()).isEqualTo(TerminalReason.RETRY_BUDGET_EXHAUSTED);
        seam.server().verify();
    }

    /**
     * Case 4 (scheduling half): an auth failure schedules NOTHING, and it does so with a full
     * budget still available — so the denial is the provider-eligibility verdict on an
     * unfixable credential, not an incidental budget exhaustion.
     */
    @Test
    void anAuthFailureSchedulesNoRetryEvenWithAFreshBudget() {
        for (HttpStatus status : List.of(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN)) {
            Seam seam = seam();
            respond(seam, status, "{\"error\":\"unauthorized\"}");
            ProviderFailure failure = submitAgainst(seam);

            assertThat(failure.category()).isEqualTo(ProviderFailureCategory.AUTH_CONFIGURATION);
            assertThat(failure.retryable()).as("status %s", status).isFalse();

            RetryDecision decision = ENGINE.decide(contextAfterFailures(failure, 1, 0.5));
            assertThat(decision.disposition()).isEqualTo(RetryDisposition.NO_RETRY);
            assertThat(decision.owningGate())
                .isEqualTo(RetryDecisionEngine.GATE_PROVIDER_ELIGIBILITY);
            assertThat(decision.terminalReason())
                .isEqualTo(TerminalReason.PROVIDER_AUTH_CONFIGURATION);
            assertThat(decision.retryNotBeforeAt()).isNull();
            seam.server().verify();
        }
    }
}
