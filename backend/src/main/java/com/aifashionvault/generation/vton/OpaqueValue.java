package com.aifashionvault.generation.vton;

import java.util.Locale;

final class OpaqueValue {
    private OpaqueValue() { }

    static String requireSafe(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
        String stripped = value.strip();
        if (stripped.length() > 512 || stripped.indexOf('\n') >= 0 || stripped.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        String compact = stripped.replace(" ", "").replace("\t", "");
        String lower = compact.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("data:")
            || lower.contains("x-amz-signature") || lower.contains("x-amz-algorithm")
            || lower.startsWith("/9j/") || lower.startsWith("ivborw0kggo")
            || lower.startsWith("r0lgod") || lower.startsWith("uklgr")) {
            throw new IllegalArgumentException(label + " must be opaque and must not contain media or a locator");
        }
        return stripped;
    }
}
