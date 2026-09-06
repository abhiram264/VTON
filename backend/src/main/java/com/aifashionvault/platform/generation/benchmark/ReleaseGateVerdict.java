package com.aifashionvault.platform.generation.benchmark;

/**
 * Tri-state outcome of an offline benchmark release gate evaluation (Plan §52).
 */
public enum ReleaseGateVerdict {

	/** Release gate passed: zero findings and at least one matched pair. */
	PASS,

	/** Hard block: one or more floor/ceiling or overall regression violations. */
	BLOCK,

	/** Review required: missing pairs, insufficient sample, or category regressions. */
	REVIEW
}
