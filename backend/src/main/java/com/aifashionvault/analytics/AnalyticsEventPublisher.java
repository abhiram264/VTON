package com.aifashionvault.analytics;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@FunctionalInterface
public interface AnalyticsEventPublisher {
    void publish(Event event);

    record Event(AnalyticsEventName name, Map<String, PropertyValue> properties) {
        private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]{0,63}");
        private static final Set<String> FORBIDDEN = Set.of("image", "media", "url", "uri", "body", "base64", "bytes",
                "token", "secret", "signed", "signature", "capability", "provider_request");

        public Event {
            Objects.requireNonNull(name, "name is required");
            Objects.requireNonNull(properties, "properties are required");
            var copy = new java.util.HashMap<String, PropertyValue>();
            properties.forEach((key, value) -> {
                validateKey(key);
                Objects.requireNonNull(value, "property value is required");
                validateValue(value);
                copy.put(key, value);
            });
            properties = Map.copyOf(copy);
        }

        private static void validateKey(String key) {
            Objects.requireNonNull(key, "property key is required");
            if (!KEY.matcher(key).matches()) throw new IllegalArgumentException("property key format is invalid");
            for (String token : FORBIDDEN) {
                if (containsToken(key, token)) throw new IllegalArgumentException("property key contains a forbidden token");
            }
        }

        private static boolean containsToken(String key, String token) {
            return key.equals(token) || key.startsWith(token + "_") || key.endsWith("_" + token)
                    || key.contains("_" + token + "_");
        }

        private static void validateValue(PropertyValue value) {
            if (value instanceof TextValue text) {
                Objects.requireNonNull(text.value(), "text value is required");
                if (text.value().isBlank()) throw new IllegalArgumentException("text value must be nonblank");
                if (text.value().codePoints().count() > 256) throw new IllegalArgumentException("text value exceeds maximum length");
                String lower = text.value().toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("http://") || lower.contains("https://") || lower.contains("data:image")
                        || lower.contains("x-amz-") || lower.contains("signature=") || lower.contains("image_base64")
                        || lower.contains("/9j/") || lower.contains("ivborw0kggo")) {
                    throw new IllegalArgumentException("text value contains an unsafe fingerprint");
                }
            } else if (value instanceof DoubleValue number && !Double.isFinite(number.value())) {
                throw new IllegalArgumentException("double value must be finite");
            }
        }
    }

    sealed interface PropertyValue permits TextValue, LongValue, DoubleValue, BooleanValue {}

    record TextValue(String value) implements PropertyValue {}
    record LongValue(long value) implements PropertyValue {}
    record DoubleValue(double value) implements PropertyValue {}
    record BooleanValue(boolean value) implements PropertyValue {}
}
