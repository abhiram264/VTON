package com.aifashionvault.generation;

import java.time.Instant;

import com.aifashionvault.shared.TerminalReason;

/**
 * Deterministic outcome of one retry decision (Q14).
 *
 * <p>Granted dispositions carry null {@code terminalReason} and null
 * {@code owningGate}. {@link RetryDisposition#NO_RETRY} always carries an
 * owning gate naming the FIRST failed R4 gate in precedence order, so two
 * workers facing the same state explain their decision identically; it also
 * carries the product-owned terminal reason except for the terminality gate,
 * whose denial adds no reason to an already-terminal job. {@code
 * retryNotBeforeAt} is set only when the disposition schedules delayed
 * work.</p>
 *
 * @param disposition the selected repetition mechanism (Q2)
 * @param terminalReason stable product-owned failure reason, null unless denied for a reason (Q25)
 * @param retryNotBeforeAt absolute instant the delayed work may resume, else null
 * @param owningGate first failed R4 gate when denied, else null (Q14)
 */
public record RetryDecision(
	RetryDisposition disposition,
	TerminalReason terminalReason,
	Instant retryNotBeforeAt,
	String owningGate) {

	/** R4 terminality gate; its denials carry no terminal reason. */
	public static final String TERMINALITY_GATE = "TERMINALITY";

	public RetryDecision {
		if (disposition == null) {
			throw new IllegalArgumentException("disposition is required");
		}
		if (disposition == RetryDisposition.NO_RETRY) {
			if (owningGate == null) {
				throw new IllegalArgumentException("NO_RETRY requires an owning gate");
			}
			if (terminalReason == null && !TERMINALITY_GATE.equals(owningGate)) {
				throw new IllegalArgumentException("NO_RETRY requires a terminal reason unless terminality owns it");
			}
		} else {
			if (terminalReason != null) {
				throw new IllegalArgumentException("granted dispositions carry no terminal reason");
			}
			if (owningGate != null) {
				throw new IllegalArgumentException("granted dispositions carry no owning gate");
			}
		}
		if (retryNotBeforeAt != null && disposition == RetryDisposition.NO_RETRY) {
			throw new IllegalArgumentException("NO_RETRY never schedules future work");
		}
	}

	/**
	 * Denial decision carrying the first failed gate and its terminal reason.
	 */
	public static RetryDecision deny(String owningGate, TerminalReason terminalReason) {
		return new RetryDecision(RetryDisposition.NO_RETRY, terminalReason, null, owningGate);
	}

	/**
	 * Denial for a job that is already terminal: no reason is added to the
	 * persisted terminal state.
	 */
	public static RetryDecision terminalityDenial() {
		return new RetryDecision(RetryDisposition.NO_RETRY, null, null, TERMINALITY_GATE);
	}

	/**
	 * Granted decision without any scheduled wait.
	 */
	public static RetryDecision grant(RetryDisposition disposition) {
		return new RetryDecision(disposition, null, null, null);
	}

	/**
	 * Granted decision scheduling delayed work at an absolute instant.
	 */
	public static RetryDecision grantAt(RetryDisposition disposition, Instant retryNotBeforeAt) {
		if (retryNotBeforeAt == null) {
			throw new IllegalArgumentException("retryNotBeforeAt is required for a delayed grant");
		}
		return new RetryDecision(disposition, null, retryNotBeforeAt, null);
	}
}
