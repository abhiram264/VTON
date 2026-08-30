package com.aifashionvault.generation.benchmark;

import java.util.List;

public record BenchmarkReport(
	List<BenchmarkCaseResult> caseResults,
	List<BenchmarkMatrixRow> matrixRows) {

	public BenchmarkReport {
		if (caseResults == null) {
			throw new IllegalArgumentException("caseResults is required");
		}
		if (matrixRows == null) {
			throw new IllegalArgumentException("matrixRows is required");
		}
		caseResults = List.copyOf(caseResults);
		matrixRows = List.copyOf(matrixRows);
	}
}
