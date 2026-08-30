package com.aifashionvault.generation.vton;

import java.util.Locale;

public record VtonMode(String value) {
    public VtonMode {
        if (value == null) throw new IllegalArgumentException("VTON mode is required");
        value = value.strip().toLowerCase(Locale.ROOT);
        if (!value.matches("[a-z0-9][a-z0-9._-]{0,63}")) throw new IllegalArgumentException("VTON mode is invalid");
    }
}
