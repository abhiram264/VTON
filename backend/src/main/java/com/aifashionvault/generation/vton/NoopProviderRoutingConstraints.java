package com.aifashionvault.generation.vton;

/**
 * Default routing constraints: no restriction. Used until admission (slice-75) wires a
 * breach-aware implementation.
 */
public final class NoopProviderRoutingConstraints implements ProviderRoutingConstraints {

    @Override
    public boolean restrictToCheapestTier() {
        return false;
    }
}
