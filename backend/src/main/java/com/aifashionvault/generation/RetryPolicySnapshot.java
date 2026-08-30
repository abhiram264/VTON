package com.aifashionvault.generation;

import java.time.Duration;

/**
 * Immutable retry limits and timing decisions resolved for a generation job.
 *
 * <p>The values in this record are persisted with the job so recovery uses the
 * same policy that admitted the work. Live safety controls remain outside the
 * snapshot.</p>
 *
 * <p>Version 1 rows keep the historical serialization and semantics exactly;
 * they carry no version 2 fields. Version 2 adds the explicit
 * technical-retry, evaluator-retry, and provider-routing-policy limits.
 * {@link #parseResolution(String)} distinguishes missing, empty, malformed,
 * and unsupported-version rows fail-closed and never substitutes current
 * defaults, so historical jobs never silently gain today's policy.</p>
 */
public record RetryPolicySnapshot(
	int maxPaidAttempts,
	int additionalLogicalAttempts,
	int maxQualityRetriesPerStep,
	int maxReplaySafeRetransmits,
	int maxTransmissionsPerAttempt,
	Duration backoffBase,
	Duration computedBackoffCap,
	Duration maxAutonomousWait,
	Duration orchestrationDeadline,
	boolean technicalRetryEnabled,
	boolean fallbackEnabled,
	boolean qualityRegenerationEnabled,
	String providerTimingPolicyId,
	int snapshotVersion,
	Integer maxTechnicalRetries,
	Integer maxEvaluatorRetriesPerCandidate,
	String providerRoutingPolicyId) {

	public static final int ADDITIONAL_LOGICAL_ATTEMPTS = 2;
	public static final int MAX_QUALITY_RETRIES_PER_STEP = 1;
	public static final int MAX_REPLAY_SAFE_RETRANSMITS = 2;
	public static final int MAX_TRANSMISSIONS_PER_ATTEMPT = 3;
	public static final Duration BACKOFF_BASE = Duration.ofSeconds(1);
	public static final Duration COMPUTED_BACKOFF_CAP = Duration.ofSeconds(30);
	public static final Duration MAX_AUTONOMOUS_WAIT = Duration.ofMinutes(5);
	public static final String DEFAULT_PROVIDER_TIMING_POLICY_ID = "default";
	public static final String DEFAULT_PROVIDER_ROUTING_POLICY_ID = "fashn-only-v1";
	public static final int SNAPSHOT_VERSION_V1 = 1;
	public static final int SNAPSHOT_VERSION_V2 = 2;

	private static final String SERIALIZED_PREFIX_V1 = "RetryPolicySnapshot[";
	private static final String SERIALIZED_PREFIX_V2 = "RetryPolicySnapshotV2[";
	private static final String SERIALIZED_PREFIX_FAMILY = "RetryPolicySnapshot";
	private static final int SERIALIZED_FIELD_COUNT_V1 = 13;
	private static final int SERIALIZED_FIELD_COUNT_V2 = 17;
	private static final String VERSION_FIELD_NAME = "version";

	public RetryPolicySnapshot {
		if (maxPaidAttempts < 1) {
			throw new IllegalArgumentException("maxPaidAttempts must be >= 1");
		}
		if (additionalLogicalAttempts < 0 || additionalLogicalAttempts > ADDITIONAL_LOGICAL_ATTEMPTS) {
			throw new IllegalArgumentException("additionalLogicalAttempts must be between 0 and 2");
		}
		if (maxPaidAttempts < additionalLogicalAttempts + 1) {
			throw new IllegalArgumentException("maxPaidAttempts must include one baseline attempt");
		}
		if (maxQualityRetriesPerStep < 0 || maxQualityRetriesPerStep > MAX_QUALITY_RETRIES_PER_STEP) {
			throw new IllegalArgumentException("maxQualityRetriesPerStep must be between 0 and 1");
		}
		if (maxReplaySafeRetransmits < 0 || maxReplaySafeRetransmits > MAX_REPLAY_SAFE_RETRANSMITS) {
			throw new IllegalArgumentException("maxReplaySafeRetransmits must be between 0 and 2");
		}
		if (maxTransmissionsPerAttempt < 1 || maxTransmissionsPerAttempt > MAX_TRANSMISSIONS_PER_ATTEMPT) {
			throw new IllegalArgumentException("maxTransmissionsPerAttempt must be between 1 and 3");
		}
		if (maxTransmissionsPerAttempt < maxReplaySafeRetransmits + 1) {
			throw new IllegalArgumentException("maxTransmissionsPerAttempt must include the original transmission");
		}
		validateDuration(backoffBase, "backoffBase", BACKOFF_BASE);
		validateDuration(computedBackoffCap, "computedBackoffCap", COMPUTED_BACKOFF_CAP);
		validateDuration(maxAutonomousWait, "maxAutonomousWait", MAX_AUTONOMOUS_WAIT);
		validateDuration(orchestrationDeadline, "orchestrationDeadline", GenerationJob.ORCHESTRATION_DEADLINE);
		if (providerTimingPolicyId == null || providerTimingPolicyId.isBlank()) {
			throw new IllegalArgumentException("providerTimingPolicyId is required");
		}
		if (snapshotVersion == SNAPSHOT_VERSION_V1) {
			if (maxTechnicalRetries != null
				|| maxEvaluatorRetriesPerCandidate != null
				|| providerRoutingPolicyId != null) {
				throw new IllegalArgumentException("version 1 snapshots must not carry version 2 fields");
			}
		} else if (snapshotVersion == SNAPSHOT_VERSION_V2) {
			if (maxTechnicalRetries == null
				|| maxTechnicalRetries < 0
				|| maxTechnicalRetries > GenerationRetryBudget.MAX_TECHNICAL_RETRIES_PER_JOB) {
				throw new IllegalArgumentException("maxTechnicalRetries must be between 0 and 2");
			}
			if (maxEvaluatorRetriesPerCandidate == null
				|| maxEvaluatorRetriesPerCandidate < 0
				|| maxEvaluatorRetriesPerCandidate > GenerationRetryBudget.MAX_EVALUATOR_RETRIES_PER_CANDIDATE) {
				throw new IllegalArgumentException("maxEvaluatorRetriesPerCandidate must be between 0 and 1");
			}
			if (providerRoutingPolicyId == null || providerRoutingPolicyId.isBlank()) {
				throw new IllegalArgumentException("providerRoutingPolicyId is required");
			}
		} else {
			throw new IllegalArgumentException("snapshotVersion must be 1 or 2");
		}
	}

	/**
	 * Compatibility constructor for version 1 snapshots. The version 2 fields
	 * stay null so historical serialization and semantics remain unchanged.
	 */
	public RetryPolicySnapshot(
		int maxPaidAttempts,
		int additionalLogicalAttempts,
		int maxQualityRetriesPerStep,
		int maxReplaySafeRetransmits,
		int maxTransmissionsPerAttempt,
		Duration backoffBase,
		Duration computedBackoffCap,
		Duration maxAutonomousWait,
		Duration orchestrationDeadline,
		boolean technicalRetryEnabled,
		boolean fallbackEnabled,
		boolean qualityRegenerationEnabled,
		String providerTimingPolicyId) {
		this(
			maxPaidAttempts,
			additionalLogicalAttempts,
			maxQualityRetriesPerStep,
			maxReplaySafeRetransmits,
			maxTransmissionsPerAttempt,
			backoffBase,
			computedBackoffCap,
			maxAutonomousWait,
			orchestrationDeadline,
			technicalRetryEnabled,
			fallbackEnabled,
			qualityRegenerationEnabled,
			providerTimingPolicyId,
			SNAPSHOT_VERSION_V1,
			null,
			null,
			null);
	}

	/**
	 * Resolves the settled default policy for a finite render plan.
	 *
	 * <p>Fallback starts disabled for staged rollout; all other retry features
	 * are enabled. The hard ceilings are never configuration inputs.</p>
	 */
	public static RetryPolicySnapshot forStepCount(int renderStepCount) {
		return forStepCount(renderStepCount, true, false, true);
	}

	/**
	 * Resolves the settled policy while allowing rollout flags to disable a
	 * feature. Flags do not alter any retry or transmission ceiling.
	 */
	public static RetryPolicySnapshot forStepCount(
		int renderStepCount,
		boolean technicalRetryEnabled,
		boolean fallbackEnabled,
		boolean qualityRegenerationEnabled) {
		if (renderStepCount < 1) {
			throw new IllegalArgumentException("renderStepCount must be >= 1");
		}
		if (renderStepCount > Integer.MAX_VALUE - ADDITIONAL_LOGICAL_ATTEMPTS) {
			throw new IllegalArgumentException("renderStepCount is too large");
		}
		return new RetryPolicySnapshot(
			renderStepCount + ADDITIONAL_LOGICAL_ATTEMPTS,
			ADDITIONAL_LOGICAL_ATTEMPTS,
			MAX_QUALITY_RETRIES_PER_STEP,
			MAX_REPLAY_SAFE_RETRANSMITS,
			MAX_TRANSMISSIONS_PER_ATTEMPT,
			BACKOFF_BASE,
			COMPUTED_BACKOFF_CAP,
			MAX_AUTONOMOUS_WAIT,
			GenerationJob.ORCHESTRATION_DEADLINE,
			technicalRetryEnabled,
			fallbackEnabled,
			qualityRegenerationEnabled,
			DEFAULT_PROVIDER_TIMING_POLICY_ID);
	}

	/**
	 * Resolves the settled version 2 policy for a finite render plan.
	 *
	 * <p>The paid-capable cap is the fixed V1 cap of
	 * {@link GenerationRetryBudget#DEFAULT_MAX_PAID_ATTEMPTS} regardless of
	 * the step count; the render plan only needs to be finite and non-empty.
	 * Fallback starts disabled for staged rollout; all other retry features
	 * are enabled. The hard ceilings are never configuration inputs.</p>
	 */
	public static RetryPolicySnapshot forStepCountV2(int renderStepCount) {
		return forStepCountV2(renderStepCount, true, false, true);
	}

	/**
	 * Resolves the settled version 2 policy while allowing rollout flags to
	 * disable a feature. Flags do not alter any retry or transmission
	 * ceiling.
	 */
	public static RetryPolicySnapshot forStepCountV2(
		int renderStepCount,
		boolean technicalRetryEnabled,
		boolean fallbackEnabled,
		boolean qualityRegenerationEnabled) {
		if (renderStepCount < 1) {
			throw new IllegalArgumentException("renderStepCount must be >= 1");
		}
		if (renderStepCount > Integer.MAX_VALUE - ADDITIONAL_LOGICAL_ATTEMPTS) {
			throw new IllegalArgumentException("renderStepCount is too large");
		}
		return new RetryPolicySnapshot(
			GenerationRetryBudget.DEFAULT_MAX_PAID_ATTEMPTS,
			ADDITIONAL_LOGICAL_ATTEMPTS,
			MAX_QUALITY_RETRIES_PER_STEP,
			MAX_REPLAY_SAFE_RETRANSMITS,
			MAX_TRANSMISSIONS_PER_ATTEMPT,
			BACKOFF_BASE,
			COMPUTED_BACKOFF_CAP,
			MAX_AUTONOMOUS_WAIT,
			GenerationJob.ORCHESTRATION_DEADLINE,
			technicalRetryEnabled,
			fallbackEnabled,
			qualityRegenerationEnabled,
			DEFAULT_PROVIDER_TIMING_POLICY_ID,
			SNAPSHOT_VERSION_V2,
			GenerationRetryBudget.MAX_TECHNICAL_RETRIES_PER_JOB,
			GenerationRetryBudget.MAX_EVALUATOR_RETRIES_PER_CANDIDATE,
			DEFAULT_PROVIDER_ROUTING_POLICY_ID);
	}

	/**
	 * Returns a stable, provider-neutral representation suitable for a later
	 * JDBC column. It intentionally contains only resolved policy data.
	 */
	public String serialize() {
		if (snapshotVersion == SNAPSHOT_VERSION_V2) {
			return "RetryPolicySnapshotV2["
				+ "version=" + snapshotVersion
				+ ";maxPaidAttempts=" + maxPaidAttempts
				+ ";additionalLogicalAttempts=" + additionalLogicalAttempts
				+ ";maxTechnicalRetries=" + maxTechnicalRetries
				+ ";maxQualityRetriesPerStep=" + maxQualityRetriesPerStep
				+ ";maxEvaluatorRetriesPerCandidate=" + maxEvaluatorRetriesPerCandidate
				+ ";maxReplaySafeRetransmits=" + maxReplaySafeRetransmits
				+ ";maxTransmissionsPerAttempt=" + maxTransmissionsPerAttempt
				+ ";backoffBase=" + backoffBase
				+ ";computedBackoffCap=" + computedBackoffCap
				+ ";maxAutonomousWait=" + maxAutonomousWait
				+ ";orchestrationDeadline=" + orchestrationDeadline
				+ ";technicalRetryEnabled=" + technicalRetryEnabled
				+ ";fallbackEnabled=" + fallbackEnabled
				+ ";qualityRegenerationEnabled=" + qualityRegenerationEnabled
				+ ";providerTimingPolicyId=" + providerTimingPolicyId
				+ ";providerRoutingPolicyId=" + providerRoutingPolicyId
				+ ']';
		}
		return "RetryPolicySnapshot["
			+ "maxPaidAttempts=" + maxPaidAttempts
			+ ";additionalLogicalAttempts=" + additionalLogicalAttempts
			+ ";maxQualityRetriesPerStep=" + maxQualityRetriesPerStep
			+ ";maxReplaySafeRetransmits=" + maxReplaySafeRetransmits
			+ ";maxTransmissionsPerAttempt=" + maxTransmissionsPerAttempt
			+ ";backoffBase=" + backoffBase
			+ ";computedBackoffCap=" + computedBackoffCap
			+ ";maxAutonomousWait=" + maxAutonomousWait
			+ ";orchestrationDeadline=" + orchestrationDeadline
			+ ";technicalRetryEnabled=" + technicalRetryEnabled
			+ ";fallbackEnabled=" + fallbackEnabled
			+ ";qualityRegenerationEnabled=" + qualityRegenerationEnabled
			+ ";providerTimingPolicyId=" + providerTimingPolicyId
			+ ']';
	}

	/**
	 * Resolves a persisted snapshot representation without ever throwing.
	 *
	 * <p>Missing, empty, malformed, and unknown-version rows fail closed into
	 * distinct states and carry no snapshot, instead of substituting current
	 * defaults. Only an exact known v1 or v2 representation yields a
	 * {@link RetryPolicyResolution.State#VALID} result, so historical jobs
	 * never gain capabilities they were never granted.</p>
	 */
	public static RetryPolicyResolution parseResolution(String serialized) {
		if (serialized == null) {
			return new RetryPolicyResolution(RetryPolicyResolution.State.MISSING, null);
		}
		if (serialized.isBlank()) {
			return new RetryPolicyResolution(RetryPolicyResolution.State.EMPTY, null);
		}
		if (serialized.startsWith(SERIALIZED_PREFIX_V2)) {
			return resolveV2(serialized);
		}
		if (serialized.startsWith(SERIALIZED_PREFIX_V1)) {
			try {
				return new RetryPolicyResolution(RetryPolicyResolution.State.VALID, parse(serialized));
			} catch (IllegalArgumentException exception) {
				return new RetryPolicyResolution(RetryPolicyResolution.State.INVALID, null);
			}
		}
		if (serialized.startsWith(SERIALIZED_PREFIX_FAMILY)) {
			return new RetryPolicyResolution(RetryPolicyResolution.State.UNSUPPORTED_VERSION, null);
		}
		return new RetryPolicyResolution(RetryPolicyResolution.State.INVALID, null);
	}

	private static RetryPolicyResolution resolveV2(String serialized) {
		if (!serialized.endsWith("]")) {
			return new RetryPolicyResolution(RetryPolicyResolution.State.INVALID, null);
		}
		String[] fields = serialized.substring(SERIALIZED_PREFIX_V2.length(), serialized.length() - 1)
			.split(";", -1);
		String versionFieldPrefix = VERSION_FIELD_NAME + '=';
		if (!fields[0].startsWith(versionFieldPrefix)) {
			return new RetryPolicyResolution(RetryPolicyResolution.State.INVALID, null);
		}
		int version;
		try {
			version = Integer.parseInt(fields[0].substring(versionFieldPrefix.length()));
		} catch (RuntimeException exception) {
			return new RetryPolicyResolution(RetryPolicyResolution.State.INVALID, null);
		}
		if (version != SNAPSHOT_VERSION_V2) {
			return new RetryPolicyResolution(RetryPolicyResolution.State.UNSUPPORTED_VERSION, null);
		}
		try {
			return new RetryPolicyResolution(RetryPolicyResolution.State.VALID, parse(serialized));
		} catch (IllegalArgumentException exception) {
			return new RetryPolicyResolution(RetryPolicyResolution.State.INVALID, null);
		}
	}

	/**
	 * Parses the deterministic representation produced by {@link #serialize()}.
	 *
	 * <p>Only the exact persisted shape is accepted. Parsed values are passed
	 * through the record validation before the representation is compared back
	 * to the input, so alternate spellings, unknown fields, trailing data, and
	 * cap-raising values fail closed.</p>
	 */
	public static RetryPolicySnapshot parse(String serialized) {
		if (serialized == null) {
			throw new IllegalArgumentException("serialized snapshot is required");
		}
		if (serialized.startsWith(SERIALIZED_PREFIX_V2)) {
			return parseV2(serialized);
		}

		String prefix = SERIALIZED_PREFIX_V1;
		if (!serialized.startsWith(prefix) || !serialized.endsWith("]")) {
			throw new IllegalArgumentException("Malformed retry policy snapshot");
		}

		String[] fields = serialized.substring(prefix.length(), serialized.length() - 1)
			.split(";", -1);
		if (fields.length != SERIALIZED_FIELD_COUNT_V1) {
			throw new IllegalArgumentException("Malformed retry policy snapshot");
		}

		try {
			int maxPaidAttempts = parseInt(fields[0], "maxPaidAttempts");
			int additionalLogicalAttempts = parseInt(fields[1], "additionalLogicalAttempts");
			int maxQualityRetriesPerStep = parseInt(fields[2], "maxQualityRetriesPerStep");
			int maxReplaySafeRetransmits = parseInt(fields[3], "maxReplaySafeRetransmits");
			int maxTransmissionsPerAttempt = parseInt(fields[4], "maxTransmissionsPerAttempt");
			Duration backoffBase = parseDuration(fields[5], "backoffBase");
			Duration computedBackoffCap = parseDuration(fields[6], "computedBackoffCap");
			Duration maxAutonomousWait = parseDuration(fields[7], "maxAutonomousWait");
			Duration orchestrationDeadline = parseDuration(fields[8], "orchestrationDeadline");
			boolean technicalRetryEnabled = parseBoolean(fields[9], "technicalRetryEnabled");
			boolean fallbackEnabled = parseBoolean(fields[10], "fallbackEnabled");
			boolean qualityRegenerationEnabled = parseBoolean(fields[11], "qualityRegenerationEnabled");
			String providerTimingPolicyId = parseField(fields[12], "providerTimingPolicyId");
			rejectUnsafePolicyCharacters(providerTimingPolicyId, "providerTimingPolicyId");

			RetryPolicySnapshot snapshot = new RetryPolicySnapshot(
				maxPaidAttempts,
				additionalLogicalAttempts,
				maxQualityRetriesPerStep,
				maxReplaySafeRetransmits,
				maxTransmissionsPerAttempt,
				backoffBase,
				computedBackoffCap,
				maxAutonomousWait,
				orchestrationDeadline,
				technicalRetryEnabled,
				fallbackEnabled,
				qualityRegenerationEnabled,
				providerTimingPolicyId);
			if (!serialized.equals(snapshot.serialize())) {
				throw new IllegalArgumentException("Malformed retry policy snapshot");
			}
			return snapshot;
		} catch (IllegalArgumentException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw new IllegalArgumentException("Malformed retry policy snapshot", exception);
		}
	}

	private static RetryPolicySnapshot parseV2(String serialized) {
		if (!serialized.endsWith("]")) {
			throw new IllegalArgumentException("Malformed retry policy snapshot");
		}

		String[] fields = serialized.substring(SERIALIZED_PREFIX_V2.length(), serialized.length() - 1)
			.split(";", -1);
		if (fields.length != SERIALIZED_FIELD_COUNT_V2) {
			throw new IllegalArgumentException("Malformed retry policy snapshot");
		}

		try {
			int snapshotVersion = parseInt(fields[0], VERSION_FIELD_NAME);
			int maxPaidAttempts = parseInt(fields[1], "maxPaidAttempts");
			int additionalLogicalAttempts = parseInt(fields[2], "additionalLogicalAttempts");
			int maxTechnicalRetries = parseInt(fields[3], "maxTechnicalRetries");
			int maxQualityRetriesPerStep = parseInt(fields[4], "maxQualityRetriesPerStep");
			int maxEvaluatorRetriesPerCandidate = parseInt(fields[5], "maxEvaluatorRetriesPerCandidate");
			int maxReplaySafeRetransmits = parseInt(fields[6], "maxReplaySafeRetransmits");
			int maxTransmissionsPerAttempt = parseInt(fields[7], "maxTransmissionsPerAttempt");
			Duration backoffBase = parseDuration(fields[8], "backoffBase");
			Duration computedBackoffCap = parseDuration(fields[9], "computedBackoffCap");
			Duration maxAutonomousWait = parseDuration(fields[10], "maxAutonomousWait");
			Duration orchestrationDeadline = parseDuration(fields[11], "orchestrationDeadline");
			boolean technicalRetryEnabled = parseBoolean(fields[12], "technicalRetryEnabled");
			boolean fallbackEnabled = parseBoolean(fields[13], "fallbackEnabled");
			boolean qualityRegenerationEnabled = parseBoolean(fields[14], "qualityRegenerationEnabled");
			String providerTimingPolicyId = parseField(fields[15], "providerTimingPolicyId");
			String providerRoutingPolicyId = parseField(fields[16], "providerRoutingPolicyId");
			rejectUnsafePolicyCharacters(providerTimingPolicyId, "providerTimingPolicyId");
			rejectUnsafePolicyCharacters(providerRoutingPolicyId, "providerRoutingPolicyId");

			RetryPolicySnapshot snapshot = new RetryPolicySnapshot(
				maxPaidAttempts,
				additionalLogicalAttempts,
				maxQualityRetriesPerStep,
				maxReplaySafeRetransmits,
				maxTransmissionsPerAttempt,
				backoffBase,
				computedBackoffCap,
				maxAutonomousWait,
				orchestrationDeadline,
				technicalRetryEnabled,
				fallbackEnabled,
				qualityRegenerationEnabled,
				providerTimingPolicyId,
				snapshotVersion,
				maxTechnicalRetries,
				maxEvaluatorRetriesPerCandidate,
				providerRoutingPolicyId);
			if (!serialized.equals(snapshot.serialize())) {
				throw new IllegalArgumentException("Malformed retry policy snapshot");
			}
			return snapshot;
		} catch (IllegalArgumentException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw new IllegalArgumentException("Malformed retry policy snapshot", exception);
		}
	}

	private static void rejectUnsafePolicyCharacters(String value, String name) {
		for (int i = 0; i < value.length(); i++) {
			char character = value.charAt(i);
			if (character == ']' || character == '=' || character < 0x20 || character == 0x7f) {
				throw new IllegalArgumentException(name + " contains unsafe data");
			}
		}
	}

	private static int parseInt(String field, String name) {
		return Integer.parseInt(parseField(field, name));
	}

	private static Duration parseDuration(String field, String name) {
		try {
			return Duration.parse(parseField(field, name));
		} catch (RuntimeException exception) {
			throw new IllegalArgumentException(name + " is malformed", exception);
		}
	}

	private static boolean parseBoolean(String field, String name) {
		String value = parseField(field, name);
		if (!value.equals("true") && !value.equals("false")) {
			throw new IllegalArgumentException(name + " must be true or false");
		}
		return Boolean.parseBoolean(value);
	}

	private static String parseField(String field, String name) {
		String prefix = name + '=';
		if (!field.startsWith(prefix)) {
			throw new IllegalArgumentException("Unexpected serialized field");
		}
		return field.substring(prefix.length());
	}

	@Override
	public String toString() {
		return serialize();
	}

	private static void validateDuration(Duration value, String name, Duration ceiling) {
		if (value == null) {
			throw new IllegalArgumentException(name + " is required");
		}
		if (value.isNegative()) {
			throw new IllegalArgumentException(name + " must be non-negative");
		}
		if (value.compareTo(ceiling) > 0) {
			throw new IllegalArgumentException(name + " exceeds its hard ceiling");
		}
	}
}
