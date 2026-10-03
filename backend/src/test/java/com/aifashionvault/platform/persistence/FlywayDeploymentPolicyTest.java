package com.aifashionvault.platform.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayDeploymentPolicyTest {

	private static final Pattern MIGRATION_FILE_PATTERN = Pattern.compile("^V(\\d{3})__[a-z0-9_]+\\.sql$");
	private static final int PUBLISHED_CEILING_VERSION = 23;
	private static final int RESERVED_NEXT_VERSION = 26;
	private static final String CLAIMED_V024 = "V024__notification_fencing_and_provider_constraints__contract.sql";
	private static final String CLAIMED_V025 = "V025__device_registration.sql";
	private static final List<String> FORBIDDEN_FILE_PREFIXES = List.of("U", "R__", "B__");
	private static final Pattern DESTRUCTIVE_PATTERN = Pattern.compile(
		"(?is)\\b(?:DROP\\s+(?!INDEX(?:\\s+UNIQUE)?\\s+CONCURRENTLY\\b)[A-Z][A-Z0-9_ ]*|TRUNCATE\\b|ALTER\\s+TABLE\\b[^;]*?\\b(?:DROP|RENAME|ALTER\\s+COLUMN|SET\\s+DATA\\s+TYPE)\\b)");
	private static final Pattern CONCURRENT_INDEX_PATTERN = Pattern.compile(
		"(?i)(CREATE|DROP)(\\s+UNIQUE)?\\s+INDEX\\s+CONCURRENTLY");
	private static final Pattern ADD_COLUMN_NOT_NULL_PATTERN = Pattern.compile(
		"(?i)ADD\\s+COLUMN[^;]*NOT\\s+NULL");
	private static final String CONTRACT_DESCRIPTION_SUFFIX = "__contract";
	private static final Pattern CONTRACT_FILENAME_PATTERN = Pattern.compile(
		".*" + Pattern.quote(CONTRACT_DESCRIPTION_SUFFIX) + "\\.sql$");

	private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
	private static final Pattern CREATE_TABLE_STATEMENT = Pattern.compile("(?is)^\\s*CREATE\\s+TABLE\\b");
	private static final Pattern CONSTANT_DEFAULT_PATTERN = Pattern.compile(
		"(?is)\\bDEFAULT\\s+('(?:[^']|'')*'|[-+]?\\d+(?:\\.\\d+)?|TRUE|FALSE|NULL)"
			+ "(?:\\s*::\\s*[A-Z_][A-Z0-9_]*(?:\\s*\\[[^\\]]*\\])?)*"
			+ "(?=\\s+(?:NOT\\s+NULL|NULL|REFERENCES|CHECK|UNIQUE|PRIMARY\\s+KEY|COLLATE|GENERATED)\\b|[,)]|$)");

	@Test
	void migrationFilesFollowVersionedNamingOnly() throws IOException {
		Path migrations = migrationRoot();
		List<String> violations = new ArrayList<>();
		try (Stream<Path> stream = Files.list(migrations)) {
			List<Path> files = stream.filter(Files::isRegularFile).sorted().toList();
			for (Path file : files) {
				String filename = file.getFileName().toString();
				boolean forbidden = false;
				for (String prefix : FORBIDDEN_FILE_PREFIXES) {
					if (filename.startsWith(prefix)) {
						violations.add(filename + " has forbidden prefix '" + prefix + "' (rule 1)");
						forbidden = true;
						break;
					}
				}
				if (!forbidden && !MIGRATION_FILE_PATTERN.matcher(filename).matches()) {
					violations.add(filename + " does not match migration pattern " + MIGRATION_FILE_PATTERN.pattern() + " (rule 1)");
				}
			}
		}
		assertThat(violations)
			.as("All migration files must follow versioned naming only (rule 1)")
			.isEmpty();
	}

	@Test
	void migrationVersionsAreContiguousWithoutDuplicates() throws IOException {
		Path migrations = migrationRoot();
		List<Integer> parsedVersions = new ArrayList<>();
		List<String> violations = new ArrayList<>();
		try (Stream<Path> stream = Files.list(migrations)) {
			List<Path> files = stream.filter(Files::isRegularFile).sorted().toList();
			for (Path file : files) {
				String filename = file.getFileName().toString();
				Matcher matcher = MIGRATION_FILE_PATTERN.matcher(filename);
				if (matcher.matches()) {
					parsedVersions.add(Integer.parseInt(matcher.group(1)));
				}
			}
		}
		assertThat(parsedVersions)
			.as("Migration directory must contain migration files")
			.isNotEmpty();

		int maxVersion = parsedVersions.stream().max(Integer::compareTo).orElse(0);
		List<Integer> expected = IntStream.rangeClosed(1, maxVersion).boxed().toList();

		Set<Integer> seen = new HashSet<>();
		for (int v : parsedVersions) {
			if (!seen.add(v)) {
				violations.add("Duplicate migration version detected: V" + String.format("%03d", v) + " (rule 2)");
			}
		}

		List<Integer> sortedDistinct = parsedVersions.stream().distinct().sorted().toList();
		if (!sortedDistinct.equals(expected)) {
			List<Integer> missing = expected.stream().filter(v -> !seen.contains(v)).toList();
			violations.add("Missing migration versions in range 1.." + maxVersion + ": " + missing + " (rule 2)");
		}

		assertThat(violations)
			.as("Migration versions must be contiguous from 1 to " + maxVersion + " without duplicates (rule 2)")
			.isEmpty();
	}

	@Test
	void v024AndV025AreClaimedAndV026StaysReserved() throws IOException {
		Path migrations = migrationRoot();
		assertThat(migrations.resolve(CLAIMED_V024)).isRegularFile();
		assertThat(migrations.resolve(CLAIMED_V025)).isRegularFile();

		List<String> violations = new ArrayList<>();
		try (Stream<Path> stream = Files.list(migrations)) {
			List<Path> files = stream.filter(Files::isRegularFile).sorted().toList();
			for (Path file : files) {
				String filename = file.getFileName().toString();
				Matcher matcher = MIGRATION_FILE_PATTERN.matcher(filename);
				if (matcher.matches()) {
					int version = Integer.parseInt(matcher.group(1));
					if (version >= RESERVED_NEXT_VERSION) {
						violations.add(filename + " has version " + version + " >= RESERVED_NEXT_VERSION ("
							+ RESERVED_NEXT_VERSION + ") while reservation is outstanding (rule 3)");
					}
				}
			}
		}
		assertThat(violations)
			.as("No migration file with version >= " + RESERVED_NEXT_VERSION + " may exist while reservation is outstanding (rule 3)")
			.isEmpty();
	}

	@Test
	void concurrentIndexBuildsAreIsolatedToTheirOwnFile() throws IOException {
		Path migrations = migrationRoot();
		List<String> violations = new ArrayList<>();
		try (Stream<Path> stream = Files.list(migrations)) {
			List<Path> files = stream.filter(Files::isRegularFile).sorted().toList();
			for (Path file : files) {
				int version = extractVersion(file.getFileName().toString());
				if (version <= PUBLISHED_CEILING_VERSION) {
					continue;
				}
				String content = Files.readString(file, StandardCharsets.UTF_8);
				String stripped = stripSqlComments(content);
				if (CONCURRENT_INDEX_PATTERN.matcher(stripped).find()) {
					String[] statements = stripped.split(";");
					for (String stmt : statements) {
						String trimmed = stmt.trim();
						if (trimmed.isEmpty()) {
							continue;
						}
						if (!CONCURRENT_INDEX_PATTERN.matcher(trimmed).find()) {
							violations.add(file.getFileName() + " mixes concurrent index with non-concurrent statement: '" + trimmed + "' (rule 4)");
						}
					}
				}
			}
		}
		assertThat(violations)
			.as("Files matching CONCURRENT_INDEX_PATTERN must contain only concurrent index statements (rule 4)")
			.isEmpty();
	}

	@Test
	void notNullColumnAddsCarryConstantDefaults() throws IOException {
		Path migrations = migrationRoot();
		List<String> violations = new ArrayList<>();
		try (Stream<Path> stream = Files.list(migrations)) {
			List<Path> files = stream.filter(Files::isRegularFile).sorted().toList();
			for (Path file : files) {
				int version = extractVersion(file.getFileName().toString());
				if (version <= PUBLISHED_CEILING_VERSION) {
					continue;
				}
				String content = Files.readString(file, StandardCharsets.UTF_8);
				String stripped = stripSqlComments(content);
				String[] statements = stripped.split(";");
				for (String stmt : statements) {
					String trimmed = stmt.trim();
					if (trimmed.isEmpty()) {
						continue;
					}
					if (CREATE_TABLE_STATEMENT.matcher(trimmed).find()) {
						continue;
					}
					if (ADD_COLUMN_NOT_NULL_PATTERN.matcher(trimmed).find()
						&& !CONSTANT_DEFAULT_PATTERN.matcher(trimmed).find()) {
						violations.add(file.getFileName() + " adds NOT NULL column without a constant DEFAULT in statement: '" + trimmed + "' (rule 5)");
					}
				}
			}
		}
		assertThat(violations)
			.as("Statements adding NOT NULL columns must specify a constant DEFAULT outside CREATE TABLE (rule 5)")
			.isEmpty();
	}

	@Test
	void notNullDefaultPolicyAllowsOnlyConstants() {
		assertThat(CONSTANT_DEFAULT_PATTERN.matcher("ALTER TABLE t ADD COLUMN n bigint NOT NULL DEFAULT 0").find()).isTrue();
		assertThat(CONSTANT_DEFAULT_PATTERN.matcher("ALTER TABLE t ADD COLUMN s text DEFAULT 'pending' NOT NULL").find()).isTrue();
		assertThat(CONSTANT_DEFAULT_PATTERN.matcher("ALTER TABLE t ADD COLUMN n bigint NOT NULL DEFAULT now()").find()).isFalse();
		assertThat(CONSTANT_DEFAULT_PATTERN.matcher("ALTER TABLE t ADD COLUMN id uuid NOT NULL DEFAULT gen_random_uuid()").find()).isFalse();
	}

	@Test
	void destructiveStatementsAreContractTagged() throws IOException {
		Path migrations = migrationRoot();
		List<String> violations = new ArrayList<>();
		try (Stream<Path> stream = Files.list(migrations)) {
			List<Path> files = stream.filter(Files::isRegularFile).sorted().toList();
			for (Path file : files) {
				String filename = file.getFileName().toString();
				int version = extractVersion(filename);
				if (version <= PUBLISHED_CEILING_VERSION) {
					continue;
				}
				String content = Files.readString(file, StandardCharsets.UTF_8);
				String stripped = stripSqlComments(content);
				if (DESTRUCTIVE_PATTERN.matcher(stripped).find()
					&& !CONTRACT_FILENAME_PATTERN.matcher(filename).matches()) {
						violations.add(filename + " contains destructive statement matching " + DESTRUCTIVE_PATTERN.pattern()
							+ " but filename does not end with " + CONTRACT_DESCRIPTION_SUFFIX + ".sql (rule 6)");
					}
			}
		}
		assertThat(violations)
			.as("Future migrations with destructive statements must end in " + CONTRACT_DESCRIPTION_SUFFIX + ".sql (rule 6)")
			.isEmpty();
	}

	@Test
	void destructivePatternCoversPostgresForms() {
		List<String> forms = List.of(
			"DROP TABLE t",
			"DROP INDEX i",
			"DROP MATERIALIZED VIEW m",
			"DROP VIEW v",
			"DROP SEQUENCE s",
			"DROP SCHEMA s",
			"DROP DATABASE d",
			"DROP EXTENSION e",
			"DROP FUNCTION f()",
			"DROP PROCEDURE p()",
			"DROP ROUTINE r()",
			"DROP TRIGGER tr ON t",
			"DROP TYPE ty",
			"DROP DOMAIN dom",
			"DROP POLICY pol ON t",
			"DROP RULE rule ON t",
			"DROP STATISTICS stat",
			"DROP PUBLICATION pub",
			"DROP SUBSCRIPTION sub",
			"DROP ROLE role",
			"DROP USER user",
			"DROP OWNED BY role",
			"ALTER TABLE t DROP COLUMN c",
			"ALTER TABLE t DROP CONSTRAINT c",
			"ALTER TABLE t ALTER COLUMN c TYPE bigint",
			"ALTER TABLE t RENAME COLUMN c TO d",
			"TRUNCATE TABLE t");
		for (String form : forms) {
			assertThat(DESTRUCTIVE_PATTERN.matcher(form).find()).as(form).isTrue();
		}
		assertThat(DESTRUCTIVE_PATTERN.matcher("DROP INDEX CONCURRENTLY i").find()).isFalse();
	}

	@Test
	void applicationKeepsFlywayDefaults() throws IOException {
		Path appYaml = applicationYaml();
		assertThat(Files.isRegularFile(appYaml))
			.as("application.yaml must exist")
			.isTrue();
		String normalizedContent = Files.readString(appYaml, StandardCharsets.UTF_8)
			.toLowerCase(java.util.Locale.ROOT)
			.replace("-", "")
			.replace("_", "");
		assertThat(normalizedContent)
			.as("application.yaml must not enable out-of-order execution (rule 7)")
			.doesNotContain("outoforder");
		assertThat(normalizedContent)
			.as("application.yaml must not enable baseline-on-migrate (rule 7)")
			.doesNotContain("baselineonmigrate");
	}

	@Test
	void concurrentBuildsDeclareTransactionalLockDisabled() throws IOException {
		Path migrations = migrationRoot();
		boolean hasConcurrentMigration = false;
		try (Stream<Path> stream = Files.list(migrations)) {
			List<Path> files = stream.filter(Files::isRegularFile).sorted().toList();
			for (Path file : files) {
				int version = extractVersion(file.getFileName().toString());
				if (version <= PUBLISHED_CEILING_VERSION) {
					continue;
				}
				String content = Files.readString(file, StandardCharsets.UTF_8);
				String stripped = stripSqlComments(content);
				if (CONCURRENT_INDEX_PATTERN.matcher(stripped).find()) {
					hasConcurrentMigration = true;
					break;
				}
			}
		}
		if (hasConcurrentMigration) {
			Path appYaml = applicationYaml();
			String content = Files.readString(appYaml, StandardCharsets.UTF_8);
			assertThat(content)
				.as("application.yaml must declare 'transactional-lock: false' when concurrent index migrations exist (rule 8)")
				.contains("transactional-lock: false");
		}
	}

	private static int extractVersion(String filename) {
		Matcher matcher = MIGRATION_FILE_PATTERN.matcher(filename);
		if (matcher.matches()) {
			return Integer.parseInt(matcher.group(1));
		}
		return -1;
	}

	private static String stripSqlComments(String sql) {
		String withoutBlock = BLOCK_COMMENT.matcher(sql).replaceAll("");
		StringBuilder sb = new StringBuilder();
		for (String line : withoutBlock.split("\r?\n")) {
			if (!line.trim().startsWith("--")) {
				sb.append(line).append("\n");
			}
		}
		return sb.toString();
	}

	private static Path migrationRoot() {
		return locate("src", "main", "resources", "db", "migration");
	}

	private static Path applicationYaml() {
		return locate("src", "main", "resources", "application.yaml");
	}

	private static Path locate(String first, String... more) {
		Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
		Path current = cwd;
		while (current != null) {
			Path direct = current.resolve(Path.of(first, more));
			if (Files.exists(direct)) {
				return direct;
			}
			Path underBackend = current.resolve("backend").resolve(Path.of(first, more));
			if (Files.exists(underBackend)) {
				return underBackend;
			}
			current = current.getParent();
		}
		throw new IllegalStateException("Cannot locate " + Path.of(first, more) + " from " + cwd);
	}
}
