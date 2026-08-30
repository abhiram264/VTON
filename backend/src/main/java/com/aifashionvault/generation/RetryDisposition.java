package com.aifashionvault.generation;

/**
 * Product-owned retry dispositions distinguishing every repetition mechanism (Q2).
 *
 * <p>Each disposition names a distinct mechanism: replay-safe retransmission reuses
 * the same attempt, evaluator retry reevaluates the same candidate, waiting joins an
 * existing execution, and provider fallback is a routing variant of a new provider
 * execution rather than a retransmission. SQS redelivery is transport only and is
 * never a disposition.</p>
 */
public enum RetryDisposition {
	NO_RETRY,
	REPLAY_SAME_ATTEMPT,
	WAIT_EXISTING_ATTEMPT,
	NEW_TECHNICAL_ATTEMPT,
	QUALITY_REGENERATE,
	EVALUATOR_RETRY,
	FALLBACK_NEW_ATTEMPT
}
