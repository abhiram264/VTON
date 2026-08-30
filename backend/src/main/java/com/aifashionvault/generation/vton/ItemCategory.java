package com.aifashionvault.generation.vton;

import java.util.Locale;

public record ItemCategory(String value) {
    public ItemCategory {
        if (value == null) throw new IllegalArgumentException("item category is required");
        value = value.strip().toLowerCase(Locale.ROOT);
        if (!value.matches("[a-z0-9][a-z0-9._-]{0,63}")) throw new IllegalArgumentException("item category is invalid");
    }
}
