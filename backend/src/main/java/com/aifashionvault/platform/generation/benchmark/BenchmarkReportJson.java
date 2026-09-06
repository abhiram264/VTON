package com.aifashionvault.platform.generation.benchmark;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.aifashionvault.generation.benchmark.BenchmarkReport;
import com.aifashionvault.generation.benchmark.BenchmarkRunSpec;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Privacy-safe JSON serialization of benchmark reports: per-case records, the
 * summary matrix, and configuration versions. Scalars, enums, and opaque ids
 * only — never image bytes, URLs, signed URLs, base64, prompt bodies,
 * exception bodies, user ids, credentials, or attractiveness/beauty fields
 * (ADR-015). Output is deterministic; no deployment decision is embedded.
 */
public final class BenchmarkReportJson {

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	private BenchmarkReportJson() {
	}

	public static String serialize(BenchmarkReport report, BenchmarkRunSpec spec) {
		List<Map<String, Object>> caseRecords = new ArrayList<>();
		for (var result : report.caseResults()) {
			Map<String, Object> record = new LinkedHashMap<>();
			record.put("runId", result.runId());
			record.put("caseId", result.caseId());
			record.put("benchmarkVersion", spec.benchmarkVersion());
			record.put("providerKey", result.providerKey());
			record.put("modelOrMode", result.modelOrMode());
			record.put("category", result.category());
			record.put("latencyMs", result.latencyMs());
			record.put("outcome", result.outcome().name());
			record.put("cost", result.cost());
			record.put("currency", result.currency());
			record.put("identityFidelity", result.identityFidelity());
			record.put("garmentFidelity", result.garmentFidelity());
			record.put("colorFidelity", result.colorFidelity());
			record.put("artifacts", result.artifacts());
			record.put("qualityGateResult",
					result.qualityGateResult() == null ? null : result.qualityGateResult().name());
			caseRecords.add(record);
		}
		List<Map<String, Object>> rows = new ArrayList<>();
		for (var row : report.matrixRows()) {
			Map<String, Object> record = new LinkedHashMap<>();
			record.put("providerKey", row.providerKey());
			record.put("modelOrMode", row.modelOrMode());
			record.put("category", row.category());
			record.put("fidelity", row.fidelity());
			record.put("p50LatencyMs", row.p50LatencyMs());
			record.put("failRate", row.failRate());
			record.put("costPerSuccess", row.costPerSuccess());
			record.put("sampleCount", row.sampleCount());
			record.put("successCount", row.successCount());
			// P50 sample size equals successCount by definition (see BenchmarkMetrics).
			record.put("p50SampleCount", row.successCount());
			rows.add(record);
		}
		Map<String, Object> root = new LinkedHashMap<>();
		root.put("benchmarkVersion", spec.benchmarkVersion());
		root.put("runId", spec.runId());
		root.put("providerKey", spec.providerKey());
		root.put("modelOrMode", spec.modelOrMode());
		root.put("promptVersion", spec.promptVersion());
		root.put("routingVersion", spec.routingVersion());
		root.put("caseResults", caseRecords);
		root.put("matrixRows", rows);
		root.put("fidelityMeaning",
				"mean of per-case identity/garment/color fidelity over quality-approved successes");
		root.put("p50Method", "nearest-rank median over quality-approved successes");
		return MAPPER.writeValueAsString(root);
	}

	/** Serializes a regression comparison; scalar deltas and case ids only, no deployment decision. */
	public static String serialize(BenchmarkRegressionComparator.RegressionReport regression) {
		List<Map<String, Object>> caseDeltas = new ArrayList<>();
		for (var delta : regression.caseDeltas()) {
			Map<String, Object> record = new LinkedHashMap<>();
			record.put("caseId", delta.caseId());
			record.put("category", delta.category());
			record.put("fidelityDelta", delta.fidelityDelta());
			record.put("fidelityDeltaPercent", delta.fidelityDeltaPercent());
			record.put("latencyDeltaMs", delta.latencyDeltaMs());
			record.put("baselineOutcome", delta.baselineOutcome().name());
			record.put("candidateOutcome", delta.candidateOutcome().name());
			caseDeltas.add(record);
		}
		List<Map<String, Object>> dimensionDeltas = new ArrayList<>();
		for (var delta : regression.dimensionDeltas()) {
			Map<String, Object> record = new LinkedHashMap<>();
			record.put("dimension", delta.dimension());
			record.put("category", delta.category());
			record.put("absolute", delta.absolute());
			record.put("percent", delta.percent());
			dimensionDeltas.add(record);
		}
		Map<String, Object> root = new LinkedHashMap<>();
		root.put("benchmarkVersion", regression.benchmarkVersion());
		root.put("caseDeltas", caseDeltas);
		root.put("dimensionDeltas", dimensionDeltas);
		root.put("missingInCandidate", regression.missingInCandidate());
		root.put("missingInBaseline", regression.missingInBaseline());
		root.put("notes", regression.notes());
		return MAPPER.writeValueAsString(root);
	}

	/** Serializes a release-gate decision; scalar findings only, no deployment automation. */
	public static String serialize(ReleaseGateDecision decision) {
		if (decision == null) {
			throw new IllegalArgumentException("decision must not be null");
		}
		List<Map<String, Object>> findings = new ArrayList<>();
		if (decision.findings() != null) {
			for (var finding : decision.findings()) {
				Map<String, Object> record = new LinkedHashMap<>();
				record.put("rule", finding.rule());
				record.put("dimension", finding.dimension());
				record.put("category", finding.category());
				record.put("severity", finding.severity());
				record.put("baselineValue", finding.baselineValue());
				record.put("candidateValue", finding.candidateValue());
				record.put("detail", finding.detail());
				findings.add(record);
			}
		}
		Map<String, Object> root = new LinkedHashMap<>();
		root.put("verdict", decision.verdict() == null ? null : decision.verdict().name());
		root.put("benchmarkVersion", decision.benchmarkVersion());
		root.put("matchedPairs", decision.matchedPairs());
		root.put("findings", findings);
		return MAPPER.writeValueAsString(root);
	}
}
