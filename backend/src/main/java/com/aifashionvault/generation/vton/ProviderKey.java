package com.aifashionvault.generation.vton;

import java.util.Locale;

public record ProviderKey(String value) {
    public ProviderKey {
        if (value == null) throw new IllegalArgumentException("provider key is required");
        value = value.strip().toLowerCase(Locale.ROOT);
        if (!value.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("provider key must be a lowercase stable identifier");
        }
    }
}
