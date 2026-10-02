package com.aifashionvault.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AnalyticsEventTest {
    @Test
    void vocabularyIsExact() {
        assertThat(AnalyticsEventName.values()).hasSize(23);
        assertThat(java.util.Arrays.stream(AnalyticsEventName.values()).map(AnalyticsEventName::wireName))
                .containsExactly("account_created", "avatar_creation_started", "avatar_created", "avatar_rejected",
                        "share_received", "share_resolved", "share_resolution_failed", "vault_item_created",
                        "vault_item_reused", "collection_created", "studio_started", "studio_item_added",
                        "studio_item_removed", "generation_requested", "generation_completed", "generation_failed",
                        "generation_quality_rejected", "look_viewed", "look_saved", "look_shared", "source_link_opened",
                        "deletion_requested", "deletion_completed");
    }

    @Test
    void acceptsEmptyAndAllScalarBoundaries() {
        Map<String, AnalyticsEventPublisher.PropertyValue> properties = new LinkedHashMap<>();
        properties.put("text", new AnalyticsEventPublisher.TextValue("ok"));
        properties.put("long_value", new AnalyticsEventPublisher.LongValue(Long.MIN_VALUE));
        properties.put("double_value", new AnalyticsEventPublisher.DoubleValue(Double.MAX_VALUE));
        properties.put("flag", new AnalyticsEventPublisher.BooleanValue(true));
        var event = new AnalyticsEventPublisher.Event(AnalyticsEventName.ACCOUNT_CREATED, properties);
        assertThat(new AnalyticsEventPublisher.Event(AnalyticsEventName.ACCOUNT_CREATED, Map.of()).properties()).isEmpty();
        assertThat(event.properties()).containsKeys("text", "long_value", "double_value", "flag");
        assertThat(event.properties()).isUnmodifiable();
        properties.put("other", new AnalyticsEventPublisher.LongValue(1));
        assertThat(event.properties()).doesNotContainKey("other");
    }

    @Test
    void rejectsEveryForbiddenKeyTokenInEveryPosition() {
        String[] forbiddenTokens = {"image", "media", "url", "uri", "body", "base64", "bytes", "token", "secret",
                "signed", "signature", "capability", "provider_request"};
        for (String token : forbiddenTokens) {
            for (String key : new String[] {token, token + "_field", "field_" + token, "field_" + token + "_value"}) {
                assertThatIllegalArgumentException().isThrownBy(() -> event(key, "ok"))
                        .withMessage("property key contains a forbidden token");
            }
        }
    }

    @Test
    void rejectsUnsafeTextFingerprintsCaseInsensitively() {
        String[] fingerprints = {"http://", "https://", "data:image", "x-amz-", "signature=", "image_base64", "/9j/",
                "ivborw0kggo"};
        for (String fingerprint : fingerprints) {
            String payload = ("prefix-" + fingerprint + "-suffix").toUpperCase(Locale.ROOT);
            assertThatIllegalArgumentException().isThrownBy(() -> event("good", payload))
                    .withMessageNotContaining(payload);
        }
    }

    @Test
    void rejectsOverlongAndNullPropertyEntries() {
        String overlongKey = "a" + "b".repeat(64);
        assertThatIllegalArgumentException().isThrownBy(() -> event(overlongKey, "ok"))
                .withMessageNotContaining(overlongKey);

        Map<String, AnalyticsEventPublisher.PropertyValue> nullKey = new LinkedHashMap<>();
        nullKey.put(null, new AnalyticsEventPublisher.TextValue("ok"));
        assertThatNullPointerException().isThrownBy(() -> new AnalyticsEventPublisher.Event(
                AnalyticsEventName.ACCOUNT_CREATED, nullKey));

        Map<String, AnalyticsEventPublisher.PropertyValue> nullValue = new LinkedHashMap<>();
        nullValue.put("good", null);
        assertThatNullPointerException().isThrownBy(() -> new AnalyticsEventPublisher.Event(
                AnalyticsEventName.ACCOUNT_CREATED, nullValue));
    }

    @Test
    void rejectsInvalidInputsWithoutEchoingPayload() {
        assertThatNullPointerException().isThrownBy(() -> new AnalyticsEventPublisher.Event(null, Map.of()));
        assertThatNullPointerException().isThrownBy(() -> new AnalyticsEventPublisher.Event(AnalyticsEventName.ACCOUNT_CREATED, null));
        assertThatIllegalArgumentException().isThrownBy(() -> event("A", "ok"));
        assertThatIllegalArgumentException().isThrownBy(() -> event("good", " "));
        assertThatIllegalArgumentException().isThrownBy(() -> event("image_data", "ok"));
        assertThatIllegalArgumentException().isThrownBy(() -> event("good", "https://example.invalid/private"))
                .withMessageNotContaining("example.invalid");
        assertThatIllegalArgumentException().isThrownBy(() -> event("good", "x".repeat(257)));
        assertThatIllegalArgumentException().isThrownBy(() -> new AnalyticsEventPublisher.Event(AnalyticsEventName.ACCOUNT_CREATED,
                Map.of("good", new AnalyticsEventPublisher.DoubleValue(Double.NaN))));
    }

    @Test
    void allowsOpaqueUuidAndBoundaryLengths() {
        String key = "a" + "b".repeat(63);
        String text = "x".repeat(256);
        var event = new AnalyticsEventPublisher.Event(AnalyticsEventName.ACCOUNT_CREATED,
                Map.of(key, new AnalyticsEventPublisher.TextValue(text), "id", new AnalyticsEventPublisher.TextValue(UUID.randomUUID().toString())));
        assertThat(event.properties()).hasSize(2);
    }

    @Test
    void publisherPassesTheSameEventOnce() {
        AtomicReference<AnalyticsEventPublisher.Event> seen = new AtomicReference<>();
        AnalyticsEventPublisher publisher = event -> seen.set(event);
        var event = new AnalyticsEventPublisher.Event(AnalyticsEventName.LOOK_VIEWED, Map.of());
        publisher.publish(event);
        assertThat(seen.get()).isSameAs(event);
    }

    private static AnalyticsEventPublisher.Event event(String key, String value) {
        return new AnalyticsEventPublisher.Event(AnalyticsEventName.ACCOUNT_CREATED,
                Map.of(key, new AnalyticsEventPublisher.TextValue(value)));
    }
}

