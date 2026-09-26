package com.aifashionvault.platform.logging;

import java.io.InputStream;
import java.util.Map;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import tools.jackson.databind.ObjectMapper;


import static org.assertj.core.api.Assertions.assertThat;

class LogbackXmlInstantiationTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Test
	void logbackRendersThroughJsonLoggingLayout() throws Exception {
		LoggerContext context = new LoggerContext();
		context.start();
		JoranConfigurator configurator = new JoranConfigurator();
		configurator.setContext(context);

		String previousRequestId = MDC.get("request_id");
		String previousUserId = MDC.get("user_id");
		ListAppender<ILoggingEvent> events = new ListAppender<>();
		events.setContext(context);
		events.start();
		try {
			try (InputStream is = getClass().getResourceAsStream("/logback-spring.xml")) {
				assertThat(is).isNotNull();
				configurator.doConfigure(is);
			}
			assertThat(context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("CONSOLE")).isNotNull();
			MDC.put("request_id", "55555555-5555-5555-5555-555555555555");
			MDC.put("user_id", "66666666-6666-6666-6666-666666666666");
			Logger logger = context.getLogger("com.aifashionvault.test.ConfiguredLogger");
			logger.addAppender(events);
			logger.info("Logback XML integration smoke test");
		}
		finally {
			if (previousRequestId == null) MDC.remove("request_id");
			else MDC.put("request_id", previousRequestId);
			if (previousUserId == null) MDC.remove("user_id");
			else MDC.put("user_id", previousUserId);
			events.stop();
			context.stop();
		}

		assertThat(events.list).hasSize(1);
		LoggingEvent event = (LoggingEvent) events.list.getFirst();
		event.setMDCPropertyMap(Map.of(
			"request_id", "55555555-5555-5555-5555-555555555555",
			"user_id", "66666666-6666-6666-6666-666666666666"));
		JsonLoggingLayout layout = new JsonLoggingLayout();
		layout.start();
		String output = layout.doLayout(event);
		@SuppressWarnings("unchecked")
		Map<String, Object> json = MAPPER.readValue(output.trim(), java.util.LinkedHashMap.class);
		assertThat(json.get("msg")).isEqualTo("Logback XML integration smoke test");
		assertThat(json.get("request_id")).isEqualTo("55555555-5555-5555-5555-555555555555");
		assertThat(json.get("user_id")).isEqualTo("66666666-6666-6666-6666-666666666666");
	}
}
