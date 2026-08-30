package com.aifashionvault.generation.benchmark;

import java.util.List;
import java.util.Set;

public record BenchmarkRunSpec(
	String runId,
	String benchmarkVersion,
	String providerKey,
	String modelOrMode,
	String promptVersion,
	String routingVersion,
	List<String> caseIds) {

	public BenchmarkRunSpec {
		BenchmarkContractRules.requireNonBlank(runId, "runId");
		BenchmarkContractRules.requireNonBlank(benchmarkVersion, "benchmarkVersion");
		BenchmarkContractRules.requireNonBlank(providerKey, "providerKey");
		BenchmarkContractRules.requireNonBlank(modelOrMode, "modelOrMode");
		BenchmarkContractRules.requireNonBlank(promptVersion, "promptVersion");
		BenchmarkContractRules.requireNonBlank(routingVersion, "routingVersion");
		if (caseIds == null || caseIds.isEmpty()) {
			throw new IllegalArgumentException("caseIds must contain at least one case id");
		}
		for (String caseId : caseIds) {
			if (caseId == null || caseId.isBlank()) {
				throw new IllegalArgumentException("caseIds must not contain blank ids");
			}
		}
		if (Set.copyOf(caseIds).size() != caseIds.size()) {
			throw new IllegalArgumentException("caseIds must not contain duplicates");
		}
		caseIds = List.copyOf(caseIds);
	}
}
