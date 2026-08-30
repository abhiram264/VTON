package com.aifashionvault.generation.vton;

import java.math.BigDecimal;
import java.util.Locale;

public record ProviderUsage(BigDecimal creditsUsed, BigDecimal estimatedCost, String currency) {
    public ProviderUsage {
        if (creditsUsed == null || creditsUsed.signum() < 0) throw new IllegalArgumentException("credits used must be non-negative");
        if (estimatedCost == null || estimatedCost.signum() < 0) throw new IllegalArgumentException("estimated cost must be non-negative");
        if (currency == null || !currency.strip().matches("[A-Za-z]{3}")) throw new IllegalArgumentException("currency must be an ISO-style three-letter code");
        currency = currency.strip().toUpperCase(Locale.ROOT);
    }
}
