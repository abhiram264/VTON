package com.aifashionvault.platform.logging;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.LayoutBase;
import tools.jackson.databind.ObjectMapper;

/**
 * Logback layout that renders each log event as a single-line JSON object adhering strictly
 * to the frozen slice-66 eleven-field allowlist and NeverLog masking policy.
 */
public class JsonLoggingLayout extends LayoutBase<ILoggingEvent> {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	public static final String CATASTROPHIC_FALLBACK_JSON =
		"{\"ts\":\"1970-01-01T00:00:00Z\",\"level\":\"ERROR\",\"logger\":\"ROOT\",\"msg\":\"***\"}" + System.lineSeparator();


	public JsonLoggingLayout() {
	}

	@Override
	public String doLayout(ILoggingEvent event) {
		try {
			Map<String, Object> fields = new LinkedHashMap<>();

			// 1. ts
			fields.put("ts", formatTimestamp(event));

			// 2. level
			fields.put("level", event != null && event.getLevel() != null ? event.getLevel().toString() : "INFO");

			// 3. logger
			String loggerName = event != null ? event.getLoggerName() : null;
			if (loggerName != null && !loggerName.isEmpty()) {
				String masked = LoggingPolicy.mask(loggerName);
				if (masked != null && !masked.isEmpty()) {
					fields.put("logger", masked);
				}
			}

			// 4. msg
			if (event != null) {
				if (hasDirectByteArrayArgument(event)) {
					fields.put("msg", LoggingPolicy.MASK);
				}
				else {
					String message = event.getFormattedMessage();
					if (message != null && !message.isEmpty()) {
						String masked = LoggingPolicy.mask(message);
						if (masked != null && !masked.isEmpty()) {
							fields.put("msg", masked);
						}
					}
				}
			}

			// 5-11 from MDC
			if (event != null) {
				Map<String, String> mdc = null;
				try {
					mdc = event.getMDCPropertyMap();
				}
				catch (Throwable ignored) {
				}
				if (mdc != null && !mdc.isEmpty()) {
					addStringField(fields, LoggingPolicy.KEY_REQUEST_ID, mdc.get(LoggingPolicy.KEY_REQUEST_ID));

					// 6. user_id
					addStringField(fields, LoggingPolicy.KEY_USER_ID, mdc.get(LoggingPolicy.KEY_USER_ID));

					// 7. generation_id
					addStringField(fields, LoggingPolicy.KEY_GENERATION_ID, mdc.get(LoggingPolicy.KEY_GENERATION_ID));

					// 8. provider
					addStringField(fields, LoggingPolicy.KEY_PROVIDER, mdc.get(LoggingPolicy.KEY_PROVIDER));

					// 9. status
					addStringField(fields, LoggingPolicy.KEY_STATUS, mdc.get(LoggingPolicy.KEY_STATUS));

					// 10. latency_ms (numeric, non-negative)
					String latencyStr = mdc.get(LoggingPolicy.KEY_LATENCY_MS);
					if (latencyStr != null && !latencyStr.isBlank()) {
						try {
							long latency = Long.parseLong(latencyStr.trim());
							if (latency >= 0) {
								fields.put(LoggingPolicy.KEY_LATENCY_MS, latency);
							}
						}
						catch (NumberFormatException ignored) {
						}
					}

					// 11. error_code
					addStringField(fields, LoggingPolicy.KEY_ERROR_CODE, mdc.get(LoggingPolicy.KEY_ERROR_CODE));
				}
			}
			return renderJson(fields) + System.lineSeparator();
		}
		catch (Throwable t) {
			return renderFallback(event);
		}
	}

	protected String renderJson(Map<String, Object> fields) throws Exception {
		return MAPPER.writeValueAsString(fields);
	}

	private void addStringField(Map<String, Object> fields, String key, String raw) {
		if (raw != null && !raw.isBlank()) {
			String masked = LoggingPolicy.mask(raw);
			if (masked != null && !masked.isBlank()) {
				fields.put(key, masked);
			}
		}
	}

	private static boolean hasDirectByteArrayArgument(ILoggingEvent event) {
		Object[] arguments = event.getArgumentArray();
		if (arguments == null || arguments.length == 0) {
			return false;
		}
		for (Object arg : arguments) {
			if (arg instanceof byte[]
				|| arg instanceof short[]
				|| arg instanceof int[]
				|| arg instanceof long[]
				|| arg instanceof float[]
				|| arg instanceof double[]
				|| arg instanceof boolean[]
				|| arg instanceof char[]) {
				return true;
			}
		}
		return false;
	}

	private String formatTimestamp(ILoggingEvent event) {
		if (event == null) {
			return Instant.now().toString();
		}
		try {
			Instant instant = event.getInstant();
			if (instant != null) {
				return instant.toString();
			}
		}
		catch (Throwable ignored) {
		}
		return Instant.ofEpochMilli(event.getTimeStamp()).toString();
	}

	private String renderFallback(ILoggingEvent event) {
		try {
			Map<String, Object> fallback = new LinkedHashMap<>();
			fallback.put("ts", formatTimestamp(event));
			fallback.put("level", event != null && event.getLevel() != null ? event.getLevel().toString() : "INFO");
			String rawLogger = event != null && event.getLoggerName() != null ? event.getLoggerName() : "ROOT";
			String maskedLogger = LoggingPolicy.mask(rawLogger);
			fallback.put("logger", maskedLogger != null && !maskedLogger.isEmpty() ? maskedLogger : "ROOT");
			fallback.put("msg", LoggingPolicy.MASK);
			return renderFallbackJson(fallback) + System.lineSeparator();
		}
		catch (Throwable fatal) {
			return CATASTROPHIC_FALLBACK_JSON;
		}
	}

	protected String renderFallbackJson(Map<String, Object> fallback) throws Exception {
		return MAPPER.writeValueAsString(fallback);
	}
}
