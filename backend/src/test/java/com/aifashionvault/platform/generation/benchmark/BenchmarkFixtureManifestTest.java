package com.aifashionvault.platform.generation.benchmark;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Dry-run validation of the canonical benchmark manifest: exact 50-avatar /
 * 100-item cardinality, declared category counts summing to 100, licensing,
 * dimensions, reference confinement, checksums, and retirement — before any
 * provider call.
 */
class BenchmarkFixtureManifestTest {

	private static final Path MAIN = Path.of("src/main/resources/benchmark/manifest.json");
	private static final Path TEST = Path.of("src/test/resources/benchmark/manifest.json");

	@Test
	void canonicalManifestPassesDryRunWithNoMediaInViolations() {
		BenchmarkFixtureManifest manifest = BenchmarkFixtureManifest.open(MAIN);
		assertThat(manifest.benchmarkVersion()).isEqualTo("2026.01.0");
		assertThat(manifest.avatars()).hasSize(50);
		assertThat(manifest.items()).hasSize(100);
		assertThat(manifest.declaredCategoryCounts().values().stream().mapToLong(Long::longValue).sum())
			.isEqualTo(100);
		assertThat(manifest.declaredCategoryCounts()).containsOnlyKeys("tops", "bottoms", "dresses", "kurtas",
			"lehenga sets", "footwear", "headwear");
		assertThatCode(manifest::requireCanonical).doesNotThrowAnyException();
		assertThat(manifest.canonicalViolations()).isEmpty();
	}

	@Test
	void variantManifestFailsDryRunWithDistinctViolationCodes() {
		BenchmarkFixtureManifest manifest = BenchmarkFixtureManifest.open(TEST);
		List<String> violations = manifest.canonicalViolations();
		assertThat(violations).anySatisfy(code -> assertThat(code).startsWith("AVATAR_COUNT:4"));
		assertThat(violations).anySatisfy(code -> assertThat(code).startsWith("ITEM_COUNT:4"));
		assertThat(violations).anySatisfy(code -> assertThat(code).startsWith("FIXTURE_DUPLICATE:fx-avatar-001"));
		assertThat(violations).anySatisfy(code -> assertThat(code).startsWith("FIXTURE_MISSING:fx-avatar-missing"));
		assertThat(violations).anySatisfy(code -> assertThat(code).startsWith("FIXTURE_RETIRED:fx-avatar-retired"));
		assertThat(violations).anySatisfy(code -> assertThat(code).startsWith("FIXTURE_RETIRED:fx-item-retired"));
		assertThat(violations).anySatisfy(code -> assertThat(code).startsWith("FIXTURE_CHECKSUM:fx-item-checksum"));
		assertThat(violations).anySatisfy(code -> assertThat(code).startsWith("CATEGORY_TOTAL:4"));
		assertThatThrownBy(manifest::requireCanonical)
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("benchmark manifest not canonical");
		// No media bytes or file content ever appear in a violation.
		for (String violation : violations) {
			assertThat(violation).doesNotContain("base64").doesNotContain("http").matches("[A-Za-z0-9_:<>\\-./]+");
		}
	}

	@Test
	void perCaseValidationReportsDistinctCodesBeforeProviderInvocation() {
		BenchmarkFixtureManifest manifest = BenchmarkFixtureManifest.open(TEST);
		// no fully valid avatar pair exists: fx-avatar-001 is duplicated, so avatar
		// validation dominates every pair that names it
		assertThat(manifest.validateForCase("fx-avatar-001", "fx-item-001", "tops")).isEqualTo("AVATAR_DUPLICATE");
		// declared entry whose file is absent fails as FIXTURE_MISSING, before the provider
		assertThat(manifest.validateForCase("fx-avatar-missing", "fx-item-001", "tops"))
			.isEqualTo("FIXTURE_MISSING");
		// an id absent from the manifest entirely is unresolved
		assertThat(manifest.validateForCase("fx-avatar-nope", "fx-item-001", "tops"))
			.isEqualTo("AVATAR_UNRESOLVED");
		// retired fixture is rejected for new runs
		assertThat(manifest.validateForCase("fx-avatar-retired", "fx-item-001", "tops")).isEqualTo("FIXTURE_RETIRED");
		// item-level retirement/checksum/category codes are surfaced by the dry run
		// (canonicalViolations) and end-to-end by the runner fixture-invalid test
	}

	@Test
	void caseCatalogIdentityIsDeterministicAndOrderedSelectionIsHonored() {
		BenchmarkFixtureManifest manifest = BenchmarkFixtureManifest.open(TEST);
		BenchmarkCaseCatalog catalog = BenchmarkCaseCatalog.fromManifest(manifest);
		assertThat(catalog.allCasesOrdered()).hasSize(4 * 4);
		BenchmarkCaseCatalog rebuilt = BenchmarkCaseCatalog.fromManifest(BenchmarkFixtureManifest.open(TEST));
		assertThat(rebuilt.allCasesOrdered()).isEqualTo(catalog.allCasesOrdered());
		List<String> selected = List.of(catalog.allCasesOrdered().get(3).caseId(),
			catalog.allCasesOrdered().get(0).caseId());
		assertThat(catalog.casesForIds(selected))
			.extracting(com.aifashionvault.generation.benchmark.BenchmarkCase::caseId)
			.containsExactlyElementsOf(selected);
		assertThatThrownBy(() -> catalog.casesForIds(List.of("no-such-case")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("unknown benchmark case id");
	}
}
