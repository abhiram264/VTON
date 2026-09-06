package com.aifashionvault.shared;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

/**
 * Shared, NOOP-safe seam for the frozen §53 spans. Every instrumentation site starts
 * observations through this facade so feature and platform code never depends on a
 * tracing backend; a {@code null} registry degrades to {@link ObservationRegistry#NOOP}
 * and registry failures never propagate to user requests or worker turns.
 *
 * <p>Key-values must be low-cardinality {@code key=value} pairs from closed sets
 * (bounded enums, provider keys, policy versions). Identity, media, URLs, tokens, and
 * secrets never belong here (ADR-015); malformed pairs are rejected before any
 * observation is created. Callers drive the started observation through the
 * fail-safe {@link #keyValue}, {@link #error}, and {@link #stop} lifecycle
 * methods (stopping in a {@code finally} block); those swallow registry and
 * handler failures so observability can never interrupt a business path.
 */
public final class ObservationFacade {

	public static final String SHARE_RESOLVE = "share.resolve";
	public static final String VAULT_CREATE = "vault.create";
	public static final String GENERATION_QUEUE_WAIT = "generation.queue_wait";
	public static final String GENERATION_PROVIDER = "generation.provider";
	public static final String GENERATION_POLL = "generation.poll";
	public static final String GENERATION_QUALITY = "generation.quality";
	public static final String GENERATION_STORE = "generation.store";
	public static final String NOTIFICATION_SEND = "notification.send";

	private final ObservationRegistry registry;

	public ObservationFacade(ObservationRegistry registry) {
		this.registry = registry == null ? ObservationRegistry.NOOP : registry;
	}

	public Observation start(String name, String... keyValues) {
		requireValidName(name);
		String[][] pairs = splitPairs(keyValues);
		try {
			Observation observation = Observation.createNotStarted(name, this.registry);
			for (String[] pair : pairs) {
				observation = observation.lowCardinalityKeyValue(pair[0], pair[1]);
			}
			return observation.start();
		}
		catch (RuntimeException observabilityFailure) {
			// Observability must never fail a user request or worker turn.
			return Observation.createNotStarted(name, ObservationRegistry.NOOP).start();
		}
	}

	/**
	 * Fail-safe low-cardinality key-value on a started observation. A {@code null}
	 * observation or a null/blank key or value is a no-op; any observability
	 * failure is swallowed so this never throws into the caller's business path.
	 */
	public void keyValue(Observation observation, String key, String value) {
		if (observation == null || key == null || key.isBlank() || value == null || value.isBlank()) {
			return;
		}
		try {
			observation.lowCardinalityKeyValue(key, value);
		}
		catch (RuntimeException | Error observabilityFailure) {
			// Observability must never fail a user request or worker turn.
		}
	}

	/**
	 * Fail-safe error marker on a started observation. A {@code null} observation
	 * or {@code null} error is a no-op; any observability failure is swallowed so
	 * this never throws into the caller's business path.
	 */
	public void error(Observation observation, Throwable error) {
		if (observation == null || error == null) {
			return;
		}
		try {
			observation.error(error);
		}
		catch (RuntimeException | Error observabilityFailure) {
			// Observability must never fail a user request or worker turn.
		}
	}

	/**
	 * Fail-safe stop for a started observation. A {@code null} observation is a
	 * no-op; any observability failure is swallowed so stopping never throws into
	 * the caller's business path.
	 */
	public void stop(Observation observation) {
		if (observation == null) {
			return;
		}
		try {
			observation.stop();
		}
		catch (RuntimeException | Error observabilityFailure) {
			// Observability must never fail a user request or worker turn.
		}
	}

	private static void requireValidName(String name) {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("observation name must not be null or blank");
		}
	}

	private static String[][] splitPairs(String[] keyValues) {
		if (keyValues == null) {
			throw new IllegalArgumentException("keyValues must not be null");
		}
		String[][] pairs = new String[keyValues.length][];
		for (int index = 0; index < keyValues.length; index++) {
			pairs[index] = splitPair(keyValues[index], index);
		}
		return pairs;
	}

	private static String[] splitPair(String keyValue, int index) {
		if (keyValue == null) {
			throw new IllegalArgumentException("keyValues[" + index + "] must not be null");
		}
		int separator = keyValue.indexOf('=');
		if (separator < 0) {
			throw new IllegalArgumentException("keyValues[" + index + "] must be a key=value pair");
		}
		String key = keyValue.substring(0, separator);
		String value = keyValue.substring(separator + 1);
		if (key.isBlank()) {
			throw new IllegalArgumentException("keyValues[" + index + "] key must not be blank");
		}
		if (value.isBlank()) {
			throw new IllegalArgumentException("keyValues[" + index + "] value must not be blank");
		}
		return new String[] { key, value };
	}
}
