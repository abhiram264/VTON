package com.aifashionvault.generation.vton;

/**
 * Routing decision seam: picks the provider for a request from performance evidence, health,
 * constraints, and routing properties.
 *
 * <p>Implementations are pure functions of their inputs: identical requests against identical
 * evidence, health, constraints, and properties produce identical routes, and no mutable shared
 * state is read or written.
 */
public interface ProviderRouter {
    ProviderRoute route(RoutingRequest request);
}
