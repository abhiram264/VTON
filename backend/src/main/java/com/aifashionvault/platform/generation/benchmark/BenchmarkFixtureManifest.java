package com.aifashionvault.platform.generation.benchmark;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Offline benchmark fixture manifest: parses the U1 manifest, validates each
 * fixture (existence, SHA-256, PNG dimensions, retirement, reference
 * confinement, licensing metadata), and exposes the canonical dry-run checks
 * (exactly 50 avatars and 100 items with declared category counts that sum to
 * the item total).
 *
 * <p>All validation failures are reported as application-owned reason codes
 * plus opaque fixture ids; no file content, media, or provider data ever
 * enters a violation, message, or exception.
 */
public final class BenchmarkFixtureManifest {

	public enum Kind {
		AVATAR,
		ITEM
	}

	public record FixtureEntry(
		String fixtureId,
		Kind kind,
		String category,
		Path reference,
		String sha256,
		int minWidth,
		int minHeight,
		String license,
		String referenceSource,
		Instant activeFrom,
		Instant retiredAt) {
	}

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final Path root;
	private final String benchmarkVersion;
	private final Map<String, Long> declaredCategoryCounts;
	private final List<FixtureEntry> avatars;
	private final List<FixtureEntry> items;
	private final Map<String, List<FixtureEntry>> byFixtureId;

	private BenchmarkFixtureManifest(Path root, String benchmarkVersion, Map<String, Long> declaredCategoryCounts,
			List<FixtureEntry> avatars, List<FixtureEntry> items) {
		this.root = root;
		this.benchmarkVersion = benchmarkVersion;
		this.declaredCategoryCounts = Map.copyOf(declaredCategoryCounts);
		this.avatars = List.copyOf(avatars);
		this.items = List.copyOf(items);
		Map<String, List<FixtureEntry>> indexed = new LinkedHashMap<>();
		for (FixtureEntry entry : allEntries()) {
			indexed.computeIfAbsent(entry.fixtureId(), ignored -> new ArrayList<>()).add(entry);
		}
		this.byFixtureId = indexed;
	}

	public static BenchmarkFixtureManifest open(Path manifestFile) {
		Path normalized = manifestFile.toAbsolutePath().normalize();
		JsonNode root;
		try {
			root = MAPPER.readTree(normalized.toFile());
		}
		catch (RuntimeException ex) {
			throw new IllegalArgumentException("benchmark manifest unreadable: BENCHMARK_MANIFEST_MALFORMED");
		}
		if (root == null || !root.isObject() || isBlank(root.path("benchmarkVersion").asText())) {
			throw new IllegalArgumentException("benchmark manifest unreadable: BENCHMARK_MANIFEST_MALFORMED");
		}
		return new BenchmarkFixtureManifest(
			normalized.getParent(),
			root.path("benchmarkVersion").asText().strip(),
			parseCategoryCounts(root.path("categoryCounts")),
			parseEntries(root.path("avatars"), Kind.AVATAR),
			parseEntries(root.path("items"), Kind.ITEM));
	}

	private static Map<String, Long> parseCategoryCounts(JsonNode node) {
		Map<String, Long> counts = new LinkedHashMap<>();
		for (String name : node.propertyNames()) {
			JsonNode value = node.path(name);
			if (isBlank(name) || !value.canConvertToLong() || value.asLong() < 1) {
				throw new IllegalArgumentException("benchmark manifest unreadable: CATEGORY_COUNTS_MALFORMED");
			}
			counts.put(name.strip(), value.asLong());
		}
		if (counts.isEmpty()) {
			throw new IllegalArgumentException("benchmark manifest unreadable: CATEGORY_COUNTS_MALFORMED");
		}
		return counts;
	}

	private static List<FixtureEntry> parseEntries(JsonNode array, Kind expectedKind) {
		if (array == null || !array.isArray()) {
			throw new IllegalArgumentException("benchmark manifest unreadable: BENCHMARK_MANIFEST_MALFORMED");
		}
		List<FixtureEntry> entries = new ArrayList<>();
		for (JsonNode node : array) {
			entries.add(parseEntry(node, expectedKind));
		}
		return entries;
	}

	private static FixtureEntry parseEntry(JsonNode node, Kind expectedKind) {
		String fixtureId = requiredText(node, "fixtureId");
		Kind kind;
		try {
			kind = Kind.valueOf(requiredText(node, "kind"));
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException("benchmark manifest unreadable: KIND_MALFORMED");
		}
		if (kind != expectedKind) {
			throw new IllegalArgumentException("benchmark manifest unreadable: KIND_MISMATCH");
		}
		JsonNode categoryNode = node.path("category");
		String category = categoryNode.isMissingNode() || categoryNode.isNull() || isBlank(categoryNode.asText())
			? null
			: categoryNode.asText().strip();
		String reference = requiredText(node, "reference");
		if (!reference.startsWith("fixtures/") || reference.contains("..")) {
			throw new IllegalArgumentException("benchmark manifest unreadable: REFERENCE_UNCONFINED");
		}
		String sha256 = requiredText(node, "sha256");
		if (!sha256.matches("sha256-[0-9a-f]{64}")) {
			throw new IllegalArgumentException("benchmark manifest unreadable: SHA256_MALFORMED");
		}
		return new FixtureEntry(
			fixtureId,
			kind,
			category,
			Path.of(reference),
			sha256,
			requiredInt(node, "minWidth"),
			requiredInt(node, "minHeight"),
			requiredText(node, "license"),
			requiredText(node, "referenceSource"),
			parseInstant(node, "activeFrom", true),
			parseInstant(node, "retiredAt", false));
	}

	private static String requiredText(JsonNode node, String field) {
		String value = node.path(field).asText(null);
		if (isBlank(value)) {
			throw new IllegalArgumentException(
				"benchmark manifest unreadable: " + field.toUpperCase(Locale.ROOT) + "_MISSING");
		}
		return value.strip();
	}

	private static int requiredInt(JsonNode node, String field) {
		JsonNode value = node.path(field);
		if (!value.canConvertToInt() || value.asInt() < 1) {
			throw new IllegalArgumentException(
				"benchmark manifest unreadable: " + field.toUpperCase(Locale.ROOT) + "_MISSING");
		}
		return value.asInt();
	}

	private static Instant parseInstant(JsonNode node, String field, boolean required) {
		JsonNode value = node.path(field);
		if (value.isMissingNode() || value.isNull() || isBlank(value.asText())) {
			if (required) {
				throw new IllegalArgumentException(
					"benchmark manifest unreadable: " + field.toUpperCase(Locale.ROOT) + "_MISSING");
			}
			return null;
		}
		try {
			return Instant.parse(value.asText().strip());
		}
		catch (DateTimeParseException ex) {
			throw new IllegalArgumentException(
				"benchmark manifest unreadable: " + field.toUpperCase(Locale.ROOT) + "_MALFORMED");
		}
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	public String benchmarkVersion() {
		return benchmarkVersion;
	}

	public Map<String, Long> declaredCategoryCounts() {
		return declaredCategoryCounts;
	}

	public List<FixtureEntry> avatars() {
		return avatars;
	}

	public List<FixtureEntry> items() {
		return items;
	}

	public List<FixtureEntry> allEntries() {
		List<FixtureEntry> all = new ArrayList<>(avatars.size() + items.size());
		all.addAll(avatars);
		all.addAll(items);
		return all;
	}

	public List<FixtureEntry> entriesWithId(String fixtureId) {
		return byFixtureId.getOrDefault(fixtureId, List.of());
	}

	public Optional<FixtureEntry> uniqueEntry(String fixtureId) {
		List<FixtureEntry> entries = entriesWithId(fixtureId);
		return entries.size() == 1 ? Optional.of(entries.get(0)) : Optional.empty();
	}

	public Path resolve(FixtureEntry entry) {
		return root.resolve(entry.reference()).normalize();
	}

	/**
	 * Canonical dry-run validation. Returns application-owned violation codes;
	 * an empty list means the manifest satisfies the canonical cardinality
	 * (50 avatars, 100 items), declared category counts, licensing, dimension,
	 * checksum, and retirement requirements.
	 */
	public List<String> canonicalViolations() {
		List<String> violations = new ArrayList<>();
		if (avatars.size() != 50) {
			violations.add("AVATAR_COUNT:" + avatars.size());
		}
		if (items.size() != 100) {
			violations.add("ITEM_COUNT:" + items.size());
		}
		long declaredTotal = declaredCategoryCounts.values().stream().mapToLong(Long::longValue).sum();
		if (declaredTotal != 100) {
			violations.add("CATEGORY_TOTAL:" + declaredTotal);
		}
		Map<String, Long> actualCounts = new LinkedHashMap<>();
		for (FixtureEntry item : items) {
			actualCounts.merge(item.category() == null ? "<missing>" : item.category(), 1L, Long::sum);
		}
		for (Map.Entry<String, Long> declared : declaredCategoryCounts.entrySet()) {
			Long actual = actualCounts.get(declared.getKey());
			if (actual == null) {
				violations.add("CATEGORY_COUNT_MISSING:" + declared.getKey());
			}
			else if (!actual.equals(declared.getValue())) {
				violations.add("CATEGORY_COUNT_MISMATCH:" + declared.getKey());
			}
		}
		for (String category : actualCounts.keySet()) {
			if (!declaredCategoryCounts.containsKey(category)) {
				violations.add("CATEGORY_COUNT_UNDECLARED:" + category);
			}
		}
		for (Map.Entry<String, List<FixtureEntry>> indexed : byFixtureId.entrySet()) {
			if (indexed.getValue().size() > 1) {
				violations.add("FIXTURE_DUPLICATE:" + indexed.getKey());
			}
		}
		for (FixtureEntry avatar : avatars) {
			if (avatar.category() != null) {
				violations.add("AVATAR_CATEGORY_PRESENT:" + avatar.fixtureId());
			}
		}
		for (FixtureEntry item : items) {
			if (item.category() == null
				|| !item.reference().startsWith(Path.of("fixtures", "items", item.category()).toString())) {
				violations.add("CATEGORY_MISMATCH:" + item.fixtureId());
			}
		}
		for (FixtureEntry entry : allEntries()) {
			String violation = fixtureViolation(entry);
			if (violation != null) {
				violations.add(violation + ":" + entry.fixtureId());
			}
		}
		return violations;
	}

	/** Dry-run gate: throws with violation codes when the manifest is not canonical. */
	public void requireCanonical() {
		List<String> violations = canonicalViolations();
		if (!violations.isEmpty()) {
			throw new IllegalStateException("benchmark manifest not canonical: " + String.join(", ", violations));
		}
	}

	/**
	 * Validates the two fixtures of one benchmark case before any provider
	 * invocation. Returns {@code null} when the pair is runnable, otherwise a
	 * single application-owned violation code.
	 */
	public String validateForCase(String avatarFixtureId, String itemFixtureId, String category) {
		List<FixtureEntry> avatarEntries = entriesWithId(avatarFixtureId);
		if (avatarEntries.isEmpty()) {
			return "AVATAR_UNRESOLVED";
		}
		if (avatarEntries.size() > 1) {
			return "AVATAR_DUPLICATE";
		}
		List<FixtureEntry> itemEntries = entriesWithId(itemFixtureId);
		if (itemEntries.isEmpty()) {
			return "ITEM_UNRESOLVED";
		}
		if (itemEntries.size() > 1) {
			return "ITEM_DUPLICATE";
		}
		FixtureEntry avatar = avatarEntries.get(0);
		FixtureEntry item = itemEntries.get(0);
		if (avatar.kind() != Kind.AVATAR || item.kind() != Kind.ITEM) {
			return "KIND_MISMATCH";
		}
		if (item.category() == null || !item.category().equals(category)
			|| !item.reference().startsWith(Path.of("fixtures", "items", item.category()).toString())) {
			return "CATEGORY_MISMATCH";
		}
		String violation = fixtureViolation(avatar);
		if (violation != null) {
			return violation;
		}
		return fixtureViolation(item);
	}

	/**
	 * Per-fixture checks shared by the dry run and per-case validation:
	 * retirement, licensing metadata, existence, SHA-256, and minimum PNG
	 * dimensions. Returns a violation code or {@code null}.
	 */
	private String fixtureViolation(FixtureEntry entry) {
		if (entry.retiredAt() != null) {
			return "FIXTURE_RETIRED";
		}
		if (isBlank(entry.license()) || isBlank(entry.referenceSource())) {
			return "LICENSE_MISSING";
		}
		Path file = resolve(entry);
		if (!Files.isRegularFile(file)) {
			return "FIXTURE_MISSING";
		}
		byte[] bytes;
		try {
			bytes = Files.readAllBytes(file);
		}
		catch (IOException ex) {
			return "FIXTURE_UNREADABLE";
		}
		if (!sha256Hex(bytes).equals(entry.sha256().substring("sha256-".length()))) {
			return "FIXTURE_CHECKSUM";
		}
		if (pngWidth(bytes) < entry.minWidth() || pngHeight(bytes) < entry.minHeight()) {
			return "FIXTURE_DIMENSIONS";
		}
		return null;
	}

	public byte[] readFixture(FixtureEntry entry) {
		try {
			return Files.readAllBytes(resolve(entry));
		}
		catch (IOException ex) {
			throw new IllegalStateException("benchmark fixture unreadable: FIXTURE_UNREADABLE");
		}
	}

	static String sha256Hex(byte[] bytes) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
			return String.format("%064x", new BigInteger(1, digest));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 unavailable", ex);
		}
	}

	static int pngWidth(byte[] bytes) {
		return pngDimension(bytes, 16);
	}

	static int pngHeight(byte[] bytes) {
		return pngDimension(bytes, 20);
	}

	private static int pngDimension(byte[] bytes, int offset) {
		if (bytes.length < 29
			|| (bytes[0] & 0xFF) != 0x89 || bytes[1] != 'P' || bytes[2] != 'N' || bytes[3] != 'G'
			|| (bytes[4] & 0xFF) != 0x0D || bytes[5] != 0x0A
			|| (bytes[6] & 0xFF) != 0x1A || bytes[7] != 0x0A
			|| (bytes[8] & 0xFF) != 0x00 || (bytes[9] & 0xFF) != 0x00
			|| (bytes[10] & 0xFF) != 0x00 || (bytes[11] & 0xFF) != 0x0D
			|| bytes[12] != 'I' || bytes[13] != 'H' || bytes[14] != 'D' || bytes[15] != 'R') {
			return -1;
		}
		return (bytes[offset] & 0xFF) << 24 | (bytes[offset + 1] & 0xFF) << 16
			| (bytes[offset + 2] & 0xFF) << 8 | (bytes[offset + 3] & 0xFF);
	}

	static String fingerprint(String material) {
		return sha256Hex(material.getBytes(StandardCharsets.UTF_8));
	}
}
