package com.aifashionvault.platform.logging;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryLoggingRedactionScanTest {

	private static final Pattern LOG_CALL = Pattern.compile(
		"\\b[A-Za-z0-9_]+\\s*\\.\\s*(?:info|warn|error|debug|trace)\\s*\\("
	);

	private static final Pattern SYSTEM_LOGGER_USAGE = Pattern.compile(
		"System\\.Logger|System\\.getLogger\\s*\\(|\\bLOG\\s*\\.\\s*log\\s*\\("
	);

	private static final Pattern BYTE_ARRAY_DECL = Pattern.compile(
		"(?:\\bbyte\\s*\\[\\s*\\]\\s*(?:@[A-Za-z0-9_.]+\\s+)*([A-Za-z0-9_]+)|\\bbyte\\s+([A-Za-z0-9_]+)\\s*\\[\\s*\\]|\\bbyte\\s*\\.\\.\\.\\s*([A-Za-z0-9_]+))"
	);

	private static final List<String> FORBIDDEN_NAMES = LoggingPolicy.NEVER_LOG_NAMES;

	private static final Pattern FORBIDDEN_VALUE_LITERAL = Pattern.compile(
		"data:image|/9j/|iVBORw0KGgo|X-Amz-Signature|X-Goog-Signature|Signature=",
		Pattern.CASE_INSENSITIVE
	);

	@Test
	void mainSourceLogCallsDoNotPassForbiddenNamesOrSensitiveValues() throws IOException {
		List<Path> javaFiles = findMainJavaFiles();
		assertThat(javaFiles).isNotEmpty();

		List<String> violations = new ArrayList<>();
		for (Path file : javaFiles) {
			scanFile(file, violations);
		}

		assertThat(violations).as("Forbidden logger call arguments found in main sources").isEmpty();
	}

	@Test
	void staticScanFailsOnForbiddenNameOrSensitiveValueInLoggerCall() {
		List<String> violations = new ArrayList<>();
		String badSource = "class Bad { void m() { log.info(\"got api_key={}\", secretKey); } }";
		scanSourceText("Bad.java", badSource, violations);
		assertThat(violations).isNotEmpty();
		assertThat(violations.getFirst()).contains("forbidden argument or name");

		List<String> violations2 = new ArrayList<>();
		String badSource2 = "class Bad2 { void m() { log.info(\"url={}\", \"http://evil?X-Amz-Signature=abc\"); } }";
		scanSourceText("Bad2.java", badSource2, violations2);
		assertThat(violations2).isNotEmpty();
	}

	@Test
	void mutationFixtureDetectsEachOfFifteenFrozenNeverLogNamesWhenUsedAsLoggerArgumentOrName() {
		assertThat(LoggingPolicy.NEVER_LOG_NAMES)
			.as("Frozen NeverLog names list must contain all 15 forbidden names")
			.hasSize(15);

		for (String forbidden : LoggingPolicy.NEVER_LOG_NAMES) {
			List<String> argViolations = new ArrayList<>();
			String badArgSource = "class MutationArg { void m() { log.info(\"processing\", " + forbidden + "); } }";
			scanSourceText("MutationArg.java", badArgSource, argViolations);
			assertThat(argViolations)
				.as("Static scan must detect forbidden name '%s' when passed as argument", forbidden)
				.isNotEmpty();
			assertThat(argViolations.getFirst()).contains(forbidden);

			List<String> keyViolations = new ArrayList<>();
			String badKeySource = "class MutationKey { void m() { log.info(\"" + forbidden + "={}\", value); } }";
			scanSourceText("MutationKey.java", badKeySource, keyViolations);
			assertThat(keyViolations)
				.as("Static scan must detect forbidden name '%s' in log message key", forbidden)
				.isNotEmpty();
			assertThat(keyViolations.getFirst()).contains(forbidden);
		}
	}

	@Test
	void mutationFixtureDetectsForbiddenCallsRegardlessOfLoggerVariableName() {
		List<String> auditViolations = new ArrayList<>();
		String auditSource = "class AuditViolation { void m() { audit.info(\"authentication failed: {}\", password); } }";
		scanSourceText("AuditViolation.java", auditSource, auditViolations);
		assertThat(auditViolations)
			.as("Static scan must detect password argument on audit.info call")
			.isNotEmpty();
		assertThat(auditViolations.getFirst()).contains("password");

		List<String> securityAuditViolations = new ArrayList<>();
		String secSource = "class SecAuditViolation { void m() { securityAudit.warn(\"payload={}\", value); } }";
		scanSourceText("SecAuditViolation.java", secSource, securityAuditViolations);
		assertThat(securityAuditViolations)
			.as("Static scan must detect payload on securityAudit.warn call")
			.isNotEmpty();
		assertThat(securityAuditViolations.getFirst()).contains("payload");
	}

	@Test
	void staticScanFailsOnSystemLoggerDeclarationOrCall() {
		List<String> declViolations = new ArrayList<>();
		String declSource = "class SystemLoggerDecl { private static final System.Logger LOG = System.getLogger(\"test\"); }";
		scanSourceText("SystemLoggerDecl.java", declSource, declViolations);
		assertThat(declViolations)
			.as("Static scan must detect System.Logger declaration or getLogger call")
			.isNotEmpty();
		assertThat(declViolations.getFirst()).contains("System.Logger");

		List<String> callViolations = new ArrayList<>();
		String callSource = "class SystemLoggerCall { void m() { LOG.log(System.Logger.Level.WARNING, \"warn\"); } }";
		scanSourceText("SystemLoggerCall.java", callSource, callViolations);
		assertThat(callViolations)
			.as("Static scan must detect LOG.log call")
			.isNotEmpty();
		assertThat(callViolations.getFirst()).contains("System.Logger");
	}

	@Test
	void staticScanFailsOnByteArrayVariablePassedToSlf4jCall() {
		List<String> violations = new ArrayList<>();
		String source = "class Bad { void m() { byte[] photo = new byte[10]; audit.info(\"upload={}\", photo); } }";
		scanSourceText("Bad.java", source, violations);
		assertThat(violations)
			.as("Static scan must catch byte[] photo passed to audit.info")
			.isNotEmpty();
		assertThat(violations.getFirst()).contains("passes byte[] variable 'photo'");

		List<String> exactReqViolations = new ArrayList<>();
		String exactReqSource = "byte[] photo; audit.info(\"upload={}\", photo)";
		scanSourceText("ExactReq.java", exactReqSource, exactReqViolations);
		assertThat(exactReqViolations)
			.as("Static scan must catch exact requirement sample: byte[] photo; audit.info(\"upload={}\", photo)")
			.isNotEmpty();
		assertThat(exactReqViolations.getFirst()).contains("passes byte[] variable 'photo'");

		List<String> innocuousParamViolations = new ArrayList<>();
		String paramSource = "class Innocuous { void logBuffer(byte[] buffer) { logger.warn(\"buffer status={}\", buffer); } }";
		scanSourceText("Innocuous.java", paramSource, innocuousParamViolations);
		assertThat(innocuousParamViolations)
			.as("Static scan must catch byte[] parameter with innocuous name 'buffer'")
			.anyMatch(v -> v.contains("passes byte[] variable 'buffer'"));

		List<String> harmlessViolations = new ArrayList<>();
		String harmlessSource = "class Harmless { void m(byte[] photo) { audit.info(\"photo size={}\", photo.length); } }";
		scanSourceText("Harmless.java", harmlessSource, harmlessViolations);
		assertThat(harmlessViolations)
			.as("Accessing photo.length does not pass the byte[] variable directly")
			.isEmpty();
	}

	private static void scanFile(Path file, List<String> violations) throws IOException {
		String source = Files.readString(file, StandardCharsets.UTF_8);
		scanSourceText(file.getFileName().toString(), source, violations);
	}

	private static void scanSourceText(String fileName, String source, List<String> violations) {
		Matcher systemLogger = SYSTEM_LOGGER_USAGE.matcher(source);
		while (systemLogger.find()) {
			violations.add(fileName + " contains forbidden System.Logger declaration or call bypassing Logback: " + systemLogger.group());
		}

		List<String> byteArrayVars = extractByteArrayVariables(source);

		Matcher logCall = LOG_CALL.matcher(source);
		while (logCall.find()) {
			int open = source.indexOf('(', logCall.start());
			String call = extractBalanced(source, open);
			checkCall(fileName, call, byteArrayVars, violations);
		}
	}

	private static List<String> extractByteArrayVariables(String source) {
		List<String> vars = new ArrayList<>();
		Matcher matcher = BYTE_ARRAY_DECL.matcher(source);
		while (matcher.find()) {
			String name = matcher.group(1);
			if (name == null) {
				name = matcher.group(2);
			}
			if (name == null) {
				name = matcher.group(3);
			}
			if (name != null && !name.isBlank() && !vars.contains(name)) {
				vars.add(name);
			}
		}
		return vars;
	}

	private static void checkCall(String fileName, String call, List<String> byteArrayVars, List<String> violations) {
		String lower = call.toLowerCase(Locale.ROOT);

		for (String forbidden : FORBIDDEN_NAMES) {
			// If call contains the forbidden parameter name or variable
			if (lower.contains(forbidden)) {
				violations.add(fileName + " log call contains forbidden argument or name: " + forbidden + " in: " + call);
			}
		}

		if (FORBIDDEN_VALUE_LITERAL.matcher(call).find()) {
			violations.add(fileName + " log call contains sensitive value literal in: " + call);
		}

		if (lower.contains("byte[") || lower.contains(".getbytes()")) {
			violations.add(fileName + " log call appears to pass raw bytes in: " + call);
		}

		List<String> args = parseCallArguments(call);
		for (String byteVar : byteArrayVars) {
			Pattern argPattern = Pattern.compile("^\\(*\\s*(?:this\\.)?" + Pattern.quote(byteVar) + "\\s*\\)*$");
			for (String arg : args) {
				if (argPattern.matcher(arg).matches()) {
					violations.add(fileName + " log call passes byte[] variable '" + byteVar + "' in: " + call);
					break;
				}
			}
		}
	}

	private static List<String> parseCallArguments(String call) {
		if (call == null || call.length() < 2 || !call.startsWith("(") || !call.endsWith(")")) {
			return List.of();
		}
		String inner = call.substring(1, call.length() - 1).trim();
		List<String> args = new ArrayList<>();
		int depthParen = 0;
		int depthBrace = 0;
		int depthBracket = 0;
		boolean inString = false;
		boolean escape = false;
		StringBuilder current = new StringBuilder();
		for (int i = 0; i < inner.length(); i++) {
			char c = inner.charAt(i);
			if (inString) {
				current.append(c);
				if (escape) {
					escape = false;
				}
				else if (c == '\\') {
					escape = true;
				}
				else if (c == '"') {
					inString = false;
				}
				continue;
			}
			if (c == '"') {
				inString = true;
				current.append(c);
				continue;
			}
			if (c == '(') {
				depthParen++;
				current.append(c);
			}
			else if (c == ')') {
				depthParen--;
				current.append(c);
			}
			else if (c == '{') {
				depthBrace++;
				current.append(c);
			}
			else if (c == '}') {
				depthBrace--;
				current.append(c);
			}
			else if (c == '[') {
				depthBracket++;
				current.append(c);
			}
			else if (c == ']') {
				depthBracket--;
				current.append(c);
			}
			else if (c == ',' && depthParen == 0 && depthBrace == 0 && depthBracket == 0) {
				String trimmed = current.toString().trim();
				if (!trimmed.isEmpty()) {
					args.add(trimmed);
				}
				current.setLength(0);
			}
			else {
				current.append(c);
			}
		}
		String trimmed = current.toString().trim();
		if (!trimmed.isEmpty()) {
			args.add(trimmed);
		}
		return args;
	}

	private static String extractBalanced(String source, int openParen) {
		if (openParen < 0 || openParen >= source.length()) {
			return "";
		}
		int depth = 0;
		boolean inString = false;
		boolean escape = false;
		for (int i = openParen; i < source.length(); i++) {
			char c = source.charAt(i);
			if (inString) {
				if (escape) {
					escape = false;
				}
				else if (c == '\\') {
					escape = true;
				}
				else if (c == '"') {
					inString = false;
				}
				continue;
			}
			if (c == '"') {
				inString = true;
				continue;
			}
			if (c == '(') {
				depth++;
			}
			else if (c == ')') {
				depth--;
				if (depth == 0) {
					return source.substring(openParen, i + 1);
				}
			}
		}
		return source.substring(openParen);
	}

	private static List<Path> findMainJavaFiles() throws IOException {
		List<Path> files = new ArrayList<>();
		Path mainDir = locate("src", "main", "java");
		try (Stream<Path> walk = Files.walk(mainDir)) {
			walk.filter(Files::isRegularFile)
				.filter(path -> path.getFileName().toString().endsWith(".java"))
				.forEach(files::add);
		}
		return List.copyOf(files);
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
}
