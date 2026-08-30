package com.aifashionvault.platform.generation;

import java.util.HashSet;
import java.util.Set;

import com.aifashionvault.generation.ProviderHealthGate;
import com.aifashionvault.generation.vton.ProviderKey;

/**
 * Static provider-health gate driven by an operator-controlled eligibility
 * set (Q56).
 *
 * <p>The gate is consulted only before new provider executions — never for
 * polling, billing, or cleanup of existing attempts — so an open circuit
 * cannot strand already-submitted work (Q56). It answers from exactly two
 * state modes:
 *
 * <ul>
 * <li>Constructed with an empty healthy set: every provider is considered
 * healthy, and only providers explicitly marked unavailable are blocked.</li>
 * <li>Constructed with a non-empty healthy set: only listed providers are
 * allowed; {@link #markUnavailable(ProviderKey)} and
 * {@link #markAvailable(ProviderKey)} remove from or restore to that set.</li>
 * </ul>
 *
 * <p>No adaptive rolling-window/half-open tuning exists here; that is later
 * scope (Q56). Wiring happens in a later stage's configuration; this class
 * carries no Spring annotations.
 */
public final class StaticProviderHealthGate implements ProviderHealthGate {

	private final boolean allProvidersHealthyByDefault;
	private final Set<ProviderKey> healthyProviders = new HashSet<>();
	private final Set<ProviderKey> unavailableProviders = new HashSet<>();

	public StaticProviderHealthGate(Set<ProviderKey> healthyProviders) {
		if (healthyProviders == null) {
			throw new IllegalArgumentException("healthyProviders is required");
		}
		for (ProviderKey provider : healthyProviders) {
			if (provider == null) {
				throw new IllegalArgumentException("healthyProviders must not contain null");
			}
		}
		this.allProvidersHealthyByDefault = healthyProviders.isEmpty();
		this.healthyProviders.addAll(healthyProviders);
	}

	@Override
	public synchronized boolean allowsNewExecution(ProviderKey provider) {
		if (provider == null) {
			throw new IllegalArgumentException("provider is required");
		}
		if (allProvidersHealthyByDefault) {
			return !unavailableProviders.contains(provider);
		}
		return healthyProviders.contains(provider);
	}

	public synchronized void markUnavailable(ProviderKey provider) {
		if (provider == null) {
			throw new IllegalArgumentException("provider is required");
		}
		if (allProvidersHealthyByDefault) {
			unavailableProviders.add(provider);
		}
		else {
			healthyProviders.remove(provider);
		}
	}

	public synchronized void markAvailable(ProviderKey provider) {
		if (provider == null) {
			throw new IllegalArgumentException("provider is required");
		}
		if (allProvidersHealthyByDefault) {
			unavailableProviders.remove(provider);
		}
		else {
			healthyProviders.add(provider);
		}
	}
}
