package com.aifashionvault.platform.web;

import java.time.Duration;
import java.util.TimeZone;
import java.util.UUID;

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
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.aifashionvault.platform.security.ExternalTokenVerifier;
import com.aifashionvault.platform.security.InProcessJwtFixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * C20 owner-bound device-token lifecycle over the real HTTP surface. The token
 * registry is exercised through the mounted controller and asserted in the
 * database, so ownership boundaries are proven at the layer that enforces them.
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude=")
@AutoConfigureMockMvc
class DeviceRegistrationApiTest {

	private static final String POSTGRES_IMAGE = "postgres:18";
	private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);
	private static final String TOKEN_A = "device-token-aaaa";
	private static final String TOKEN_B = "device-token-bbbb";

	static {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
	}

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("postgres")
		.withStartupTimeout(STARTUP_TIMEOUT);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

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

	@BeforeEach
	void clearIdentityAndDeviceTables() {
		jdbc.update("DELETE FROM device_registration");
		jdbc.update("DELETE FROM notification_preference");
		jdbc.update("DELETE FROM identity_mapping");
		jdbc.update("DELETE FROM users");
	}

	@Test
	void registerIsIdempotentForTheSameOwnerAndToken() throws Exception {
		register(TOKEN_A, "ANDROID")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.deviceToken").value(TOKEN_A))
			.andExpect(jsonPath("$.platform").value("ANDROID"));

		register(TOKEN_A, "ANDROID").andExpect(status().isOk());

		assertThat(count("device_registration")).isEqualTo(1);
	}

	@Test
	void reRegisteringATokenMovesItToTheSecondOwner() throws Exception {
		String firstOwner = InProcessJwtFixture.bearerTokenForSubject("owner-first");
		String secondOwner = InProcessJwtFixture.bearerTokenForSubject("owner-second");

		register(TOKEN_A, "ANDROID", firstOwner).andExpect(status().isOk());
		UUID firstOwnerId = ownerOf(TOKEN_A);

		register(TOKEN_A, "IOS", secondOwner).andExpect(status().isOk());
		UUID secondOwnerId = ownerOf(TOKEN_A);

		assertThat(secondOwnerId).isNotEqualTo(firstOwnerId);
		assertThat(count("device_registration")).isEqualTo(1);

		// The first account no longer sees the device; the second account does.
		list(firstOwner).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
		list(secondOwner).andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].deviceToken").value(TOKEN_A))
			.andExpect(jsonPath("$[0].platform").value("IOS"));
	}

	@Test
	void listReturnsOnlyTheCallersDevices() throws Exception {
		String firstOwner = InProcessJwtFixture.bearerTokenForSubject("owner-first");
		String secondOwner = InProcessJwtFixture.bearerTokenForSubject("owner-second");

		register(TOKEN_A, "ANDROID", firstOwner).andExpect(status().isOk());
		register(TOKEN_B, "IOS", secondOwner).andExpect(status().isOk());

		list(firstOwner).andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].deviceToken").value(TOKEN_A));
		list(secondOwner).andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].deviceToken").value(TOKEN_B));
	}

	@Test
	void removalIsOwnerBoundAndIdempotent() throws Exception {
		String firstOwner = InProcessJwtFixture.bearerTokenForSubject("owner-first");
		String secondOwner = InProcessJwtFixture.bearerTokenForSubject("owner-second");

		register(TOKEN_A, "ANDROID", firstOwner).andExpect(status().isOk());

		// A different account's delete must not remove the row.
		remove(TOKEN_A, secondOwner).andExpect(status().isNoContent());
		assertThat(count("device_registration")).isEqualTo(1);

		remove(TOKEN_A, firstOwner).andExpect(status().isNoContent());
		assertThat(count("device_registration")).isEqualTo(0);

		// Repeat removal is still 204.
		remove(TOKEN_A, firstOwner).andExpect(status().isNoContent());
	}

	@Test
	void invalidRegistrationIsRejectedWithoutWriting() throws Exception {
		mockMvc.perform(post("/me/devices")
				.header(HttpHeaders.AUTHORIZATION, bearer(InProcessJwtFixture.bearerToken()))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"deviceToken\":\"" + TOKEN_A + "\",\"platform\":\"WINDOWS\"}"))
			.andExpect(status().isBadRequest());
		assertThat(count("device_registration")).isEqualTo(0);

		mockMvc.perform(post("/me/devices")
				.header(HttpHeaders.AUTHORIZATION, bearer(InProcessJwtFixture.bearerToken()))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"deviceToken\":\"  \",\"platform\":\"ANDROID\"}"))
			.andExpect(status().isBadRequest());
		assertThat(count("device_registration")).isEqualTo(0);
	}

	@Test
	void preferencesDefaultToEnabledAndPersistPerOwner() throws Exception {
		String firstOwner = InProcessJwtFixture.bearerTokenForSubject("owner-first");
		String secondOwner = InProcessJwtFixture.bearerTokenForSubject("owner-second");

		mockMvc.perform(get("/me/notification-preferences").header(HttpHeaders.AUTHORIZATION, bearer(firstOwner)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.pushEnabled").value(true));

		mockMvc.perform(put("/me/notification-preferences")
				.header(HttpHeaders.AUTHORIZATION, bearer(firstOwner))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"pushEnabled\":false}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.pushEnabled").value(false));

		mockMvc.perform(get("/me/notification-preferences").header(HttpHeaders.AUTHORIZATION, bearer(firstOwner)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.pushEnabled").value(false));

		// The other owner keeps the default.
		mockMvc.perform(get("/me/notification-preferences").header(HttpHeaders.AUTHORIZATION, bearer(secondOwner)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.pushEnabled").value(true));

		assertThat(count("notification_preference")).isEqualTo(1);
	}

	@Test
	void deviceEndpointsRequireAuthentication() throws Exception {
		mockMvc.perform(get("/me/devices")).andExpect(status().isUnauthorized());
		mockMvc.perform(delete("/me/devices").contentType(MediaType.APPLICATION_JSON).content("{\"deviceToken\":\"x\"}"))
			.andExpect(status().isUnauthorized());
	}

	private org.springframework.test.web.servlet.ResultActions register(String token, String platform) throws Exception {
		return register(token, platform, InProcessJwtFixture.bearerToken());
	}

	private org.springframework.test.web.servlet.ResultActions register(String token, String platform, String jwt) throws Exception {
		return mockMvc.perform(post("/me/devices")
			.header(HttpHeaders.AUTHORIZATION, bearer(jwt))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"deviceToken\":\"" + token + "\",\"platform\":\"" + platform + "\"}"));
	}

	private org.springframework.test.web.servlet.ResultActions list(String jwt) throws Exception {
		return mockMvc.perform(get("/me/devices").header(HttpHeaders.AUTHORIZATION, bearer(jwt)));
	}

	private org.springframework.test.web.servlet.ResultActions remove(String token, String jwt) throws Exception {
		return mockMvc.perform(delete("/me/devices")
			.header(HttpHeaders.AUTHORIZATION, bearer(jwt))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"deviceToken\":\"" + token + "\"}"));
	}

	private UUID ownerOf(String token) {
		UUID owner = jdbc.queryForObject(
			"SELECT user_id FROM device_registration WHERE device_token = ?", UUID.class, token);
		assertThat(owner).isNotNull();
		return owner;
	}

	private int count(String table) {
		Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
		assertThat(count).isNotNull();
		return count;
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}

	@TestConfiguration
	static class InProcessVerifierConfiguration {

		@Bean
		@Primary
		ExternalTokenVerifier inProcessExternalTokenVerifier() {
			return InProcessJwtFixture.verifier();
		}
	}
}
