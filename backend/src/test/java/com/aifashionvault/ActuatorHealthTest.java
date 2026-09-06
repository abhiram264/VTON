package com.aifashionvault;

import java.time.Duration;
import java.util.TimeZone;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.aifashionvault.platform.security.ExternalTokenVerifier;
import com.aifashionvault.platform.security.InProcessJwtFixture;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
	"spring.autoconfigure.exclude=",
	"management.endpoints.web.exposure.include=health,metrics"
})
@AutoConfigureMockMvc
class ActuatorHealthTest {

	private static final String POSTGRES_IMAGE = "postgres:18";
	private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);

	static {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
	}

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("postgres")
		.withStartupTimeout(STARTUP_TIMEOUT);

	@Autowired
	private MockMvc mockMvc;

	@DynamicPropertySource
	static void registerDatasource(DynamicPropertyRegistry registry) {
		POSTGRES.start();
		registry.add("vault.db.url", POSTGRES::getJdbcUrl);
		registry.add("vault.db.username", POSTGRES::getUsername);
		registry.add("vault.db.password", POSTGRES::getPassword);
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@Test
	void healthIsReachableAndHealthy() throws Exception {
		mockMvc.perform(get("/actuator/health"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void metricsIsExposedUnderProductionLikeConfigAndListsTheFrozenQueueMeters() throws Exception {
		mockMvc.perform(get("/actuator/metrics").header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.names", hasItem("queue.depth")))
			.andExpect(jsonPath("$.names", hasItem("queue.oldest_age")));

		mockMvc.perform(get("/actuator/metrics/queue.oldest_age").header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("queue.oldest_age"))
			.andExpect(jsonPath("$.baseUnit").value("seconds"));
	}

	@Test
	void endpointsOutsideTheExposureListStayHidden() throws Exception {
		mockMvc.perform(get("/actuator/env").header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isNotFound());
	}

	private static String bearer() {
		return "Bearer " + InProcessJwtFixture.bearerToken();
	}

	@TestConfiguration
	static class Configuration {
		@Bean
		@Primary
		ExternalTokenVerifier tokenVerifier() {
			return InProcessJwtFixture.verifier();
		}
	}
}
