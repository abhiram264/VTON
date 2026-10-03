package com.aifashionvault.platform.generation.benchmark;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.aifashionvault.generation.benchmark.BenchmarkCaseResult;

/**
 * In-memory completed-result store implementing the benchmark
 * idempotency/resume contract: a stable (runId, caseId, configuration
 * fingerprint) identifies one completed result. Replaying an identical
 * completed case is a no-op, a conflicting stored result fails closed, and an
 * interrupted run resumes only unfinished cases. Offline artifact only; never
 * a production row.
 */
public final class BenchmarkResultStore {

	private final Map<String, StoredResult> completed = new LinkedHashMap<>();

	private record StoredResult(String configurationFingerprint, BenchmarkCaseResult result) {
	}

	private static String key(String runId, String caseId) {
		return runId.length() + ":" + runId + caseId;
	}

	public boolean isCompleted(String runId, String caseId, String configurationFingerprint) {
		StoredResult stored = completed.get(key(runId, caseId));
		return stored != null && stored.configurationFingerprint().equals(configurationFingerprint);
	}

	/**
	 * Records a completed case. Returns the stored result; when a result is
	 * already present under a different configuration fingerprint the call
	 * fails closed with an application-owned message.
	 */
	public BenchmarkCaseResult record(String runId, String caseId, String configurationFingerprint,
			BenchmarkCaseResult result) {
		String key = key(runId, caseId);
		StoredResult stored = completed.get(key);
		if (stored != null && !stored.configurationFingerprint().equals(configurationFingerprint)) {
			throw new IllegalStateException(
				"benchmark result conflict: " + key + " already completed under a different configuration");
		}
		if (stored == null) {
			completed.put(key, new StoredResult(configurationFingerprint, result));
			return result;
		}
		return stored.result();
	}

	public Optional<BenchmarkCaseResult> completedResult(String runId, String caseId) {
		StoredResult stored = completed.get(key(runId, caseId));
		return stored == null ? Optional.empty() : Optional.of(stored.result());
	}

	public List<BenchmarkCaseResult> completedResultsOrdered(List<String> orderedCaseIds, String runId) {
		return orderedCaseIds.stream()
			.map(caseId -> completedResult(runId, caseId))
			.flatMap(Optional::stream)
			.toList();
	}

	public void clear() {
		completed.clear();
	}
}
