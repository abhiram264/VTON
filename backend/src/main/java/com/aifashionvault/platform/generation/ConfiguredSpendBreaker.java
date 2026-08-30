package com.aifashionvault.platform.generation;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import com.aifashionvault.generation.SpendBreaker;
import com.aifashionvault.shared.UserId;

/**
 * In-memory hard cost breaker with global and per-user ceilings (Q55).
 *
 * <p>{@link #blocksNewProviderExecution(UserId)} reports blocked once the
 * accumulated global spend or the user's accumulated spend has reached or
 * exceeded the respective ceiling. Blocking stays blocking until spend
 * accounting says otherwise: there is no daily-reset waiting, so the
 * orchestrator terminates {@code COST_BUDGET_BLOCKED} instead of deferring
 * work (Q55). Recorded spend is never released here; reconciliation of
 * submitted attempts is not gated by this breaker at all.
 *
 * <p>All state is guarded by the intrinsic lock, so concurrent worker threads
 * observe one consistent accumulator. Wiring happens in a later stage's
 * configuration; this class carries no Spring annotations.
 */
public final class ConfiguredSpendBreaker implements SpendBreaker {

	private final BigDecimal globalCeiling;
	private final BigDecimal perUserCeiling;
	private final Map<UserId, BigDecimal> spendByUser = new HashMap<>();
	private BigDecimal globalSpend = BigDecimal.ZERO;

	public ConfiguredSpendBreaker(BigDecimal globalCeiling, BigDecimal perUserCeiling) {
		if (globalCeiling == null || globalCeiling.compareTo(BigDecimal.ZERO) <= 0) {
			throw new IllegalArgumentException("globalCeiling must be positive");
		}
		if (perUserCeiling == null || perUserCeiling.compareTo(BigDecimal.ZERO) <= 0) {
			throw new IllegalArgumentException("perUserCeiling must be positive");
		}
		this.globalCeiling = globalCeiling;
		this.perUserCeiling = perUserCeiling;
	}

	@Override
	public synchronized boolean blocksNewProviderExecution(UserId userId) {
		if (userId == null) {
			throw new IllegalArgumentException("userId is required");
		}
		if (globalSpend.compareTo(globalCeiling) >= 0) {
			return true;
		}
		return spendByUser.getOrDefault(userId, BigDecimal.ZERO).compareTo(perUserCeiling) >= 0;
	}

	@Override
	public synchronized void recordAttemptSpend(UserId userId, BigDecimal amount) {
		if (userId == null) {
			throw new IllegalArgumentException("userId is required");
		}
		if (amount == null) {
			throw new IllegalArgumentException("amount is required");
		}
		if (amount.signum() < 0) {
			throw new IllegalArgumentException("amount must not be negative");
		}
		if (amount.signum() == 0) {
			return;
		}
		globalSpend = globalSpend.add(amount);
		spendByUser.merge(userId, amount, BigDecimal::add);
	}
}
