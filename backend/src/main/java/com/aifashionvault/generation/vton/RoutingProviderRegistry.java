package com.aifashionvault.generation.vton;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Routing decorator over a base {@link ProviderRegistry} (slice-74 U3): routes the global
 * provider choice through the engine at the only selection seam the platform already calls —
 * {@link #selectedProviderKey()} — while every other seam delegates unchanged.
 *
 * <p>{@link #resolve(ProviderKey)} and {@link #providerKeys()} delegate verbatim, so attempts
 * stay pinned to real provider keys (economics attribution preserved, ADR-014): aliases and
 * {@link UnknownProviderException} pass through unchanged. {@link #selectedProvider()} inherits
 * the interface default and therefore resolves the routed key.
 *
 * <p>{@link #selectedProviderKey()} contract: build the global routing request
 * {@code RoutingRequest(null, List.of(), properties.defaultObjective())}, route, emit the route
 * to {@link ProviderRoutingMetrics}, and return the route's key. Any {@link RuntimeException}
 * from engine or evidence store fails open to the base registry's bootstrap key with outcome
 * {@link RouteOutcome#DEFAULTED_EVIDENCE_UNAVAILABLE} and empty axis scores. Never returns null,
 * never throws; every decision — passthrough included — is metric-visible. A broken metrics
 * adapter can never break provider selection.
 */
public final class RoutingProviderRegistry implements ProviderRegistry {

    private final ProviderRegistry delegate;
    private final ProviderRouter router;
    private final ProviderRoutingMetrics metrics;
    private final ProviderRoutingProperties properties;

    public RoutingProviderRegistry(
        ProviderRegistry delegate,
        ProviderRouter router,
        ProviderRoutingMetrics metrics,
        ProviderRoutingProperties properties) {
        if (delegate == null) throw new IllegalArgumentException("delegate is required");
        if (router == null) throw new IllegalArgumentException("router is required");
        if (metrics == null) throw new IllegalArgumentException("metrics is required");
        if (properties == null) throw new IllegalArgumentException("properties is required");
        this.delegate = delegate;
        this.router = router;
        this.metrics = metrics;
        this.properties = properties;
    }

    @Override
    public VtonProvider resolve(ProviderKey providerKey) {
        return delegate.resolve(providerKey);
    }

    @Override
    public ProviderKey selectedProviderKey() {
        try {
            ProviderRoute route = router.route(new RoutingRequest(null, List.of(), properties.defaultObjective()));
            emit(route);
            return route.providerKey();
        } catch (RuntimeException engineFailure) {
            ProviderRoute fallback = new ProviderRoute(delegate.selectedProviderKey(), null,
                properties.policyVersion(), properties.defaultObjective(),
                RouteOutcome.DEFAULTED_EVIDENCE_UNAVAILABLE, delegate.providerKeys().size(), Map.of());
            emit(fallback);
            return fallback.providerKey();
        }
    }

    @Override
    public Collection<ProviderKey> providerKeys() {
        return delegate.providerKeys();
    }

    private void emit(ProviderRoute route) {
        try {
            metrics.routeSelected(route);
        } catch (RuntimeException ignored) {
            // A broken metrics adapter must never break provider selection.
        }
    }
}
