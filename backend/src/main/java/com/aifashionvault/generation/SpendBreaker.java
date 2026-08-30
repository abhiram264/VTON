package com.aifashionvault.generation;

import com.aifashionvault.shared.TerminalReason;
import com.aifashionvault.shared.UserId;

/**
 * Hard cost breaker that outranks every retry entitlement (Q55, Q70).
 *
 * <p>The breaker blocks all new provider executions — including quality
 * regeneration and fallback — but never blocks no-spend reconciliation of
 * already-submitted attempts. A running job that cannot execute within budget
 * terminates {@link TerminalReason#COST_BUDGET_BLOCKED} rather than waiting
 * indefinitely for a daily reset.
 */
public interface SpendBreaker {

	boolean blocksNewProviderExecution(com.aifashionvault.shared.UserId userId);

	void recordAttemptSpend(UserId userId, java.math.BigDecimal amount);
}
