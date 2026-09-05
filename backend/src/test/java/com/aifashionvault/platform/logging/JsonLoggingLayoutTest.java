package com.aifashionvault.platform.logging;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class JsonLoggingLayoutTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private JsonLoggingLayout layout;
	private LoggerContext context;
	private Logger logger;

	@BeforeEach
	void setUp() {
		layout = new JsonLoggingLayout();
		context = new LoggerContext();
		logger = context.getLogger("com.aifashionvault.test.TestLogger");
	}

	@Test
	void rendersValidJsonObjectWithExactOrderedAllowlistKeys() throws Exception {
		Map<String, String> mdc = new HashMap<>();
		mdc.put("request_id", "11111111-1111-1111-1111-111111111111");
		mdc.put("user_id", "22222222-2222-2222-2222-222222222222");
		mdc.put("generation_id", "33333333-3333-3333-3333-333333333333");
		mdc.put("provider", "fashn");
		mdc.put("status", "SUCCEEDED");
		mdc.put("latency_ms", "450");
		mdc.put("error_code", "NONE");

		LoggingEvent event = createEvent(Level.INFO, "test message", mdc);
		String line = layout.doLayout(event);

		assertThat(line).endsWith(System.lineSeparator());
		String trimmed = line.trim();
		assertThat(trimmed).doesNotContain("\n").doesNotContain("\r");

		@SuppressWarnings("unchecked")
		Map<String, Object> json = MAPPER.readValue(trimmed, LinkedHashMap.class);

		assertThat(json.keySet()).containsExactly(
			"ts",
			"level",
			"logger",
			"msg",
			"request_id",
			"user_id",
			"generation_id",
			"provider",
			"status",
			"latency_ms",
			"error_code"
		);

		assertThat((String) json.get("ts")).matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*Z$");
		assertThat(json.get("level")).isEqualTo("INFO");
		assertThat(json.get("logger")).isEqualTo("com.aifashionvault.test.TestLogger");
		assertThat(json.get("msg")).isEqualTo("test message");
		assertThat(json.get("request_id")).isEqualTo("11111111-1111-1111-1111-111111111111");
		assertThat(json.get("user_id")).isEqualTo("22222222-2222-2222-2222-222222222222");
		assertThat(json.get("generation_id")).isEqualTo("33333333-3333-3333-3333-333333333333");
		assertThat(json.get("provider")).isEqualTo("fashn");
		assertThat(json.get("status")).isEqualTo("SUCCEEDED");
		assertThat(json.get("latency_ms")).isInstanceOf(Number.class);
		assertThat(((Number) json.get("latency_ms")).longValue()).isEqualTo(450L);
		assertThat(json.get("error_code")).isEqualTo("NONE");
	}

	@Test
	void omitsAbsentKeysAndEmptyValues() throws Exception {
		Map<String, String> mdc = new HashMap<>();
		mdc.put("request_id", "11111111-1111-1111-1111-111111111111");
		mdc.put("user_id", "");
		mdc.put("generation_id", "   ");
		// latency_ms absent, error_code absent, status absent, provider absent

		LoggingEvent event = createEvent(Level.WARN, "warning msg", mdc);
		String line = layout.doLayout(event);

		@SuppressWarnings("unchecked")
		Map<String, Object> json = MAPPER.readValue(line.trim(), LinkedHashMap.class);
		assertThat(json.containsKey("user_id")).isFalse();
		assertThat(json.containsKey("generation_id")).isFalse();
		assertThat(json.containsKey("provider")).isFalse();
		assertThat(json.containsKey("status")).isFalse();
		assertThat(json.containsKey("latency_ms")).isFalse();
		assertThat(json.containsKey("error_code")).isFalse();
		assertThat(json.containsKey("request_id")).isTrue();
		assertThat(json.get("request_id")).isEqualTo("11111111-1111-1111-1111-111111111111");
	}

	@Test
	void latencyMsParsesAsNonNegativeNumberAndOmitsNegativeOrInvalid() throws Exception {
		Map<String, String> mdc = new HashMap<>();
		mdc.put("latency_ms", "0");
		LoggingEvent event0 = createEvent(Level.INFO, "msg", mdc);
		@SuppressWarnings("unchecked")
		Map<String, Object> json0 = MAPPER.readValue(layout.doLayout(event0).trim(), LinkedHashMap.class);
		assertThat(json0.get("latency_ms")).isInstanceOf(Number.class);
		assertThat(((Number) json0.get("latency_ms")).longValue()).isEqualTo(0L);

		mdc.put("latency_ms", "-1");
		LoggingEvent eventNeg = createEvent(Level.INFO, "msg", mdc);
		@SuppressWarnings("unchecked")
		Map<String, Object> jsonNeg = MAPPER.readValue(layout.doLayout(eventNeg).trim(), LinkedHashMap.class);
		assertThat(jsonNeg.containsKey("latency_ms")).isFalse();

		mdc.put("latency_ms", "not-a-number");
		LoggingEvent eventNan = createEvent(Level.INFO, "msg", mdc);
		@SuppressWarnings("unchecked")
		Map<String, Object> jsonNan = MAPPER.readValue(layout.doLayout(eventNan).trim(), LinkedHashMap.class);
		assertThat(jsonNan.containsKey("latency_ms")).isFalse();
	}

	@Test
	void dropsArbitraryMdcKeysAndNeverLogNames() throws Exception {
		Map<String, String> mdc = new HashMap<>();
		mdc.put("image", "raw-image-bytes");
		mdc.put("payload", "sensitive-payload");
		mdc.put("token", "secret-token");
		mdc.put("password", "secret-password");
		mdc.put("arbitrary_future_key", "future-value");
		mdc.put("request_id", "11111111-1111-1111-1111-111111111111");

		LoggingEvent event = createEvent(Level.INFO, "msg", mdc);
		String line = layout.doLayout(event);

		@SuppressWarnings("unchecked")
		Map<String, Object> json = MAPPER.readValue(line.trim(), LinkedHashMap.class);
		assertThat(json.containsKey("image")).isFalse();
		assertThat(json.containsKey("payload")).isFalse();
		assertThat(json.containsKey("token")).isFalse();
		assertThat(json.containsKey("password")).isFalse();
		assertThat(json.containsKey("arbitrary_future_key")).isFalse();
		assertThat(json.containsKey("request_id")).isTrue();
	}

	@Test
	void masksSignedUrlsBase64TokensAndSecretsInMsg() throws Exception {
		String secretUrl = "https://example.invalid/look.jpg?X-Amz-Signature=secret-sig&foo=bar";
		String tokenUrl = "https://cdn.example.com/item.png?token=capability123";
		String base64Data = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAAB";
		String rawJpeg = "/9j/4AAQSkZJRgABAQEAYABgAAD";
		String bearer = "Bearer secret-bearer-token-12345";
		String tokenParam = "token=sensitive-auth-token";
		String apiKey = "api_key=sk-secret-key-999";
		String passwordParam = "password=my-secret-pass";

		String rawMsg = "Fetch from " + secretUrl + " and " + tokenUrl + " with " + base64Data
			+ " or " + rawJpeg + " auth " + bearer + " params " + tokenParam + " " + apiKey + " " + passwordParam;

		LoggingEvent event = createEvent(Level.INFO, rawMsg, Map.of());
		String line = layout.doLayout(event);

		assertThat(line).doesNotContain(secretUrl);
		assertThat(line).doesNotContain(tokenUrl);
		assertThat(line).doesNotContain("secret-sig");
		assertThat(line).doesNotContain("capability123");
		assertThat(line).doesNotContain(base64Data);
		assertThat(line).doesNotContain(rawJpeg);
		assertThat(line).doesNotContain("secret-bearer-token-12345");
		assertThat(line).doesNotContain("sensitive-auth-token");
		assertThat(line).doesNotContain("sk-secret-key-999");
		assertThat(line).doesNotContain("my-secret-pass");

		@SuppressWarnings("unchecked")
		Map<String, Object> json = MAPPER.readValue(line.trim(), LinkedHashMap.class);
		assertThat((String) json.get("msg")).contains("***");
	}

	@Test
	void masksAssignmentLikeValuesForEveryFrozenNeverLogNameInMsg() throws Exception {
		String basicAuth = "authorization=Basic dXNlcjpwYXNz";
		String customerContent = "content=customer@example.com";
		LoggingEvent counterExampleEvent = createEvent(Level.INFO, basicAuth + " and " + customerContent, Map.of());
		String counterExampleLine = layout.doLayout(counterExampleEvent);
		assertThat(counterExampleLine).doesNotContain("Basic dXNlcjpwYXNz");
		assertThat(counterExampleLine).doesNotContain("customer@example.com");
		assertThat(counterExampleLine).contains("authorization=" + LoggingPolicy.MASK);
		assertThat(counterExampleLine).contains("content=" + LoggingPolicy.MASK);

		for (String forbiddenName : LoggingPolicy.NEVER_LOG_NAMES) {
			String sensitiveVal = "sensitive-val-" + forbiddenName;
			String msg1 = forbiddenName + "=" + sensitiveVal;
			LoggingEvent event1 = createEvent(Level.INFO, msg1, Map.of());
			String line1 = layout.doLayout(event1);
			assertThat(line1)
				.as("Assignment with '=' for forbidden name '%s' must not leak value", forbiddenName)
				.doesNotContain(sensitiveVal);
			assertThat(line1).contains(forbiddenName + "=" + LoggingPolicy.MASK);

			String msg2 = forbiddenName + ": " + sensitiveVal;
			LoggingEvent event2 = createEvent(Level.INFO, msg2, Map.of());
			String line2 = layout.doLayout(event2);
			assertThat(line2)
				.as("Assignment with ': ' for forbidden name '%s' must not leak value", forbiddenName)
				.doesNotContain(sensitiveVal);
			assertThat(line2).contains(forbiddenName + ": " + LoggingPolicy.MASK);

			String msg3 = "\"" + forbiddenName + "\": \"" + sensitiveVal + "\"";
			LoggingEvent event3 = createEvent(Level.INFO, msg3, Map.of());
			String line3 = layout.doLayout(event3);
			assertThat(line3)
				.as("JSON-like assignment for forbidden name '%s' must not leak value", forbiddenName)
				.doesNotContain(sensitiveVal);
		}

		// Ensure ordinary prose containing substrings is preserved without blanket masking
		String prose = "status update image processing data integrity content length token bucket";
		LoggingEvent proseEvent = createEvent(Level.INFO, prose, Map.of());
		String proseLine = layout.doLayout(proseEvent);
		assertThat(proseLine).contains(prose);
	}

	@Test
	void masksUnquotedMultiwordAssignmentsWithoutLeavingSuffixLeakage() throws Exception {
		String passwordAssignment = "password=correct horse battery staple";
		LoggingEvent passEvent = createEvent(Level.INFO, passwordAssignment, Map.of());
		String passLine = layout.doLayout(passEvent);
		assertThat(passLine).doesNotContain("correct");
		assertThat(passLine).doesNotContain("horse");
		assertThat(passLine).doesNotContain("battery");
		assertThat(passLine).doesNotContain("staple");
		assertThat(passLine).contains("password=" + LoggingPolicy.MASK);

		String contentAssignment = "content=multi word user payload text here, user_id=test-user";
		LoggingEvent contentEvent = createEvent(Level.INFO, contentAssignment, Map.of());
		String contentLine = layout.doLayout(contentEvent);
		assertThat(contentLine).doesNotContain("multi");
		assertThat(contentLine).doesNotContain("word");
		assertThat(contentLine).doesNotContain("payload text");
		assertThat(contentLine).contains("content=" + LoggingPolicy.MASK);
		assertThat(contentLine).contains("user_id=test-user");

		@SuppressWarnings("unchecked")
		Map<String, Object> passJson = MAPPER.readValue(passLine.trim(), LinkedHashMap.class);
		assertThat(passJson.get("msg")).isEqualTo("password=" + LoggingPolicy.MASK);

		@SuppressWarnings("unchecked")
		Map<String, Object> contentJson = MAPPER.readValue(contentLine.trim(), LinkedHashMap.class);
		assertThat(contentJson.get("msg")).isEqualTo("content=" + LoggingPolicy.MASK + ", user_id=test-user");
	}
	@Test
	void masksStandardAndUrlSafeBase64BlobsWhilePreservingUuids() throws Exception {
		// Innocuously named field carrying URL-safe base64 blob (with - and _)
		String urlSafeBlob = "Abcd_efghijklmnopqrstuvwxyz0123456789-ABCDE";
		// Padded URL-safe base64 blob
		String paddedUrlSafe = "VGhpcyBpcyBhIHNhZmUgdGVzdCBibG9i_-==";
		// Standard base64 blob (with + and /)
		String standardBlob = "VGhpcyBpcyBhIHNhZmUgdGVzdCBibG9iKysv==";
		// Unpadded standard base64 40+ chars
		String unpaddedStandard = "VGhpcyBpcyBhIHNhZmUgdGVzdCBibG9iKysvMDEyMzQ1Njc4OTA=";
		// UUIDs must NOT be masked
		String sampleUuid = "11111111-2222-3333-4444-555555555555";
		String anotherUuid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

		String rawMsg = "opaque=" + urlSafeBlob + " padded=" + paddedUrlSafe + " std=" + standardBlob
			+ " unpadded=" + unpaddedStandard + " uuid1=" + sampleUuid + " uuid2=" + anotherUuid;

		LoggingEvent event = createEvent(Level.INFO, rawMsg, Map.of());
		String line = layout.doLayout(event);

		assertThat(line).doesNotContain(urlSafeBlob);
		assertThat(line).doesNotContain("Abcd_efgh");
		assertThat(line).doesNotContain(paddedUrlSafe);
		assertThat(line).doesNotContain(standardBlob);
		// UUIDs must remain visible and unmodified
		assertThat(line).contains(sampleUuid);
		assertThat(line).contains(anotherUuid);

		@SuppressWarnings("unchecked")
		Map<String, Object> json = MAPPER.readValue(line.trim(), LinkedHashMap.class);
		String msg = (String) json.get("msg");
		assertThat(msg).contains("uuid1=" + sampleUuid);
		assertThat(msg).contains("uuid2=" + anotherUuid);
		assertThat(msg).contains(LoggingPolicy.MASK);
	}

	@Test
	void handlesQuotesNewlinesAndControlCharactersWithoutCorruptingJson() throws Exception {
		String hostile = "Line 1\nLine 2\r\n\t\"quoted\" \\backslash and \u0000 control char \u001F end";
		LoggingEvent event = createEvent(Level.ERROR, hostile, Map.of());
		String line = layout.doLayout(event);

		assertThat(line.lines().count()).isEqualTo(1);
		@SuppressWarnings("unchecked")
		Map<String, Object> json = MAPPER.readValue(line.trim(), LinkedHashMap.class);
		assertThat((String) json.get("msg")).contains("Line 1\nLine 2");
		assertThat((String) json.get("msg")).contains("\"quoted\"");
	}

	@Test
	void directByteArrayArgumentEmitsMaskAndNeverDecimalArrayOrRawBytes() throws Exception {
		LoggingEvent event = new LoggingEvent(
			"com.aifashionvault.test.TestLogger",
			logger,
			Level.INFO,
			"upload={}",
			null,
			new Object[]{ new byte[]{ 1, 2, 3 } }
		);
		event.setTimeStamp(Instant.parse("2026-09-05T12:00:00Z").toEpochMilli());

		String line = layout.doLayout(event);
		assertThat(line).doesNotContain("1, 2, 3");
		assertThat(line).doesNotContain("[B@");
		assertThat(line).doesNotContain("upload=");

		@SuppressWarnings("unchecked")
		Map<String, Object> json = MAPPER.readValue(line.trim(), LinkedHashMap.class);
		assertThat(json.get("msg")).isEqualTo(LoggingPolicy.MASK);
	}

	@Test
	void nonArrayParameterizedMessageFormatsNormally() throws Exception {
		LoggingEvent event = new LoggingEvent(
			"com.aifashionvault.test.TestLogger",
			logger,
			Level.INFO,
			"processed item count={} name={}",
			null,
			new Object[]{ 42, "item-alpha" }
		);
		event.setTimeStamp(Instant.parse("2026-09-05T12:00:00Z").toEpochMilli());

		String line = layout.doLayout(event);
		@SuppressWarnings("unchecked")
		Map<String, Object> json = MAPPER.readValue(line.trim(), LinkedHashMap.class);
		assertThat(json.get("msg")).isEqualTo("processed item count=42 name=item-alpha");
	}

	@Test
	void otherPrimitiveArraysEmitMask() throws Exception {
		LoggingEvent intArrEvent = new LoggingEvent(
			"com.aifashionvault.test.TestLogger",
			logger,
			Level.INFO,
			"numbers={}",
			null,
			new Object[]{ new int[]{ 10, 20, 30 } }
		);
		intArrEvent.setTimeStamp(Instant.parse("2026-09-05T12:00:00Z").toEpochMilli());
		String line = layout.doLayout(intArrEvent);
		@SuppressWarnings("unchecked")
		Map<String, Object> json = MAPPER.readValue(line.trim(), LinkedHashMap.class);
		assertThat(json.get("msg")).isEqualTo(LoggingPolicy.MASK);
	}

	@Test
	void degradesSafelyOnInternalFaultWithoutThrowing() throws Exception {
		JsonLoggingLayout faultyLayout = new JsonLoggingLayout() {
			@Override
			protected String renderJson(Map<String, Object> fields) throws Exception {
				throw new RuntimeException("Simulated layout fault");
			}
		};

		LoggingEvent event = createEvent(Level.ERROR, "normal msg", Map.of("request_id", "req-1"));
		String line = faultyLayout.doLayout(event);

		assertThat(line).isNotNull();
		assertThat(line).endsWith(System.lineSeparator());
		@SuppressWarnings("unchecked")
		Map<String, Object> json = MAPPER.readValue(line.trim(), LinkedHashMap.class);
		assertThat(json.containsKey("ts")).isTrue();
		assertThat(json.containsKey("level")).isTrue();
		assertThat(json.containsKey("logger")).isTrue();
		assertThat(json.get("msg")).isEqualTo(LoggingPolicy.MASK);
	}

	@Test
	void masksSignedUrlsBase64TokensAndSecretsInLoggerName() throws Exception {
		String secretUrl = "https://example.invalid/look.jpg?X-Amz-Signature=secret-sig&foo=bar";
		String tokenUrl = "https://cdn.example.com/item.png?token=capability123";
		String base64Data = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAAB";
		String rawJpeg = "/9j/4AAQSkZJRgABAQEAYABgAAD";
		String bearer = "Bearer secret-bearer-token-12345";
		String tokenParam = "token=sensitive-auth-token";
		String apiKey = "api_key=sk-secret-key-999";
		String passwordParam = "password=my-secret-pass";

		List<String> dynamicLoggers = java.util.List.of(
			"com.aifashionvault." + secretUrl,
			"com.aifashionvault." + tokenUrl,
			"com.aifashionvault." + base64Data,
			"com.aifashionvault." + rawJpeg,
			"com.aifashionvault." + bearer,
			"com.aifashionvault." + tokenParam,
			"com.aifashionvault." + apiKey,
			"com.aifashionvault." + passwordParam
		);

		for (String dynamicLogger : dynamicLoggers) {
			LoggingEvent event = createEvent(dynamicLogger, Level.INFO, "safe message", Map.of());
			String line = layout.doLayout(event);

			assertThat(line).doesNotContain("secret-sig");
			assertThat(line).doesNotContain("capability123");
			assertThat(line).doesNotContain("iVBORw0KGgoAAAANSUhEUgAAAAEAAAAB");
			assertThat(line).doesNotContain("/9j/4AAQSkZJRgABAQEAYABgAAD");
			assertThat(line).doesNotContain("secret-bearer-token-12345");
			assertThat(line).doesNotContain("sensitive-auth-token");
			assertThat(line).doesNotContain("sk-secret-key-999");
			assertThat(line).doesNotContain("my-secret-pass");

			@SuppressWarnings("unchecked")
			Map<String, Object> json = MAPPER.readValue(line.trim(), LinkedHashMap.class);
			assertThat((String) json.get("logger")).contains("***");
			assertThat((String) json.get("msg")).isEqualTo("safe message");
		}
	}

	@Test
	void catastrophicFallbackEmitsFixedValidJsonWithoutInterpolatingHostileContent() throws Exception {
		JsonLoggingLayout catastrophicLayout = new JsonLoggingLayout() {
			@Override
			protected String renderJson(Map<String, Object> fields) throws Exception {
				throw new RuntimeException("Primary render failed");
			}

			@Override
			protected String renderFallbackJson(Map<String, Object> fallback) throws Exception {
				throw new RuntimeException("Fallback render failed");
			}
		};

		String hostileLogger = "HostileLogger<script>\"\\n\r\t";
		String hostileMessage = "SecretPayload12345\"}\u0000";
		LoggingEvent event = createEvent(
			hostileLogger,
			Level.ERROR,
			hostileMessage,
			Map.of("request_id", "malicious_req")
		);

		String line = catastrophicLayout.doLayout(event);

		assertThat(line).isNotNull();
		assertThat(line).endsWith(System.lineSeparator());
		assertThat(line).doesNotContain("HostileLogger");
		assertThat(line).doesNotContain("SecretPayload12345");
		assertThat(line).doesNotContain("malicious_req");

		assertThat(line).isEqualTo(JsonLoggingLayout.CATASTROPHIC_FALLBACK_JSON);

		@SuppressWarnings("unchecked")
		Map<String, Object> json = MAPPER.readValue(line.trim(), LinkedHashMap.class);
		assertThat(json.get("ts")).isEqualTo("1970-01-01T00:00:00Z");
		assertThat(json.get("level")).isEqualTo("ERROR");
		assertThat(json.get("logger")).isEqualTo("ROOT");
		assertThat(json.get("msg")).isEqualTo(LoggingPolicy.MASK);
	}

	private LoggingEvent createEvent(Level level, String message, Map<String, String> mdc) {
		return createEvent("com.aifashionvault.test.TestLogger", level, message, mdc);
	}

	private LoggingEvent createEvent(String loggerName, Level level, String message, Map<String, String> mdc) {
		Logger targetLogger = context != null ? context.getLogger(loggerName) : logger;
		LoggingEvent event = new LoggingEvent(
			loggerName,
			targetLogger,
			level,
			message,
			null,
			null
		);
		event.setTimeStamp(Instant.parse("2026-09-05T12:00:00Z").toEpochMilli());
		if (mdc != null && !mdc.isEmpty()) {
			event.setMDCPropertyMap(new HashMap<>(mdc));
		}
		return event;
	}
}
