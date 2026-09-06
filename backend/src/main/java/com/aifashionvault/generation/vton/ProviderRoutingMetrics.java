package com.aifashionvault.generation.vton;

/**
 * Routing decision observation seam (slice-74): every route decision is recorded here so the
 * DEFAULTED family and forced-pin conditions are alarmable. Records are media-free by
 * construction (ADR-015) — see {@link ProviderRoute}.
 */
public interface ProviderRoutingMetrics {

    void routeSelected(ProviderRoute route);
}
