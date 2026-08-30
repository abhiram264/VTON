package com.aifashionvault;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DomainArchitectureTest {

	private static final String ROOT_PACKAGE = "com.aifashionvault";

	private static final List<String> DOMAIN_PACKAGES = List.of(
		"shared",
		"user",
		"avatar",
		"importer",
		"vault",
		"collection",
		"studio",
		"generation",
		"look",
		"notification",
		"privacy");

	private static final List<String> FEATURE_MODULES = List.of(
		"user",
		"avatar",
		"importer",
		"vault",
		"collection",
		"studio",
		"generation",
		"look",
		"notification",
		"privacy");

	private static final Pattern JPA_OR_ENTITY = Pattern.compile(
		"jakarta\\.persistence\\.Entity|javax\\.persistence\\.Entity"
			+ "|org\\.springframework\\.data\\.jpa|\\bJpaRepository\\b"
			+ "|\\bEnableJpaRepositories\\b|@Entity\\b");

	private static final Pattern REST_CONTROLLER = Pattern.compile(
		"@RestController\\b|org\\.springframework\\.web\\.bind\\.annotation\\.RestController");

	private static final Pattern REDIS_TYPE = Pattern.compile(
		"org\\.springframework\\.data\\.redis|\\bredis\\.clients\\b|\\bio\\.lettuce\\b"
			+ "|\\borg\\.redisson\\b|\\bRedis[A-Za-z0-9_]*\\b|\\bJedis[A-Za-z0-9_]*\\b"
			+ "|\\bRedisson[A-Za-z0-9_]*\\b");

	private static final Pattern FASHN_TYPE_DECLARATION = Pattern.compile(
		"\\b(?:class|interface|enum|record)\\s+Fashn\\w*");

	private static final Pattern ORG_OR_TENANT_ID = Pattern.compile(
		"\\borgId\\b|\\btenantId\\b|\\borg_id\\b|\\btenant_id\\b",
		Pattern.CASE_INSENSITIVE);

	private static final Pattern IMPORT_LINE = Pattern.compile(
		"^\\s*import\\s+(?:static\\s+)?(" + Pattern.quote(ROOT_PACKAGE) + "(?:\\.\\w+)*)");

	private static final Pattern FLYWAY_VERSION = Pattern.compile("^[Vv](\\d+(?:_\\d+)*)__");

	@Test
	void sourceHasNoSpringDataJpaOrPersistenceEntity() throws IOException {
		assertThat(scanJava((root, file, line) -> matchLabel(line, JPA_OR_ENTITY, "Spring Data JPA / jakarta.persistence.Entity")))
			.isEmpty();
	}

	@Test
	void domainPackagesHaveNoRestController() throws IOException {
		assertThat(scanJava((root, file, line) -> {
			if (!isDomainFile(root, file)) {
				return Optional.empty();
			}
			return matchLabel(line, REST_CONTROLLER, "@RestController");
		})).isEmpty();
	}

	@Test
	void sourceHasNoRedisTypes() throws IOException {
		assertThat(scanJava((root, file, line) -> matchLabel(line, REDIS_TYPE, "Redis type")))
			.isEmpty();
	}

	@Test
	void domainPackagesHaveNoFashnTypes() throws IOException {
		Path javaRoot = javaSourceRoot();
		List<String> violations = new ArrayList<>();
		for (Path file : javaFiles(javaRoot)) {
			if (!isDomainFile(javaRoot, file)) {
				continue;
			}
			if (file.getFileName().toString().startsWith("Fashn")
				&& file.getFileName().toString().endsWith(".java")) {
				violations.add(display(javaRoot, file) + " Fashn* type");
			}
			violations.addAll(scanFile(javaRoot, file, (root, path, line) ->
				matchLabel(line, FASHN_TYPE_DECLARATION, "Fashn* type")));
		}
		assertThat(violations).isEmpty();
	}

	@Test
	void flywayAllowsOnlyTheFrozenV019MigrationAbovePublishedV017() throws IOException {
		Path migrations = migrationRoot();
		List<String> violations = new ArrayList<>();
		if (Files.isDirectory(migrations)) {
			try (Stream<Path> walk = Files.walk(migrations)) {
				walk.filter(Files::isRegularFile).forEach(file -> {
					String name = file.getFileName().toString();
					if (isNewerThanV017(name) && !name.equals("V018__single_item_manual_product.sql")
						&& !name.equals("V019__share_import_resolver.sql")) {
						violations.add(display(migrations, file) + " unexpected V019+ Flyway file");
					}
				});
			}
		}
		assertThat(violations).isEmpty();
	}

	@Test
	void sourceHasNoOrgIdOrTenantId() throws IOException {
		List<String> violations = new ArrayList<>();
		violations.addAll(scanJava((root, file, line) ->
			matchLabel(line, ORG_OR_TENANT_ID, "orgId/tenantId")));
		Path migrations = migrationRoot();
		if (Files.isDirectory(migrations)) {
			try (Stream<Path> walk = Files.walk(migrations)) {
				for (Path file : walk.filter(Files::isRegularFile).toList()) {
					violations.addAll(scanFile(migrations, file, (root, path, line) ->
						matchLabel(line, ORG_OR_TENANT_ID, "orgId/tenantId")));
				}
			}
		}
		assertThat(violations).isEmpty();
	}

	@Test
	void featureModulesDependOnlyOnSharedWhenPresent() throws IOException {
		Path javaRoot = javaSourceRoot();
		List<String> violations = new ArrayList<>();
		for (Path file : javaFiles(javaRoot)) {
			Optional<String> module = featureModuleOf(javaRoot, file);
			if (module.isEmpty()) {
				continue;
			}
			List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i);
				if (isCommentLine(line)) {
					continue;
				}
				Matcher matcher = IMPORT_LINE.matcher(line);
				if (!matcher.find()) {
					continue;
				}
				Optional<String> imported = featureModuleName(matcher.group(1));
				if (imported.isPresent() && !imported.get().equals(module.get())) {
					violations.add(display(javaRoot, file) + ":" + (i + 1)
						+ " feature module '" + module.get()
						+ "' depends on sibling '" + imported.get() + "'");
				}
			}
		}
		assertThat(violations).isEmpty();
	}

	private static List<String> scanJava(LineCheck check) throws IOException {
		Path javaRoot = javaSourceRoot();
		List<String> violations = new ArrayList<>();
		for (Path file : javaFiles(javaRoot)) {
			violations.addAll(scanFile(javaRoot, file, check));
		}
		return violations;
	}

	private static List<String> scanFile(Path root, Path file, LineCheck check) throws IOException {
		List<String> violations = new ArrayList<>();
		List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			if (isCommentLine(line)) {
				continue;
			}
			int lineNumber = i + 1;
			check.match(root, file, line).ifPresent(label ->
				violations.add(display(root, file) + ":" + lineNumber + " " + label));
		}
		return violations;
	}

	private static Optional<String> matchLabel(String line, Pattern pattern, String label) {
		return pattern.matcher(line).find() ? Optional.of(label) : Optional.empty();
	}

	private static boolean isCommentLine(String line) {
		String trimmed = line.trim();
		return trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*");
	}

	private static boolean isDomainFile(Path javaRoot, Path file) {
		return domainPackageOf(packageFromPath(javaRoot, file)).isPresent();
	}

	private static Optional<String> featureModuleOf(Path javaRoot, Path file) {
		return featureModuleName(packageFromPath(javaRoot, file));
	}

	private static Optional<String> domainPackageOf(String pkg) {
		for (String name : DOMAIN_PACKAGES) {
			String prefix = ROOT_PACKAGE + "." + name;
			if (pkg.equals(prefix) || pkg.startsWith(prefix + ".")) {
				return Optional.of(name);
			}
		}
		return Optional.empty();
	}

	private static Optional<String> featureModuleName(String pkg) {
		for (String name : FEATURE_MODULES) {
			String prefix = ROOT_PACKAGE + "." + name;
			if (pkg.equals(prefix) || pkg.startsWith(prefix + ".")) {
				return Optional.of(name);
			}
		}
		return Optional.empty();
	}

	private static String packageFromPath(Path javaRoot, Path file) {
		Path relative = javaRoot.relativize(file.getParent());
		if (relative.getNameCount() == 1 && relative.getFileName().toString().isEmpty()) {
			return "";
		}
		return relative.toString().replace('\\', '/').replace('/', '.');
	}

	private static boolean isNewerThanV017(String filename) {
		Matcher matcher = FLYWAY_VERSION.matcher(filename);
		if (!matcher.find()) {
			return false;
		}
		String[] parts = matcher.group(1).split("_");
		int major = Integer.parseInt(parts[0]);
		if (major > 17) {
			return true;
		}
		if (major < 17) {
			return false;
		}
		for (int i = 1; i < parts.length; i++) {
			if (Integer.parseInt(parts[i]) > 0) {
				return true;
			}
		}
		return false;
	}

	private static List<Path> javaFiles(Path root) throws IOException {
		if (!Files.isDirectory(root)) {
			return List.of();
		}
		try (Stream<Path> walk = Files.walk(root)) {
			return walk
				.filter(Files::isRegularFile)
				.filter(path -> path.getFileName().toString().endsWith(".java"))
				.toList();
		}
	}

	private static String display(Path root, Path file) {
		return root.relativize(file).toString().replace('\\', '/');
	}

	private static Path javaSourceRoot() {
		return locate("src", "main", "java");
	}

	private static Path migrationRoot() {
		return locate("src", "main", "resources", "db", "migration");
	}

	private static Path locate(String first, String... more) {
		Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
		Path current = cwd;
		while (current != null) {
			Path direct = current.resolve(Path.of(first, more));
			if (Files.isDirectory(direct)) {
				return direct;
			}
			Path underBackend = current.resolve("backend").resolve(Path.of(first, more));
			if (Files.isDirectory(underBackend)) {
				return underBackend;
			}
			current = current.getParent();
		}
		throw new IllegalStateException("Cannot locate " + Path.of(first, more) + " from " + cwd);
	}

	@FunctionalInterface
	private interface LineCheck {
		Optional<String> match(Path root, Path file, String line);
	}
}
