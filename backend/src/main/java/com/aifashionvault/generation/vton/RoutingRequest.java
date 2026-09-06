package com.aifashionvault.generation.vton;

import java.util.List;

/**
 * Routing decision input. {@code mode} is nullable: null means global context with no capability
 * filter. {@code categories} is non-null and may be empty; an empty list means "all evidenced
 * categories" (global aggregation). {@code objective} is required.
 */
public record RoutingRequest(VtonMode mode, List<ItemCategory> categories, RoutingObjective objective) {
    public RoutingRequest {
        if (categories == null) throw new IllegalArgumentException("categories is required");
        categories = List.copyOf(categories);
        if (objective == null) throw new IllegalArgumentException("objective is required");
    }
}
