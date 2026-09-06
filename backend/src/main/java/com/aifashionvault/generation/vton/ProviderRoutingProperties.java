package com.aifashionvault.generation.vton;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Mutable routing policy properties for the {@code vault.generation.routing} prefix.
 *
 * <p>{@code @ConfigurationProperties} bean: every setter validates eagerly so an invalid
 * configuration value fails at binding time instead of mid-route, and the typed accessors used
 * by the routing engine re-parse defensively. Bean registration is wiring, not this bean's
 * concern.
 */
@ConfigurationProperties(prefix = "vault.generation.routing")
public class ProviderRoutingProperties {

    /** Routing activation mode. */
    public enum RoutingMode { AUTO, OFF, ON }

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;

    private String mode = "auto";
    private String defaultObjective = "quality";
    private long windowDays = 7L;
    private long minSampleCount = 30L;
    private BigDecimal maxFailRate = new BigDecimal("0.50");
    private BigDecimal cheapestTierTolerance = new BigDecimal("1.25");
    private String currency = "USD";
    private Map<String, String> categoryObjectives = new LinkedHashMap<>();
    private String forcedProvider;
    private String policyVersion = "routing-v1";

    public String getMode() { return mode; }

    public void setMode(String mode) {
        if (mode == null || mode.isBlank()) throw new IllegalArgumentException("mode is required");
        RoutingMode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
        this.mode = mode;
    }

    public String getDefaultObjective() { return defaultObjective; }

    public void setDefaultObjective(String defaultObjective) {
        if (defaultObjective == null || defaultObjective.isBlank()) {
            throw new IllegalArgumentException("defaultObjective is required");
        }
        RoutingObjective.valueOf(defaultObjective.trim().toUpperCase(Locale.ROOT));
        this.defaultObjective = defaultObjective;
    }

    public long getWindowDays() { return windowDays; }

    public void setWindowDays(long windowDays) {
        if (windowDays < 1) throw new IllegalArgumentException("windowDays must be at least 1");
        this.windowDays = windowDays;
    }

    public long getMinSampleCount() { return minSampleCount; }

    public void setMinSampleCount(long minSampleCount) {
        if (minSampleCount < 1) {
            throw new IllegalArgumentException("minSampleCount must be at least 1");
        }
        this.minSampleCount = minSampleCount;
    }

    public BigDecimal getMaxFailRate() { return maxFailRate; }

    public void setMaxFailRate(BigDecimal maxFailRate) {
        if (maxFailRate == null || maxFailRate.compareTo(ZERO) < 0 || maxFailRate.compareTo(ONE) > 0) {
            throw new IllegalArgumentException("maxFailRate must be within [0,1]");
        }
        this.maxFailRate = maxFailRate;
    }

    public BigDecimal getCheapestTierTolerance() { return cheapestTierTolerance; }

    public void setCheapestTierTolerance(BigDecimal cheapestTierTolerance) {
        if (cheapestTierTolerance == null || cheapestTierTolerance.compareTo(ONE) < 0) {
            throw new IllegalArgumentException("cheapestTierTolerance must be at least 1");
        }
        this.cheapestTierTolerance = cheapestTierTolerance;
    }

    public String getCurrency() { return currency; }

    public void setCurrency(String currency) {
        if (currency == null || currency.isBlank()) throw new IllegalArgumentException("currency is required");
        String trimmed = currency.trim();
        if (trimmed.length() != 3) {
            throw new IllegalArgumentException("currency must be a 3-letter code");
        }
        this.currency = trimmed.toUpperCase(Locale.ROOT);
    }

    public Map<String, String> getCategoryObjectives() { return categoryObjectives; }

    public void setCategoryObjectives(Map<String, String> categoryObjectives) {
        if (categoryObjectives == null) {
            throw new IllegalArgumentException("categoryObjectives is required");
        }
        Map<String, String> copy = new LinkedHashMap<>();
        categoryObjectives.forEach((category, objective) -> {
            if (category == null || category.isBlank()) {
                throw new IllegalArgumentException("category key is required");
            }
            if (objective == null || objective.isBlank()) {
                throw new IllegalArgumentException("category objective is required");
            }
            RoutingObjective.valueOf(objective.trim().toUpperCase(Locale.ROOT));
            copy.put(new ItemCategory(category).value(), objective);
        });
        this.categoryObjectives = copy;
    }

    public String getForcedProvider() { return forcedProvider; }

    /**
     * Fail-fast canary pin: a non-blank value must match the provider-key pattern
     * {@code [a-z0-9][a-z0-9._-]{0,63}} on the raw value, otherwise binding fails with
     * {@link IllegalArgumentException}. Blank/null means no pin.
     */
    public void setForcedProvider(String forcedProvider) {
        if (forcedProvider != null && !forcedProvider.isBlank()
            && !forcedProvider.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("forcedProvider must be a valid provider key");
        }
        this.forcedProvider = forcedProvider;
    }

    public String getPolicyVersion() { return policyVersion; }

    public void setPolicyVersion(String policyVersion) {
        if (policyVersion == null || policyVersion.isBlank()) {
            throw new IllegalArgumentException("policyVersion is required");
        }
        this.policyVersion = policyVersion;
    }

    /** Typed accessor for the engine: parsed routing mode. */
    public RoutingMode mode() {
        return RoutingMode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
    }

    /** Typed accessor for the engine: parsed default objective. */
    public RoutingObjective defaultObjective() {
        return RoutingObjective.valueOf(defaultObjective.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * Objective for a category: the per-category override when present, else the default
     * objective.
     */
    public RoutingObjective objectiveFor(ItemCategory category) {
        if (category == null) throw new IllegalArgumentException("category is required");
        String override = categoryObjectives.get(category.value());
        if (override == null) return defaultObjective();
        return RoutingObjective.valueOf(override.trim().toUpperCase(Locale.ROOT));
    }

    /** Typed accessor for the engine: the validated forced pin, empty when absent/blank. */
    public Optional<ProviderKey> forcedProvider() {
        if (forcedProvider == null || forcedProvider.isBlank()) return Optional.empty();
        return Optional.of(new ProviderKey(forcedProvider));
    }

    /** Typed accessor for the engine: trailing evidence window. */
    public Duration window() {
        return Duration.ofDays(windowDays);
    }

    /** Typed accessor for the engine: maximum tolerated trailing failure rate. */
    public BigDecimal maxFailRate() { return maxFailRate; }

    /** Typed accessor for the engine: cheapest-tier cost tolerance multiplier. */
    public BigDecimal cheapestTierTolerance() { return cheapestTierTolerance; }

    /** Typed accessor for the engine: minimum summed sample count per (provider, category). */
    public long minSampleCount() { return minSampleCount; }

    /** Typed accessor for the engine: routing policy version riding every route. */
    public String policyVersion() { return policyVersion; }
}
