package com.aifashionvault.generation;

/**
 * Fail-closed outcome of resolving a persisted retry-policy snapshot
 * representation.
 *
 * <p>Only {@link State#VALID} carries a {@link RetryPolicySnapshot}; every
 * non-valid state carries a null snapshot so missing, empty, malformed, and
 * unknown-version rows can never be mistaken for an admitted policy. No
 * resolution path substitutes current defaults for a historical row.</p>
 */
public record RetryPolicyResolution(State state, RetryPolicySnapshot snapshot) {

	/**
	 * Why the persisted representation was accepted or rejected.
	 */
	public enum State {
		/** The representation parsed exactly into a known snapshot version. */
		VALID,
		/** The persisted value is present but empty or blank. */
		EMPTY,
		/** No persisted value exists for the job. */
		MISSING,
		/** The value is malformed or violates the settled ceilings. */
		INVALID,
		/** The value names a snapshot version this build cannot honor. */
		UNSUPPORTED_VERSION
	}

	public RetryPolicyResolution {
		if (state == null) {
			throw new IllegalArgumentException("state is required");
		}
		if (state == State.VALID) {
			if (snapshot == null) {
				throw new IllegalArgumentException("snapshot is required for a VALID resolution");
			}
		} else if (snapshot != null) {
			throw new IllegalArgumentException("only a VALID resolution may carry a snapshot");
		}
	}
}
