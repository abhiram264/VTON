package com.aifashionvault.generation.vton;

/**
 * Routing constraint seam consumed by the provider routing engine (slice-74).
 *
 * <p>Admission owns breach detection (slice-75); this port is the routing-side consumption
 * point for its "switch to cheaper provider" action. The default binding is the no-op
 * implementation until admission wiring lands.
 */
public interface ProviderRoutingConstraints {

    /**
     * When true, routing restricts candidates to the cheapest evidenced tier: candidates
     * without cost evidence are excluded and survivors must stay within the configured
     * cheapest-tier tolerance of the minimum evidenced cost.
     */
    boolean restrictToCheapestTier();
}
