package com.aifashionvault.platform.generation.benchmark;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.aifashionvault.generation.benchmark.BenchmarkCase;

/**
 * Deterministic benchmark case catalog: the Cartesian pairing of manifest
 * avatars and items in manifest order. Case identity is a pure function of
 * benchmark version, avatar fixture id, and item fixture id, so any consumer
 * derives the same catalog for the same manifest.
 */
public final class BenchmarkCaseCatalog {

	private final BenchmarkFixtureManifest manifest;
	private final Map<String, BenchmarkCase> byCaseId;

	private BenchmarkCaseCatalog(BenchmarkFixtureManifest manifest, Map<String, BenchmarkCase> byCaseId) {
		this.manifest = manifest;
		this.byCaseId = byCaseId;
	}

	public static BenchmarkCaseCatalog fromManifest(BenchmarkFixtureManifest manifest) {
		Map<String, BenchmarkCase> byCaseId = new LinkedHashMap<>();
		int avatarIndex = 0;
		for (BenchmarkFixtureManifest.FixtureEntry avatar : manifest.avatars()) {
			avatarIndex++;
			int itemIndex = 0;
			for (BenchmarkFixtureManifest.FixtureEntry item : manifest.items()) {
				itemIndex++;
				BenchmarkCase benchmarkCase = new BenchmarkCase(
					caseId(manifest.benchmarkVersion(), avatar.fixtureId(), item.fixtureId(), avatarIndex, itemIndex),
					manifest.benchmarkVersion(),
					avatar.fixtureId(),
					item.fixtureId(),
					item.category());
				byCaseId.put(benchmarkCase.caseId(), benchmarkCase);
			}
		}
		return new BenchmarkCaseCatalog(manifest, byCaseId);
	}

	static String caseId(String benchmarkVersion, String avatarFixtureId, String itemFixtureId, int avatarIndex,
			int itemIndex) {
		return benchmarkVersion + ":a" + avatarIndex + "-" + avatarFixtureId + ":i" + itemIndex + "-" + itemFixtureId;
	}

	public BenchmarkFixtureManifest manifest() {
		return manifest;
	}

	public Optional<BenchmarkCase> caseById(String caseId) {
		return Optional.ofNullable(byCaseId.get(caseId));
	}

	public List<BenchmarkCase> allCasesOrdered() {
		return List.copyOf(byCaseId.values());
	}

	public List<BenchmarkCase> casesForIds(List<String> caseIds) {
		List<BenchmarkCase> selected = new ArrayList<>(caseIds.size());
		for (String caseId : caseIds) {
			BenchmarkCase benchmarkCase = byCaseId.get(caseId);
			if (benchmarkCase == null) {
				throw new IllegalArgumentException("unknown benchmark case id: " + caseId);
			}
			selected.add(benchmarkCase);
		}
		return selected;
	}
}
