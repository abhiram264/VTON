package com.aifashionvault.platform.logging;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.Map;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class LogbackXmlInstantiationTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Test
	void logbackRendersThroughJsonLoggingLayoutOnStdout() throws Exception {
		LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
		JoranConfigurator configurator = new JoranConfigurator();
		configurator.setContext(context);
		context.reset();
		try (InputStream is = getClass().getResourceAsStream("/logback-spring.xml")) {
			assertThat(is).isNotNull();
			configurator.doConfigure(is);
		}

		PrintStream originalOut = System.out;
		ByteArrayOutputStream outCapture = new ByteArrayOutputStream();
		System.setOut(new PrintStream(outCapture));
		try {
			MDC.put("request_id", "55555555-5555-5555-5555-555555555555");
			MDC.put("user_id", "66666666-6666-6666-6666-666666666666");

			Logger logger = context.getLogger("com.aifashionvault.test.ConfiguredLogger");
			logger.info("Logback XML integration smoke test");
		}
		finally {
			MDC.clear();
			System.setOut(originalOut);
		}

		String output = outCapture.toString();
		assertThat(output).isNotEmpty();

		String targetLine = null;
		for (String line : output.lines().toList()) {
			if (line.contains("Logback XML integration smoke test")) {
				targetLine = line;
				break;
			}
		}

		assertThat(targetLine).isNotNull();
		@SuppressWarnings("unchecked")
		Map<String, Object> json = MAPPER.readValue(targetLine.trim(), java.util.LinkedHashMap.class);
		assertThat(json.get("logger")).isEqualTo("com.aifashionvault.test.ConfiguredLogger");
		assertThat(json.get("msg")).isEqualTo("Logback XML integration smoke test");
		assertThat(json.get("request_id")).isEqualTo("55555555-5555-5555-5555-555555555555");
		assertThat(json.get("user_id")).isEqualTo("66666666-6666-6666-6666-666666666666");
	}
}
