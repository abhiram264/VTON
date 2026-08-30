package com.aifashionvault.generation.benchmark;

public record BenchmarkCase(
	String caseId,
	String benchmarkVersion,
	String avatarFixtureId,
	String itemFixtureId,
	String category) {

	public BenchmarkCase {
		BenchmarkContractRules.requireNonBlank(caseId, "caseId");
		BenchmarkContractRules.requireNonBlank(benchmarkVersion, "benchmarkVersion");
		BenchmarkContractRules.requireNonBlank(avatarFixtureId, "avatarFixtureId");
		BenchmarkContractRules.requireNonBlank(itemFixtureId, "itemFixtureId");
		BenchmarkContractRules.requireNonBlank(category, "category");
	}
}
