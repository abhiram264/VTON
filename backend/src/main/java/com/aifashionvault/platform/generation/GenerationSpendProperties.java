package com.aifashionvault.platform.generation;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Approved generation spend caps (C19).
 *
 * These ceilings were previously hard-coded constants inside
 * {@link GenerationProcessorConfiguration} (USD 500 global / 25 per user / 250
 * per provider per day). They are launch configuration, not code: an operator
 * approves the budget and sets it per environment, so the values must be
 * visible and overridable rather than compiled in. The field defaults below
 * reproduce the previous constants exactly and match the values declared in
 * application.yaml; unset configuration therefore keeps the current behavior.
 *
 * The per-provider ceiling applies to whichever provider the registry selects
 * at runtime (see {@link GenerationProcessorConfiguration#abuseBudgetPolicy}),
 * so it is a single ceiling rather than a per-key map.
 */
@ConfigurationProperties(prefix = "vault.generation.spend")
public class GenerationSpendProperties {

	private BigDecimal globalCeiling = new BigDecimal("500.00");

	private BigDecimal perUserCeiling = new BigDecimal("25.00");

	private BigDecimal providerCeiling = new BigDecimal("250.00");

	private String currency = "USD";

	private Duration window = Duration.ofDays(1);

	public BigDecimal getGlobalCeiling() {
		return this.globalCeiling;
	}

	public void setGlobalCeiling(BigDecimal globalCeiling) {
		this.globalCeiling = globalCeiling;
	}

	public BigDecimal getPerUserCeiling() {
		return this.perUserCeiling;
	}

	public void setPerUserCeiling(BigDecimal perUserCeiling) {
		this.perUserCeiling = perUserCeiling;
	}

	public BigDecimal getProviderCeiling() {
		return this.providerCeiling;
	}

	public void setProviderCeiling(BigDecimal providerCeiling) {
		this.providerCeiling = providerCeiling;
	}

	public String getCurrency() {
		return this.currency;
	}

	public void setCurrency(String currency) {
		this.currency = currency;
	}

	public Duration getWindow() {
		return this.window;
	}

	public void setWindow(Duration window) {
		this.window = window;
	}
}
