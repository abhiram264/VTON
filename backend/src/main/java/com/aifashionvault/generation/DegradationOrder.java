package com.aifashionvault.generation;

/**
 * Deterministic graceful degradation order as specified in §86.
 */
public enum DegradationOrder {
	DEGRADE_MODE_TO_STANDARD,
	USE_FALLBACK_PROVIDER,
	SAVE_VAULT_ITEM
}
