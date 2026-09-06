package com.aifashionvault.generation.vton;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Auditable routing decision.
 *
 * <p>{@code modelOrMode} is audit-only, taken from the winning evidence row; null when no
 * evidence backs the route. {@code axisScores} keys are "quality", "cost", "latency" with values
 * in [0,1]; the map may be empty for passthrough/default outcomes and is always unmodifiable.
 *
 * <p>Media-free audit guarantee (ADR-015): routing records carry only provider keys, model
 * identifiers, enums, versions, counts, and numbers — never media references, URLs, user
 * identifiers, or credentials.
 */
public record ProviderRoute(
    ProviderKey providerKey,
    String modelOrMode,
    String routingPolicyVersion,
    RoutingObjective objective,
    RouteOutcome outcome,
    int candidateCount,
    Map<String, BigDecimal> axisScores) {

    public ProviderRoute {
        if (providerKey == null) throw new IllegalArgumentException("providerKey is required");
        if (routingPolicyVersion == null || routingPolicyVersion.isBlank()) {
            throw new IllegalArgumentException("routingPolicyVersion is required");
        }
        if (objective == null) throw new IllegalArgumentException("objective is required");
        if (outcome == null) throw new IllegalArgumentException("outcome is required");
        if (candidateCount < 0) throw new IllegalArgumentException("candidateCount must not be negative");
        if (axisScores == null) throw new IllegalArgumentException("axisScores is required");
        axisScores = Map.copyOf(axisScores);
    }
}
