package com.aifashionvault.generation.vton;

/**
 * Routing audit trail: names why the returned route was produced. The DEFAULTED family is
 * alarmable — routing fell back instead of choosing from evidence.
 */
public enum RouteOutcome {
    SELECTED, PASSTHROUGH_SINGLE_PROVIDER, PASSTHROUGH_DISABLED,
    DEFAULTED_FORCED, FORCED_IGNORED_UNREGISTERED,
    DEFAULTED_INSUFFICIENT_EVIDENCE, DEFAULTED_NO_ELIGIBLE, DEFAULTED_EVIDENCE_UNAVAILABLE
}
