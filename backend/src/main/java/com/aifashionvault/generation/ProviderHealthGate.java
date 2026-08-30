package com.aifashionvault.generation;

/**
 * Provider health circuit boundary consulted only before new executions (Q56).
 *
 * <p>The gate decides whether a provider is currently eligible for a new
 * provider execution. It is never consulted for polling, billing, or cleanup
 * of existing attempts, so an open circuit cannot strand already-submitted
 * work; health probes are operational traffic and consume no user retry
 * budget. Adaptive rolling-window/half-open tuning is deferred from the first
 * retry slice (Q56).
 */
public interface ProviderHealthGate {

	boolean allowsNewExecution(com.aifashionvault.generation.vton.ProviderKey provider);
}
