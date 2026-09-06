package com.aifashionvault.generation.vton;

/**
 * Default routing metrics: discards decisions. Used until observability wiring lands.
 */
public final class NoopProviderRoutingMetrics implements ProviderRoutingMetrics {

    @Override
    public void routeSelected(ProviderRoute route) {
        // no-op by contract
    }
}
