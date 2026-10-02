package com.aifashionvault.platform.web;

import java.time.Duration;
import java.util.TimeZone;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.aifashionvault.platform.security.ExternalTokenVerifier;
import com.aifashionvault.platform.security.InProcessJwtFixture;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end HTTP contract for the owner-scoped consent surface: grant records the
 * policy version, the read reflects it, withdrawal revokes without deleting history,
 * and a second account observes none of the first account's consent state.
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude=")
@AutoConfigureMockMvc
class ConsentsHttpTest {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
		.withDatabaseName("postgres").withStartupTimeout(Duration.ofMinutes(5));
	private static final ObjectMapper MAPPER = new ObjectMapper();

	static { TimeZone.setDefault(TimeZone.getTimeZone("UTC")); }

	@Autowired MockMvc mockMvc;
	@Autowired JdbcTemplate jdbc;

	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		POSTGRES.start();
		registry.add("vault.db.url", POSTGRES::getJdbcUrl);
		registry.add("vault.db.username", POSTGRES::getUsername);
		registry.add("vault.db.password", POSTGRES::getPassword);
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@BeforeEach
	void clearTables() {
		jdbc.update("TRUNCATE TABLE consent, identity_mapping, users CASCADE");
	}

	@Test
	void grantReadWithdrawRoundTripRecordsThePolicyVersionAndKeepsHistory() throws Exception {
		String token = bearerToken();

		assertThat(json(consents(token)).get("consents").isEmpty()).isTrue();

		JsonNode granted = json(grant(token, "third_party_ai_transfer", "2026-10-01"));
		assertThat(granted.get("purpose").stringValue()).isEqualTo("third_party_ai_transfer");
		assertThat(granted.get("version").stringValue()).isEqualTo("2026-10-01");
		assertThat(granted.get("revokedAt").isNull()).isTrue();
		assertThat(granted.get("active").booleanValue()).isTrue();

		JsonNode read = json(consents(token));
		assertThat(read.get("consents")).hasSize(1);
		assertThat(read.get("consents").get(0).get("active").booleanValue()).isTrue();

		mockMvc.perform(delete("/me/consents/third_party_ai_transfer").header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isNoContent());

		JsonNode withdrawn = json(consents(token)).get("consents");
		assertThat(withdrawn).hasSize(1);
		assertThat(withdrawn.get(0).get("revokedAt").isNull()).isFalse();
		assertThat(withdrawn.get(0).get("active").booleanValue()).isFalse();

		mockMvc.perform(delete("/me/consents/third_party_ai_transfer").header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isNotFound())
			.andExpect(content().string("{\"code\":\"CONSENT_NOT_GRANTED\"}"));
	}

	@Test
	void reGrantingASupersededVersionKeepsTheEarlierGrantInHistory() throws Exception {
		String token = bearerToken();
		json(grant(token, "retention", "v1"));
		json(grant(token, "retention", "v2"));

		JsonNode rows = json(consents(token)).get("consents");
		assertThat(rows).hasSize(2);
		assertThat(rows.get(0).get("version").stringValue()).isEqualTo("v1");
		assertThat(rows.get(0).get("active").booleanValue()).isFalse();
		assertThat(rows.get(1).get("version").stringValue()).isEqualTo("v2");
		assertThat(rows.get(1).get("active").booleanValue()).isTrue();
	}

	@Test
	void unknownPurposeAndInvalidVersionUseStableCodes() throws Exception {
		String token = bearerToken();
		mockMvc.perform(put("/me/consents/marketing").header(HttpHeaders.AUTHORIZATION, bearer(token))
				.contentType(MediaType.APPLICATION_JSON).content("{\"version\":\"v1\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(content().string("{\"code\":\"CONSENT_PURPOSE_INVALID\"}"));
		mockMvc.perform(put("/me/consents/retention").header(HttpHeaders.AUTHORIZATION, bearer(token))
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(content().string("{\"code\":\"CONSENT_VERSION_INVALID\"}"));
		mockMvc.perform(put("/me/consents/retention").header(HttpHeaders.AUTHORIZATION, bearer(token))
				.contentType(MediaType.APPLICATION_JSON).content("{\"version\":\"   \"}"))
			.andExpect(status().isBadRequest())
			.andExpect(content().string("{\"code\":\"CONSENT_VERSION_INVALID\"}"));
		mockMvc.perform(delete("/me/consents/marketing").header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isBadRequest())
			.andExpect(content().string("{\"code\":\"CONSENT_PURPOSE_INVALID\"}"));
	}

	@Test
	void aSecondAccountObservesNoConsentStateOfTheFirst() throws Exception {
		String owner = bearerToken();
		json(grant(owner, "body_image_processing", "v1"));

		String stranger = bearerTokenForSubject("in-process-second-subject");
		JsonNode strangerConsents = json(consents(stranger)).get("consents");

		assertThat(strangerConsents.isEmpty()).isTrue();
	}

	@Test
	void unauthenticatedConsentRequestsAreRefused() throws Exception {
		mockMvc.perform(get("/me/consents")).andExpect(status().isUnauthorized());
		mockMvc.perform(put("/me/consents/retention").contentType(MediaType.APPLICATION_JSON).content("{\"version\":\"v1\"}"))
			.andExpect(status().isUnauthorized());
	}

	private String bearerToken() throws Exception {
		String token = InProcessJwtFixture.bearerToken();
		provision(token);
		return token;
	}

	private String bearerTokenForSubject(String subject) throws Exception {
		String token = InProcessJwtFixture.bearerTokenForSubject(subject);
		provision(token);
		return token;
	}

	private void provision(String token) throws Exception {
		mockMvc.perform(get("/me").header(HttpHeaders.AUTHORIZATION, bearer(token))).andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity_mapping", Integer.class)).isGreaterThan(0);
	}

	private MvcResult consents(String token) throws Exception {
		return mockMvc.perform(get("/me/consents").header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isOk()).andReturn();
	}

	private MvcResult grant(String token, String purpose, String version) throws Exception {
		return mockMvc.perform(put("/me/consents/" + purpose).header(HttpHeaders.AUTHORIZATION, bearer(token))
				.contentType(MediaType.APPLICATION_JSON).content("{\"version\":\"" + version + "\"}"))
			.andExpect(status().isOk()).andReturn();
	}

	private static JsonNode json(MvcResult result) throws Exception {
		return MAPPER.readTree(result.getResponse().getContentAsString());
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}

	@TestConfiguration
	static class Configuration {
		@Bean @Primary ExternalTokenVerifier verifier() {
			return InProcessJwtFixture.verifier();
		}
	}
}
