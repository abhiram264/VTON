package com.aifashionvault.generation.vton;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import com.aifashionvault.generation.GenerationAttempt;
import com.aifashionvault.generation.ProviderTurnOutcome;
import com.aifashionvault.look.Look;
import com.aifashionvault.notification.Notification;
import com.aifashionvault.shared.AvatarProfileId;
import com.aifashionvault.shared.ClientRequestId;
import com.aifashionvault.shared.GenerationJobId;
import com.aifashionvault.shared.LookId;
import com.aifashionvault.shared.NotificationId;
import com.aifashionvault.shared.QualityDecision;
import com.aifashionvault.shared.StudioSessionId;
import com.aifashionvault.shared.UserId;
import java.util.concurrent.atomic.AtomicInteger;

import com.aifashionvault.shared.GenerationAttemptId;
import com.aifashionvault.shared.StorageKey;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class VtonProviderContractsTest {

    private static final VtonMode STANDARD = new VtonMode("standard");
    private static final ItemCategory CLOTHING = new ItemCategory("clothing");
    private static final PrivateMediaRef AVATAR = media("private/avatar");
    private static final VtonProductAsset PRODUCT = new VtonProductAsset(media("private/product"), CLOTHING);

    @Test
    void valueObjectsRejectMediaLocatorsAndNormalizeSafeOpaqueValues() {
        assertThat(new ProviderKey("  provider-a ").value()).isEqualTo("provider-a");
        assertThat(new ProviderJobId("  remote-42 ").value()).isEqualTo("remote-42");
        assertThat(new ProviderOutputRef("  output-42 ").value()).isEqualTo("output-42");
        assertThat(new VtonMode(" Standard ").value()).isEqualTo("standard");
        assertThat(new ItemCategory(" Headwear ").value()).isEqualTo("headwear");

        assertThatIllegalArgumentException().isThrownBy(() -> new ProviderKey("Provider A"));
        assertThatIllegalArgumentException().isThrownBy(() -> new ProviderJobId("https://provider.example/jobs/42"));
        assertThatIllegalArgumentException().isThrownBy(() -> new ProviderOutputRef("data:image/png;base64,abc"));
        assertThatIllegalArgumentException().isThrownBy(() -> new PrivateMediaRef(new StorageKey("https://example.test/a"), OptionalLong.empty()));
    }

    @Test
    void requestIsImmutableRequiresProductsRejectsDuplicatesAndNormalizesBlankInstructions() {
        var products = new java.util.ArrayList<>(List.of(PRODUCT));
        var request = new VtonRequest(AVATAR, products, Optional.of("   "), STANDARD);
        products.clear();

        assertThat(request.productAssets()).containsExactly(PRODUCT);
        assertThat(request.instructions()).isEmpty();
        assertThatExceptionOfType(UnsupportedOperationException.class)
            .isThrownBy(() -> request.productAssets().add(PRODUCT));
        assertThatIllegalArgumentException().isThrownBy(() -> new VtonRequest(AVATAR, List.of(), Optional.empty(), STANDARD));
        assertThatIllegalArgumentException().isThrownBy(() -> new VtonRequest(AVATAR, java.util.Arrays.asList(PRODUCT, null), Optional.empty(), STANDARD));
        assertThatIllegalArgumentException().isThrownBy(() -> new VtonRequest(AVATAR, List.of(PRODUCT, PRODUCT), Optional.empty(), STANDARD));
    }

    @Test
    void capabilityValidationCoversModeCategoryCountInstructionsAndKnownSize() {
        var mode = new ModeCapabilities(Set.of(CLOTHING), 1, false, new InputSizeLimit.Known(100));
        var capabilities = capabilities(Map.of(STANDARD, mode), SubmissionReplaySafety.UNSAFE);

        assertThat(VtonRequestValidator.validate(request(AVATAR, List.of(PRODUCT), Optional.empty(), STANDARD), capabilities)).isEmpty();
        assertFailure(request(AVATAR, List.of(PRODUCT), Optional.empty(), new VtonMode("hd")), capabilities, ProviderFailureCategory.UNSUPPORTED_CAPABILITY);
        assertFailure(request(AVATAR, List.of(new VtonProductAsset(media("shoe"), new ItemCategory("footwear"))), Optional.empty(), STANDARD), capabilities, ProviderFailureCategory.UNSUPPORTED_CAPABILITY);
        assertFailure(request(AVATAR, List.of(PRODUCT, new VtonProductAsset(media("product-2"), CLOTHING)), Optional.empty(), STANDARD), capabilities, ProviderFailureCategory.UNSUPPORTED_CAPABILITY);
        assertFailure(request(AVATAR, List.of(PRODUCT), Optional.of("keep logo"), STANDARD), capabilities, ProviderFailureCategory.UNSUPPORTED_CAPABILITY);
        assertFailure(request(media("large-avatar", 101), List.of(PRODUCT), Optional.empty(), STANDARD), capabilities, ProviderFailureCategory.INVALID_REQUEST);
    }

    @Test
    void validatingProviderRejectsLocallyWithoutInvokingAdapterSubmission() {
        var calls = new AtomicInteger();
        var provider = new ValidatingVtonProvider() {
            @Override public ProviderKey providerKey() { return new ProviderKey("fake"); }
            @Override public ProviderCapabilities capabilities() {
                return VtonProviderContractsTest.capabilities(Map.of(STANDARD, new ModeCapabilities(Set.of(CLOTHING), 1, false, InputSizeLimit.unknown())), SubmissionReplaySafety.UNSAFE);
            }
            @Override protected GenerationSubmission submitValid(VtonRequest request, SubmissionKey key) {
                calls.incrementAndGet();
                return new GenerationSubmission.Accepted(new ProviderJobId("remote"), Optional.empty());
            }
            @Override public StatusQueryResult getStatus(ProviderJobId providerJobId) { return new StatusQueryResult.Observed(new GenerationStatus.Running()); }
            @Override public ProviderMediaSource openOutput(ProviderOutputRef outputRef) { throw new UnsupportedOperationException(); }
        };

        var submission = provider.submit(request(AVATAR, List.of(PRODUCT), Optional.of("unsupported"), STANDARD), new SubmissionKey(UUID.randomUUID()));

        assertThat(submission).isInstanceOf(GenerationSubmission.Rejected.class);
        assertThat(calls).hasValue(0);
    }

    @Test
    void submissionAndStatusResultsKeepAmbiguityAndObservationFailuresDistinct() {
        var timeout = new ProviderFailure(ProviderFailureCategory.TIMEOUT_NETWORK, true, "REQUEST_TIMEOUT");
        var rejected = new GenerationSubmission.Rejected(new ProviderFailure(ProviderFailureCategory.INVALID_REQUEST, false, "BAD_INPUT"));
        var indeterminate = new GenerationSubmission.Indeterminate(timeout);
        var unavailable = new StatusQueryResult.Unavailable(timeout);
        var failed = new StatusQueryResult.Observed(new GenerationStatus.Failed(new ProviderFailure(ProviderFailureCategory.CONTENT_POLICY_REJECTION, false, "POLICY"), Optional.empty()));

        assertThat(rejected).isNotEqualTo(indeterminate);
        assertThat(unavailable).isNotEqualTo(failed);
        assertThat(new GenerationStatus.Queued().lifecycle()).isEqualTo(GenerationLifecycle.QUEUED);
        assertThat(new GenerationStatus.Running().lifecycle()).isEqualTo(GenerationLifecycle.RUNNING);
        assertThat(new GenerationStatus.Succeeded(List.of(new ProviderOutputRef("result")), Optional.empty()).lifecycle()).isEqualTo(GenerationLifecycle.SUCCEEDED);
        assertThat(((StatusQueryResult.Observed) failed).status().lifecycle()).isEqualTo(GenerationLifecycle.FAILED);
        assertThat(new GenerationSubmission.Accepted(new ProviderJobId("job"), Optional.of(new ProviderUsage(new BigDecimal("1.5"), new BigDecimal("0.12"), "USD"))).providerJobId().value()).isEqualTo("job");

        VtonRequest validRequest = request(AVATAR, List.of(PRODUCT), Optional.empty(), STANDARD);
        VtonProvider acceptedProvider = provider("turn-provider");
        assertThat(acceptedProvider.submitTurn(validRequest, new SubmissionKey(UUID.randomUUID())))
            .isEqualTo(new ProviderTurnOutcome.Accepted(new ProviderJobId("remote")));
        assertThat(new ValidatingVtonProvider() {
            @Override public ProviderKey providerKey() { return new ProviderKey("indeterminate"); }
            @Override public ProviderCapabilities capabilities() { return acceptedProvider.capabilities(); }
            @Override protected GenerationSubmission submitValid(VtonRequest ignored, SubmissionKey key) {
                return new GenerationSubmission.Indeterminate(timeout);
            }
            @Override public StatusQueryResult getStatus(ProviderJobId id) { throw new UnsupportedOperationException(); }
            @Override public ProviderMediaSource openOutput(ProviderOutputRef ref) { throw new UnsupportedOperationException(); }
        }.submitTurn(validRequest, new SubmissionKey(UUID.randomUUID())))
            .isEqualTo(new ProviderTurnOutcome.Indeterminate(timeout));
        assertThatIllegalArgumentException().isThrownBy(() -> new ProviderFailure(ProviderFailureCategory.UNKNOWN_PROVIDER_FAILURE, false, "https://provider.example/body"));
    }

    @Test
    void providerFailureTrailingContractVersionIsOptionalForLegacyPorts() {
        assertThat(ProviderFailure.class.getRecordComponents())
            .extracting(RecordComponent::getName)
            .containsExactly("category", "retryable", "safeCode", "retryAfterAt", "mappingContractVersion");

        var legacy = new ProviderFailure(ProviderFailureCategory.RATE_CAPACITY, true, "RATE_LIMITED");
        assertThat(legacy.mappingContractVersion()).isNull();
        var fourArgument = new ProviderFailure(
            ProviderFailureCategory.RATE_CAPACITY,
            true,
            "RATE_LIMITED",
            Instant.parse("2026-08-28T12:34:56Z"));
        assertThat(fourArgument.mappingContractVersion()).isNull();

        var stamped = new ProviderFailure(
            ProviderFailureCategory.RATE_CAPACITY,
            true,
            "RATE_LIMITED",
            null,
            "fashn-mapping-v1");
        assertThat(stamped.mappingContractVersion()).isEqualTo("fashn-mapping-v1");

        assertThatIllegalArgumentException().isThrownBy(() -> new ProviderFailure(
            ProviderFailureCategory.RATE_CAPACITY,
            true,
            "RATE_LIMITED",
            null,
            "contains space"));
        assertThatIllegalArgumentException().isThrownBy(() -> new ProviderFailure(
            ProviderFailureCategory.RATE_CAPACITY,
            true,
            "RATE_LIMITED",
            null,
            "a".repeat(129)));
    }

    @Test
    void successfulStatusCarriesOnlyOpaqueOutputsAndStreamingSourceIsBounded() throws Exception {
        var status = new GenerationStatus.Succeeded(List.of(new ProviderOutputRef("result-1")), Optional.empty());
        assertThat(status.outputs()).extracting(ProviderOutputRef::value).containsExactly("result-1");
        assertThatIllegalArgumentException().isThrownBy(() -> new GenerationStatus.Succeeded(List.of(), Optional.empty()));

        try (ProviderMediaSource source = ProviderMediaSource.of(new ByteArrayInputStream(new byte[] {1, 2, 3}), OptionalLong.of(3), 10, "image/png")) {
            assertThat(source.maxBytes()).isEqualTo(10);
            assertThat(source.contentLength()).hasValue(3);
            assertThat(source.stream().readAllBytes()).containsExactly(1, 2, 3);
        }
        assertThatIllegalArgumentException().isThrownBy(() -> ProviderMediaSource.of(new ByteArrayInputStream(new byte[0]), OptionalLong.of(11), 10, "image/png"));
        try (ProviderMediaSource source = ProviderMediaSource.of(new ByteArrayInputStream(new byte[11]), OptionalLong.empty(), 10, "image/png")) {
            assertThatExceptionOfType(java.io.IOException.class).isThrownBy(() -> source.stream().readAllBytes());
        }
    }

    @Test
    void capabilitiesAreImmutableModeScopedAndSeparateTransferDirections() {
        var modes = new java.util.HashMap<VtonMode, ModeCapabilities>();
        modes.put(STANDARD, new ModeCapabilities(Set.of(CLOTHING), 1, true, InputSizeLimit.unknown()));
        var capabilities = new ProviderCapabilities(modes, Set.of(InputTransferMethod.BOUNDED_INLINE_DATA, InputTransferMethod.EPHEMERAL_SIGNED_READ), Set.of(OutputDeliveryMethod.EPHEMERAL_PROVIDER_READ), SubmissionReplaySafety.SAME_KEY_SAFE);
        modes.clear();

        assertThat(capabilities.forMode(STANDARD)).isPresent();
        assertThat(capabilities.inputTransfers()).containsExactlyInAnyOrder(InputTransferMethod.BOUNDED_INLINE_DATA, InputTransferMethod.EPHEMERAL_SIGNED_READ);
        assertThat(capabilities.outputDeliveries()).containsExactly(OutputDeliveryMethod.EPHEMERAL_PROVIDER_READ);
        assertThat(capabilities.submissionReplaySafety()).isEqualTo(SubmissionReplaySafety.SAME_KEY_SAFE);
        assertThatExceptionOfType(UnsupportedOperationException.class)
            .isThrownBy(() -> capabilities.modes().put(new VtonMode("preview"), capabilities.modes().get(STANDARD)));
    }

    @Test
    void privacyPolicyPrefersBoundedInlineThenShortestEligibleSignedRead() {
        var policy = new PrivacyTransferPolicy(
            Set.of(InputTransferMethod.BOUNDED_INLINE_DATA, InputTransferMethod.EPHEMERAL_SIGNED_READ),
            Set.of(OutputDeliveryMethod.BOUNDED_INLINE_DATA, OutputDeliveryMethod.EPHEMERAL_PROVIDER_READ),
            128, Duration.ofMinutes(5), true);
        var providerTransfers = Set.of(InputTransferMethod.BOUNDED_INLINE_DATA, InputTransferMethod.EPHEMERAL_SIGNED_READ);

        assertThat(policy.chooseInputTransfer(providerTransfers, 100, Duration.ofMinutes(2)).method()).isEqualTo(InputTransferMethod.BOUNDED_INLINE_DATA);
        var signed = policy.chooseInputTransfer(providerTransfers, 129, Duration.ofMinutes(2));
        assertThat(signed.method()).isEqualTo(InputTransferMethod.EPHEMERAL_SIGNED_READ);
        assertThat(signed.signedReadTtl()).contains(Duration.ofMinutes(2));
        assertThat(signed.opaqueAliasRequired()).isTrue();
        assertThatExceptionOfType(UnsupportedProviderCapabilityException.class)
            .isThrownBy(() -> policy.chooseInputTransfer(Set.of(InputTransferMethod.EPHEMERAL_SIGNED_READ), 100, Duration.ofMinutes(10)));
        assertThatExceptionOfType(UnsupportedProviderCapabilityException.class)
            .isThrownBy(() -> policy.chooseInputTransfer(Set.of(), 100, Duration.ofMinutes(1)));
        assertThat(policy.chooseOutputDelivery(Set.of(OutputDeliveryMethod.EPHEMERAL_PROVIDER_READ, OutputDeliveryMethod.BOUNDED_INLINE_DATA)))
            .isEqualTo(OutputDeliveryMethod.BOUNDED_INLINE_DATA);
        assertThatExceptionOfType(UnsupportedProviderCapabilityException.class)
            .isThrownBy(() -> policy.chooseOutputDelivery(Set.of()));
    }

    @Test
    void submissionKeysAreStablePerAttemptAndDistinctAcrossAttempts() {
        var first = new GenerationAttemptId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        var second = new GenerationAttemptId(UUID.fromString("00000000-0000-0000-0000-000000000002"));

        assertThat(SubmissionKey.forAttempt(first)).isEqualTo(SubmissionKey.forAttempt(first));
        assertThat(SubmissionKey.forAttempt(first)).isNotEqualTo(SubmissionKey.forAttempt(second));
    }

    @Test
    void registryKeepsHistoricalProvidersAndAliasesSeparateFromNewAttemptSelection() {
        VtonProvider oldProvider = provider("old-provider");
        VtonProvider newProvider = provider("new-provider");
        var oldKey = oldProvider.providerKey();
        var newKey = newProvider.providerKey();
        var alias = new ProviderKey("renamed-provider");
        ProviderRegistry registry = new DefaultProviderRegistry(List.of(oldProvider, newProvider), newKey, Map.of(alias, oldKey));

        assertThat(registry.selectedProviderKey()).isEqualTo(newKey);
        assertThat(registry.selectedProvider()).isSameAs(newProvider);
        assertThat(registry.resolve(oldKey)).isSameAs(oldProvider);
        assertThat(registry.resolve(alias)).isSameAs(oldProvider);
        assertThatExceptionOfType(UnknownProviderException.class)
            .isThrownBy(() -> registry.resolve(new ProviderKey("missing")));
    }

    @Test
    void externalJobIdentityIncludesProviderKey() {
        var remoteId = new ProviderJobId("same-id");
        assertThat(new ProviderExecutionId(new ProviderKey("one"), remoteId))
            .isNotEqualTo(new ProviderExecutionId(new ProviderKey("two"), remoteId));
    }

    @Test
    void redeliveryPolicyStopsPollsReplaysOrBlocksWithoutExactlyOnce() {
        var historical = new ProviderExecutionId(new ProviderKey("old-provider"), new ProviderJobId("remote-1"));
        var registry = new DefaultProviderRegistry(
            List.of(provider("old-provider"), provider("new-provider")),
            new ProviderKey("new-provider"),
            Map.of());
        assertThat(registry.selectedProviderKey()).isEqualTo(new ProviderKey("new-provider"));
        assertThat(registry.resolve(historical.providerKey()).providerKey()).isEqualTo(historical.providerKey());

        var submits = new AtomicInteger();
        VtonProvider observing = new ValidatingVtonProvider() {
            @Override public ProviderKey providerKey() { return historical.providerKey(); }
            @Override public ProviderCapabilities capabilities() {
                return VtonProviderContractsTest.capabilities(Map.of(STANDARD, new ModeCapabilities(Set.of(CLOTHING), 1, false, InputSizeLimit.unknown())), SubmissionReplaySafety.UNSAFE);
            }
            @Override protected GenerationSubmission submitValid(VtonRequest request, SubmissionKey submissionKey) {
                submits.incrementAndGet();
                return new GenerationSubmission.Accepted(historical.providerJobId(), Optional.empty());
            }
            @Override public StatusQueryResult getStatus(ProviderJobId providerJobId) {
                return new StatusQueryResult.Observed(new GenerationStatus.Running());
            }
            @Override public ProviderMediaSource openOutput(ProviderOutputRef outputRef) { throw new UnsupportedOperationException(); }
        };

        assertThat(AttemptRedeliveryPolicy.decide(true, Optional.of(historical), true, SubmissionReplaySafety.SAME_KEY_SAFE))
            .isEqualTo(AttemptRedeliveryPolicy.Decision.STOP);
        assertThat(AttemptRedeliveryPolicy.decide(true, Optional.empty(), false, SubmissionReplaySafety.UNSAFE))
            .isEqualTo(AttemptRedeliveryPolicy.Decision.STOP);

        assertThat(AttemptRedeliveryPolicy.decide(false, Optional.of(historical), true, SubmissionReplaySafety.SAME_KEY_SAFE))
            .isEqualTo(AttemptRedeliveryPolicy.Decision.POLL);
        assertThat(observing.getStatus(historical.providerJobId())).isInstanceOf(StatusQueryResult.Observed.class);
        assertThat(submits).hasValue(0);

        assertThat(AttemptRedeliveryPolicy.decide(false, Optional.empty(), true, SubmissionReplaySafety.UNSAFE))
            .isEqualTo(AttemptRedeliveryPolicy.Decision.BLOCK_UNSAFE_REPLAY);
        assertThat(AttemptRedeliveryPolicy.decide(false, Optional.empty(), true, SubmissionReplaySafety.SAME_KEY_SAFE))
            .isEqualTo(AttemptRedeliveryPolicy.Decision.REPLAY_SAME_KEY);
        assertThat(AttemptRedeliveryPolicy.decide(false, Optional.empty(), false, SubmissionReplaySafety.UNSAFE))
            .isEqualTo(AttemptRedeliveryPolicy.Decision.SUBMIT);

        assertThat(AttemptRedeliveryPolicy.Decision.values())
            .containsExactlyInAnyOrder(
                AttemptRedeliveryPolicy.Decision.SUBMIT,
                AttemptRedeliveryPolicy.Decision.REPLAY_SAME_KEY,
                AttemptRedeliveryPolicy.Decision.POLL,
                AttemptRedeliveryPolicy.Decision.STOP,
                AttemptRedeliveryPolicy.Decision.BLOCK_UNSAFE_REPLAY);
        assertThat(AttemptRedeliveryPolicy.Decision.values()).extracting(Enum::name).doesNotContain("EXACTLY_ONCE");

        assertThatIllegalArgumentException().isThrownBy(
            () -> AttemptRedeliveryPolicy.decide(false, null, false, SubmissionReplaySafety.UNSAFE));
        assertThatIllegalArgumentException().isThrownBy(
            () -> AttemptRedeliveryPolicy.decide(false, Optional.empty(), false, null));

        var reorderedWakeups = List.of(
            AttemptRedeliveryPolicy.decide(false, Optional.empty(), false, SubmissionReplaySafety.UNSAFE),
            AttemptRedeliveryPolicy.decide(false, Optional.of(historical), false, SubmissionReplaySafety.UNSAFE),
            AttemptRedeliveryPolicy.decide(true, Optional.of(historical), false, SubmissionReplaySafety.UNSAFE),
            AttemptRedeliveryPolicy.decide(false, Optional.of(historical), false, SubmissionReplaySafety.UNSAFE));
        assertThat(reorderedWakeups).containsExactly(
            AttemptRedeliveryPolicy.Decision.SUBMIT,
            AttemptRedeliveryPolicy.Decision.POLL,
            AttemptRedeliveryPolicy.Decision.STOP,
            AttemptRedeliveryPolicy.Decision.POLL);
        assertThat(submits).hasValue(0);
    }

    @Test
    void requestShapeRejectsNullsAndExcludesPrivacyUserModelAndLocators() {
        assertThatIllegalArgumentException().isThrownBy(() -> new VtonRequest(null, List.of(PRODUCT), Optional.empty(), STANDARD));
        assertThatIllegalArgumentException().isThrownBy(() -> new VtonRequest(AVATAR, null, Optional.empty(), STANDARD));

        assertThat(componentNames(VtonRequest.class)).containsExactly("avatar", "productAssets", "instructions", "mode");
        assertThat(componentNames(VtonRequest.class)).doesNotContain("privacyMode", "userId", "tenantId", "model", "url", "base64", "options");
        assertThat(componentTypes(VtonRequest.class)).containsExactly("PrivateMediaRef", "List", "Optional", "VtonMode");
        assertThat(componentNames(GenerationStatus.Succeeded.class)).doesNotContain("model");
        assertThat(componentNames(ProviderCapabilities.class)).doesNotContain("model");

        var request = new VtonRequest(AVATAR, List.of(PRODUCT), Optional.empty(), STANDARD);
        assertThat(request.avatar()).isInstanceOf(PrivateMediaRef.class);
        assertThat(request.toString().toLowerCase(java.util.Locale.ROOT))
            .doesNotContain("http://", "https://", "data:", "x-amz", "base64");

        var minted = new AtomicBoolean();
        var policy = new PrivacyTransferPolicy(
            Set.of(InputTransferMethod.BOUNDED_INLINE_DATA),
            Set.of(OutputDeliveryMethod.EPHEMERAL_PROVIDER_READ),
            128, Duration.ofMinutes(5), true);
        VtonProvider minting = new ValidatingVtonProvider() {
            @Override public ProviderKey providerKey() { return new ProviderKey("fake"); }
            @Override public ProviderCapabilities capabilities() {
                return VtonProviderContractsTest.capabilities(Map.of(STANDARD, new ModeCapabilities(Set.of(CLOTHING), 1, false, InputSizeLimit.unknown())), SubmissionReplaySafety.UNSAFE);
            }
            @Override protected GenerationSubmission submitValid(VtonRequest submitted, SubmissionKey key) {
                policy.chooseInputTransfer(Set.of(InputTransferMethod.BOUNDED_INLINE_DATA), 64, Duration.ofMinutes(1));
                minted.set(true);
                return new GenerationSubmission.Accepted(new ProviderJobId("remote"), Optional.empty());
            }
            @Override public StatusQueryResult getStatus(ProviderJobId providerJobId) { return new StatusQueryResult.Observed(new GenerationStatus.Queued()); }
            @Override public ProviderMediaSource openOutput(ProviderOutputRef outputRef) { throw new UnsupportedOperationException(); }
        };
        minting.submit(request, new SubmissionKey(UUID.randomUUID()));
        assertThat(minted).isTrue();
    }

    @Test
    void submissionKeyIsDistinctFromJobClientRequestId() {
        var attemptId = new GenerationAttemptId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        var clientRequestId = new ClientRequestId("job-client-request");
        var submissionKey = SubmissionKey.forAttempt(attemptId);

        assertThat(submissionKey.value().toString()).isNotEqualTo(clientRequestId.value());
        assertThat(submissionKey.value()).isNotEqualTo(UUID.nameUUIDFromBytes(clientRequestId.value().getBytes(StandardCharsets.UTF_8)));
        assertThat(submissionKey).isNotEqualTo(new SubmissionKey(UUID.nameUUIDFromBytes(("clientRequestId:" + clientRequestId.value()).getBytes(StandardCharsets.UTF_8))));
        assertThat(SubmissionKey.class).isNotEqualTo(ClientRequestId.class);
    }

    @Test
    void retryabilityDoesNotImplySameKeyReplay() {
        var retryable = new ProviderFailure(ProviderFailureCategory.TIMEOUT_NETWORK, true, "REQUEST_TIMEOUT");
        assertThat(retryable.retryable()).isTrue();
        assertThat(retryable.category()).isEqualTo(ProviderFailureCategory.TIMEOUT_NETWORK);
        assertThat(AttemptRedeliveryPolicy.decide(false, Optional.empty(), true, SubmissionReplaySafety.UNSAFE))
            .isEqualTo(AttemptRedeliveryPolicy.Decision.BLOCK_UNSAFE_REPLAY)
            .isNotEqualTo(AttemptRedeliveryPolicy.Decision.REPLAY_SAME_KEY);
    }

    @Test
    void pollAndReplaySchedulesAreEncodedOnThePolicyType() {
        assertThat(AttemptRedeliveryPolicy.FIRST_POLL_DELAY).isEqualTo(Duration.ofSeconds(3));
        assertThat(AttemptRedeliveryPolicy.QUEUED_RUNNING_POLL_DELAYS).containsExactly(
            Duration.ofSeconds(3),
            Duration.ofSeconds(5),
            Duration.ofSeconds(10),
            Duration.ofSeconds(15),
            Duration.ofSeconds(30),
            Duration.ofSeconds(60));
        assertThat(AttemptRedeliveryPolicy.QUEUED_RUNNING_POLL_CAP).isEqualTo(Duration.ofSeconds(60));
        assertThat(AttemptRedeliveryPolicy.queuedOrRunningPollDelay(0)).isEqualTo(Duration.ofSeconds(3));
        assertThat(AttemptRedeliveryPolicy.queuedOrRunningPollDelay(5)).isEqualTo(Duration.ofSeconds(60));
        assertThat(AttemptRedeliveryPolicy.queuedOrRunningPollDelay(6)).isEqualTo(Duration.ofSeconds(60));

        assertThat(AttemptRedeliveryPolicy.UNAVAILABLE_POLL_DELAYS).containsExactly(
            Duration.ofSeconds(10),
            Duration.ofSeconds(20),
            Duration.ofSeconds(40),
            Duration.ofSeconds(60));
        assertThat(AttemptRedeliveryPolicy.unavailablePollDelay(0)).isEqualTo(Duration.ofSeconds(10));
        assertThat(AttemptRedeliveryPolicy.unavailablePollDelay(3)).isEqualTo(Duration.ofSeconds(60));
        assertThat(AttemptRedeliveryPolicy.unavailablePollDelay(4)).isEqualTo(Duration.ofSeconds(60));

        assertThat(AttemptRedeliveryPolicy.REPLAY_SAFE_TRANSMISSION_DELAYS).containsExactly(
            Duration.ofSeconds(5),
            Duration.ofSeconds(30),
            Duration.ofSeconds(120));
        assertThat(AttemptRedeliveryPolicy.MAX_REPLAY_SAFE_TRANSMISSIONS).isEqualTo(3);
        assertThat(AttemptRedeliveryPolicy.replaySafeRetransmissionDelay(0)).isEqualTo(Duration.ofSeconds(5));
        assertThat(AttemptRedeliveryPolicy.replaySafeRetransmissionDelay(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(AttemptRedeliveryPolicy.replaySafeRetransmissionDelay(2)).isEqualTo(Duration.ofSeconds(120));
        assertThat(AttemptRedeliveryPolicy.replaySafeRetransmissionAllowed(2)).isTrue();
        assertThat(AttemptRedeliveryPolicy.replaySafeRetransmissionAllowed(3)).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> AttemptRedeliveryPolicy.replaySafeRetransmissionDelay(3));

        assertThat(AttemptRedeliveryPolicy.UNSAFE_CALLBACK_RECOVERY_GRACE).isEqualTo(Duration.ofMinutes(5));
        assertThat(AttemptRedeliveryPolicy.PROVIDER_EXECUTION_DEADLINE).isEqualTo(Duration.ofMinutes(15));
        assertThat(AttemptRedeliveryPolicy.JOB_ORCHESTRATION_DEADLINE).isEqualTo(Duration.ofMinutes(30));
        assertThat(AttemptRedeliveryPolicy.POLL_JITTER_FRACTION).isEqualTo(0.20);
    }

    @Test
    void replaySafeAmbiguityRetransmitsSameKeyUntilBudgetThenUnsafeNeverPostsAgain() {
        var key = new SubmissionKey(UUID.fromString("00000000-0000-0000-0000-0000000000aa"));
        assertThat(AttemptRedeliveryPolicy.decide(false, Optional.empty(), true, SubmissionReplaySafety.SAME_KEY_SAFE))
            .isEqualTo(AttemptRedeliveryPolicy.Decision.REPLAY_SAME_KEY);
        assertThat(key).isEqualTo(new SubmissionKey(UUID.fromString("00000000-0000-0000-0000-0000000000aa")));
        assertThat(AttemptRedeliveryPolicy.replaySafeRetransmissionAllowed(0)).isTrue();
        assertThat(AttemptRedeliveryPolicy.replaySafeRetransmissionAllowed(3)).isFalse();

        assertThat(AttemptRedeliveryPolicy.decide(false, Optional.empty(), true, SubmissionReplaySafety.UNSAFE))
            .isEqualTo(AttemptRedeliveryPolicy.Decision.BLOCK_UNSAFE_REPLAY);
        assertThat(AttemptRedeliveryPolicy.failUnsafeAmbiguityPromptly(false, Duration.ZERO)).isTrue();
        assertThat(AttemptRedeliveryPolicy.failUnsafeAmbiguityPromptly(true, Duration.ofMinutes(4).plusSeconds(59))).isFalse();
        assertThat(AttemptRedeliveryPolicy.failUnsafeAmbiguityPromptly(true, Duration.ofMinutes(5))).isTrue();
        assertThat(AttemptRedeliveryPolicy.failUnsafeAmbiguityPromptly(true, Duration.ofMinutes(6))).isTrue();
    }

    @Test
    void submissionAndStatusContractsStayProviderNeutralWithoutFreeText() {
        assertThat(GenerationSubmission.class.getPermittedSubclasses())
            .extracting(Class::getSimpleName)
            .containsExactlyInAnyOrder("Accepted", "Rejected", "Indeterminate");
        assertThat(GenerationStatus.class.getPermittedSubclasses())
            .extracting(Class::getSimpleName)
            .containsExactlyInAnyOrder("Queued", "Running", "Succeeded", "Failed");
        assertThat(StatusQueryResult.class.getPermittedSubclasses())
            .extracting(Class::getSimpleName)
            .containsExactlyInAnyOrder("Observed", "Unavailable");
        assertThat(componentNames(GenerationSubmission.Accepted.class)).containsExactly("providerJobId", "usage");
        assertThat(componentNames(GenerationSubmission.Rejected.class)).containsExactly("failure");
        assertThat(componentNames(GenerationSubmission.Indeterminate.class)).containsExactly("failure");
        assertThat(componentNames(StatusQueryResult.Unavailable.class)).containsExactly("failure");
        assertThat(componentNames(GenerationStatus.Failed.class)).containsExactly("failure", "usage");
        assertThat(componentNames(GenerationSubmission.Rejected.class)).doesNotContain("message", "error", "body", "text");
        assertThat(componentNames(GenerationStatus.Failed.class)).doesNotContain("message", "error", "body", "text");
        assertThat(new StatusQueryResult.Unavailable(new ProviderFailure(ProviderFailureCategory.TIMEOUT_NETWORK, true, "STATUS_UNAVAILABLE")))
            .isNotInstanceOf(GenerationStatus.class);
    }


    @Test
    void statusBoundariesRejectBlankTreatNotFoundAsUnavailableAndKeepUnknownProtocol() {
        var submits = new AtomicInteger();
        var canonicalLifecycle = new AtomicInteger(-1);
        VtonProvider observing = new ValidatingVtonProvider() {
            @Override public ProviderKey providerKey() { return new ProviderKey("fake"); }
            @Override public ProviderCapabilities capabilities() {
                return VtonProviderContractsTest.capabilities(Map.of(STANDARD, new ModeCapabilities(Set.of(CLOTHING), 1, false, InputSizeLimit.unknown())), SubmissionReplaySafety.UNSAFE);
            }
            @Override protected GenerationSubmission submitValid(VtonRequest request, SubmissionKey key) {
                submits.incrementAndGet();
                return new GenerationSubmission.Accepted(new ProviderJobId("remote"), Optional.empty());
            }
            @Override public StatusQueryResult getStatus(ProviderJobId providerJobId) {
                if ("missing-remote".equals(providerJobId.value())) {
                    return new StatusQueryResult.Unavailable(new ProviderFailure(ProviderFailureCategory.REMOTE_EXECUTION_NOT_FOUND, false, "REMOTE_NOT_FOUND"));
                }
                if ("unknown-lifecycle".equals(providerJobId.value())) {
                    return new StatusQueryResult.Unavailable(new ProviderFailure(ProviderFailureCategory.PROTOCOL_VIOLATION, false, "UNKNOWN_LIFECYCLE"));
                }
                return new StatusQueryResult.Observed(new GenerationStatus.Running());
            }
            @Override public ProviderMediaSource openOutput(ProviderOutputRef outputRef) { throw new UnsupportedOperationException(); }
        };

        assertThatIllegalArgumentException().isThrownBy(() -> new ProviderJobId(""));
        assertThatIllegalArgumentException().isThrownBy(() -> new ProviderJobId("   "));
        assertThatIllegalArgumentException().isThrownBy(() -> new ProviderJobId(null));

        var notFound = observing.getStatus(new ProviderJobId("missing-remote"));
        assertThat(notFound).isInstanceOf(StatusQueryResult.Unavailable.class);
        assertThat(notFound).isNotInstanceOf(StatusQueryResult.Observed.class);
        assertThat(((StatusQueryResult.Unavailable) notFound).failure().category())
            .isEqualTo(ProviderFailureCategory.REMOTE_EXECUTION_NOT_FOUND);

        var unknown = observing.getStatus(new ProviderJobId("unknown-lifecycle"));
        assertThat(unknown).isInstanceOf(StatusQueryResult.Unavailable.class);
        assertThat(((StatusQueryResult.Unavailable) unknown).failure().category())
            .isEqualTo(ProviderFailureCategory.PROTOCOL_VIOLATION);
        assertThat(canonicalLifecycle).hasValue(-1);

        var first = observing.getStatus(new ProviderJobId("running"));
        var second = observing.getStatus(new ProviderJobId("running"));
        assertThat(first).isEqualTo(second).isInstanceOf(StatusQueryResult.Observed.class);
        var concurrent = CompletableFuture.allOf(
            CompletableFuture.runAsync(() -> observing.getStatus(new ProviderJobId("running"))),
            CompletableFuture.runAsync(() -> observing.getStatus(new ProviderJobId("running")))).join();
        assertThat(concurrent).isNull();
        assertThat(submits).hasValue(0);
    }

    @Test
    void openOutputStreamsIntoPrivateTempWithoutLocatorOrAwsContracts() throws Exception {
        var outputRef = new ProviderOutputRef("result-1");
        VtonProvider streaming = new ValidatingVtonProvider() {
            @Override public ProviderKey providerKey() { return new ProviderKey("fake"); }
            @Override public ProviderCapabilities capabilities() {
                return VtonProviderContractsTest.capabilities(Map.of(STANDARD, new ModeCapabilities(Set.of(CLOTHING), 1, false, InputSizeLimit.unknown())), SubmissionReplaySafety.UNSAFE);
            }
            @Override protected GenerationSubmission submitValid(VtonRequest request, SubmissionKey key) {
                return new GenerationSubmission.Accepted(new ProviderJobId("remote"), Optional.empty());
            }
            @Override public StatusQueryResult getStatus(ProviderJobId providerJobId) {
                return new StatusQueryResult.Observed(new GenerationStatus.Succeeded(List.of(outputRef), Optional.empty()));
            }
            @Override public ProviderMediaSource openOutput(ProviderOutputRef ref) {
                assertThat(ref).isEqualTo(outputRef);
                return ProviderMediaSource.of(new ByteArrayInputStream(new byte[] {1, 2, 3}), OptionalLong.of(3), 10, "image/png");
            }
        };
        GenerationOutputStore store = source -> {
            try (source) {
                long size = source.stream().readAllBytes().length;
                return new PrivateMediaRef(new StorageKey("private/temp/result-1"), OptionalLong.of(size));
            }
        };

        PrivateMediaRef stored;
        try (ProviderMediaSource source = streaming.openOutput(outputRef)) {
            stored = store.writePrivateTemp(source);
        }
        assertThat(stored).isInstanceOf(PrivateMediaRef.class);
        assertThat(stored.storageKey().value()).isEqualTo("private/temp/result-1");
        assertThat(stored.toString().toLowerCase(java.util.Locale.ROOT))
            .doesNotContain("http://", "https://", "data:", "base64", "amazonaws");

        var write = GenerationOutputStore.class.getMethod("writePrivateTemp", ProviderMediaSource.class);
        assertThat(write.getParameterTypes()).containsExactly(ProviderMediaSource.class).doesNotContain(byte[].class);
        assertThat(write.getReturnType()).isEqualTo(PrivateMediaRef.class).isNotEqualTo(byte[].class);
        assertNoCloudSdkTypes(GenerationOutputStore.class);
        assertNoCloudSdkTypes(ProviderMediaSource.class);
        assertNoCloudSdkTypes(VtonProvider.class);
        assertNoCloudSdkTypes(PrivateMediaRef.class);

        assertThatIllegalArgumentException().isThrownBy(() -> look(QualityDecision.FAIL, stored.storageKey()));
        assertThatIllegalArgumentException().isThrownBy(() -> look(QualityDecision.RETRY, stored.storageKey()));
    }

    @Test
    void capabilitiesExcludeModelRateConcurrencyAndQuota() {
        assertThat(componentNames(ProviderCapabilities.class))
            .containsExactly("modes", "inputTransfers", "outputDeliveries", "submissionReplaySafety")
            .doesNotContain("model", "rate", "concurrency", "quota");
        assertThat(componentNames(ModeCapabilities.class))
            .containsExactly("supportedCategories", "maxProductAssets", "instructionsSupported", "inputSizeLimit")
            .doesNotContain("model", "rate", "concurrency", "quota");
        provider("fake").capabilities();
    }

    @Test
    void privacyTransfersHaveNoPermanentPublicLocatorAndRejectTelemetryLeakage() {
        assertThat(InputTransferMethod.values()).containsExactly(
            InputTransferMethod.BOUNDED_INLINE_DATA, InputTransferMethod.EPHEMERAL_SIGNED_READ);
        assertThat(OutputDeliveryMethod.values()).containsExactly(
            OutputDeliveryMethod.BOUNDED_INLINE_DATA, OutputDeliveryMethod.EPHEMERAL_PROVIDER_READ);
        assertThat(InputTransferMethod.values()).extracting(Enum::name)
            .doesNotContain("PERMANENT_PUBLIC", "PUBLIC_URL", "PERMANENT_URL");

        assertThatIllegalArgumentException().isThrownBy(() -> OpaqueValue.requireSafe("https://cdn.example/out", "telemetry"));
        assertThatIllegalArgumentException().isThrownBy(() -> OpaqueValue.requireSafe("data:image/png;base64,abc", "telemetry"));
        assertThatIllegalArgumentException().isThrownBy(() -> OpaqueValue.requireSafe("https://s3.example/x?X-Amz-Signature=abc", "telemetry"));
        assertThatIllegalArgumentException().isThrownBy(() -> OpaqueValue.requireSafe("/9j/4AAQ", "telemetry"));
    }

    @Test
    void historicalProviderIdentityIsImmutableAndFallbackIsANewAttempt() {
        var jobId = new GenerationJobId(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
        var firstId = new GenerationAttemptId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
        var fallbackId = new GenerationAttemptId(UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"));
        var started = Instant.parse("2026-08-22T00:00:00Z");
        var first = new GenerationAttempt(firstId, jobId, "old-provider", null, "remote-1", 1, started, null, null, null, null, null, null, null, null, null, false, null, null);
        var registry = new DefaultProviderRegistry(
            List.of(provider("old-provider"), provider("new-provider")),
            new ProviderKey("new-provider"),
            Map.of());

        assertThat(first.provider()).isEqualTo("old-provider");
        assertThat(registry.selectedProviderKey().value()).isEqualTo("new-provider");
        assertThat(first.provider()).isNotEqualTo(registry.selectedProviderKey().value());
        assertThat(GenerationAttempt.class.getDeclaredMethods())
            .extracting(Method::getName)
            .doesNotContain("setProvider", "withProvider");

        var fallback = new GenerationAttempt(fallbackId, jobId, "new-provider", null, null, 2, started, null, null, null, null, null, null, null, null, null, false, null, null);
        assertThat(fallback.id()).isNotEqualTo(first.id());
        assertThat(fallback.attemptNumber()).isEqualTo(2);
        assertThat(first.attemptNumber()).isEqualTo(1);
        assertThat(first.provider()).isEqualTo("old-provider");
        assertThat(fallback.provider()).isEqualTo("new-provider");
    }

    @Test
    void providerPortHasNoCancelDeleteOrNotificationResponsibility() {
        assertThat(VtonProvider.class.getDeclaredMethods())
            .extracting(Method::getName)
            .containsExactlyInAnyOrder("providerKey", "capabilities", "submit", "submitTurn", "getStatus", "openOutput")
            .doesNotContain("cancel", "delete", "notify", "send");
        for (Method method : VtonProvider.class.getDeclaredMethods()) {
            assertThat(method.getReturnType().getSimpleName()).doesNotContain("Notification", "Sender");
            assertThat(Arrays.stream(method.getParameterTypes()).map(Class::getSimpleName))
                .noneMatch(name -> name.contains("Notification") || name.contains("Sender"));
        }

        var notified = new AtomicBoolean();
        var outputRef = new ProviderOutputRef("result-1");
        VtonProvider technicalOnly = new ValidatingVtonProvider() {
            @Override public ProviderKey providerKey() { return new ProviderKey("fake"); }
            @Override public ProviderCapabilities capabilities() {
                return VtonProviderContractsTest.capabilities(Map.of(STANDARD, new ModeCapabilities(Set.of(CLOTHING), 1, false, InputSizeLimit.unknown())), SubmissionReplaySafety.UNSAFE);
            }
            @Override protected GenerationSubmission submitValid(VtonRequest request, SubmissionKey key) {
                return new GenerationSubmission.Accepted(new ProviderJobId("remote"), Optional.empty());
            }
            @Override public StatusQueryResult getStatus(ProviderJobId providerJobId) {
                return new StatusQueryResult.Observed(new GenerationStatus.Succeeded(List.of(outputRef), Optional.empty()));
            }
            @Override public ProviderMediaSource openOutput(ProviderOutputRef ref) { throw new UnsupportedOperationException(); }
        };
        technicalOnly.submit(request(AVATAR, List.of(PRODUCT), Optional.empty(), STANDARD), new SubmissionKey(UUID.randomUUID()));
        var status = technicalOnly.getStatus(new ProviderJobId("remote"));
        assertThat(((StatusQueryResult.Observed) status).status().lifecycle()).isEqualTo(GenerationLifecycle.SUCCEEDED);
        assertThat(notified).isFalse();
        assertThat(qualityGateAllowsReadyNotification(((StatusQueryResult.Observed) status).status(), Optional.empty())).isFalse();
    }

    @Test
    void qualityDeletionAndNotificationBoundariesStayApplicationOwned() {
        var tempKey = new StorageKey("private/temp/result-1");
        assertThatIllegalArgumentException().isThrownBy(() -> look(QualityDecision.FAIL, tempKey));
        assertThatIllegalArgumentException().isThrownBy(() -> look(QualityDecision.RETRY, tempKey));
        assertThat(look(QualityDecision.PASS, tempKey).qualityStatus()).isEqualTo(QualityDecision.PASS);

        var outputRef = new ProviderOutputRef("result-1");
        assertThatIllegalArgumentException().isThrownBy(() -> notification(Map.of("image", "https://provider.example/" + outputRef.value())));
        assertThatIllegalArgumentException().isThrownBy(() -> rejectOutputRefAsMediaUrl(outputRef));
        assertThatIllegalArgumentException().isThrownBy(() -> notification(Map.of("image_url", "users/u/looks/l.jpg")));
        assertThatIllegalArgumentException().isThrownBy(() -> notification(Map.of("base64_image", "/9j/4AAQ")));
        assertThatIllegalArgumentException().isThrownBy(() -> rejectUnsafeNotificationPayload(Map.of("provider_model", "tryon-v1")));
        assertThatIllegalArgumentException().isThrownBy(() -> rejectUnsafeNotificationPayload(Map.of("error_body", "{\"error\":1}")));
        assertThatIllegalArgumentException().isThrownBy(() -> rejectUnsafeNotificationPayload(Map.of("request_body", "{\"model\":\"x\"}")));

        record DeletedAccountGate(boolean deleted) {
            PrivateMediaRef copy(GenerationOutputStore store, ProviderMediaSource source) throws Exception {
                if (deleted) throw new IllegalStateException("deleted account blocks output copy");
                return store.writePrivateTemp(source);
            }
            Look expose(QualityDecision quality, StorageKey key) {
                if (deleted) throw new IllegalStateException("deleted account blocks exposure");
                return look(quality, key);
            }
            Notification notifyUser() {
                if (deleted) throw new IllegalStateException("deleted account blocks notification");
                return notification(Map.of("lookId", "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
            }
        }
        var deleted = new DeletedAccountGate(true);
        assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> deleted.copy(
            source -> new PrivateMediaRef(tempKey, OptionalLong.of(3)),
            ProviderMediaSource.of(new ByteArrayInputStream(new byte[] {1}), OptionalLong.of(1), 10, "image/png")));
        assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> deleted.expose(QualityDecision.PASS, tempKey));
        assertThatExceptionOfType(IllegalStateException.class).isThrownBy(deleted::notifyUser);
    }



    private static VtonProvider provider(String key) {
        return new ValidatingVtonProvider() {
            @Override public ProviderKey providerKey() { return new ProviderKey(key); }
            @Override public ProviderCapabilities capabilities() { return VtonProviderContractsTest.capabilities(Map.of(STANDARD, new ModeCapabilities(Set.of(CLOTHING), 1, false, InputSizeLimit.unknown())), SubmissionReplaySafety.UNSAFE); }
            @Override protected GenerationSubmission submitValid(VtonRequest request, SubmissionKey submissionKey) { return new GenerationSubmission.Accepted(new ProviderJobId("remote"), Optional.empty()); }
            @Override public StatusQueryResult getStatus(ProviderJobId providerJobId) { return new StatusQueryResult.Observed(new GenerationStatus.Queued()); }
            @Override public ProviderMediaSource openOutput(ProviderOutputRef outputRef) { throw new UnsupportedOperationException(); }
        };
    }

    private static ProviderCapabilities capabilities(Map<VtonMode, ModeCapabilities> modes, SubmissionReplaySafety replay) {
        return new ProviderCapabilities(modes, Set.of(InputTransferMethod.BOUNDED_INLINE_DATA), Set.of(OutputDeliveryMethod.EPHEMERAL_PROVIDER_READ), replay);
    }

    private static VtonRequest request(PrivateMediaRef avatar, List<VtonProductAsset> products, Optional<String> instructions, VtonMode mode) {
        return new VtonRequest(avatar, products, instructions, mode);
    }

    private static void assertFailure(VtonRequest request, ProviderCapabilities capabilities, ProviderFailureCategory category) {
        assertThat(VtonRequestValidator.validate(request, capabilities)).get().extracting(ProviderFailure::category).isEqualTo(category);
    }

    private static PrivateMediaRef media(String key) {
        return media(key, -1);
    }

    private static PrivateMediaRef media(String key, long size) {
        return new PrivateMediaRef(new StorageKey(key), size < 0 ? OptionalLong.empty() : OptionalLong.of(size));
    }

    private static List<String> componentNames(Class<?> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    private static List<String> componentTypes(Class<?> type) {
        return Arrays.stream(type.getRecordComponents()).map(component -> component.getType().getSimpleName()).toList();
    }

    private static void assertNoCloudSdkTypes(Class<?> type) {
        Stream.concat(
                Stream.of(type),
                Stream.concat(
                    Arrays.stream(type.getDeclaredMethods()).map(Method::getReturnType),
                    Arrays.stream(type.getDeclaredMethods()).flatMap(method -> Arrays.stream(method.getParameterTypes()))))
            .map(Class::getName)
            .forEach(name -> assertThat(name)
                .doesNotContain("amazonaws")
                .doesNotContain("software.amazon"));
    }

    private static Look look(QualityDecision quality, StorageKey storageKey) {
        return new Look(
            new LookId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")),
            new UserId(UUID.fromString("11111111-1111-1111-1111-111111111111")),
            new AvatarProfileId(UUID.fromString("22222222-2222-2222-2222-222222222222")),
            new GenerationJobId(UUID.fromString("33333333-3333-3333-3333-333333333333")),
            new StudioSessionId(UUID.fromString("44444444-4444-4444-4444-444444444444")),
            storageKey,
            "{\"items\":1}",
            "old-provider",
            "tryon-v1",
            quality,
            Instant.parse("2026-08-22T00:00:00Z"),
            List.of());
    }

    private static Notification notification(Map<String, String> payload) {
        return new Notification(
            new NotificationId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")),
            new UserId(UUID.fromString("11111111-1111-1111-1111-111111111111")),
            "LOOK_READY",
            payload,
            null,
            Instant.parse("2026-08-22T00:00:00Z"));
    }

    private static void rejectUnsafeNotificationPayload(Map<String, String> payload) {
        for (String key : payload.keySet()) {
            if (Set.of("provider_model", "error_body", "request_body").contains(key)) {
                throw new IllegalArgumentException("payload must not contain " + key);
            }
        }
    }

    private static void rejectOutputRefAsMediaUrl(ProviderOutputRef outputRef) {
        throw new IllegalArgumentException("provider output reference must not be serialized as a media URL: " + outputRef.value());
    }

    private static boolean qualityGateAllowsReadyNotification(GenerationStatus status, Optional<QualityDecision> quality) {
        return status.lifecycle() == GenerationLifecycle.SUCCEEDED && quality.orElse(null) == QualityDecision.PASS;
    }

}
