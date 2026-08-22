package com.aifashionvault.platform.web;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.TimeZone;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.aifashionvault.platform.security.ExternalTokenVerifier;
import com.aifashionvault.platform.security.InProcessJwtFixture;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.user.IdentityProvisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.autoconfigure.exclude=")
@AutoConfigureMockMvc
class MeApiTest {

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
	void clearIdentityTables() {
		jdbc.update("DELETE FROM identity_mapping");
		jdbc.update("DELETE FROM users");
	}

	@Test
	void getMeAuthenticatedReturnsCanonicalIdOnlyJson() throws Exception {
		MvcResult result = mockMvc.perform(get("/me").header(HttpHeaders.AUTHORIZATION, bearer(InProcessJwtFixture.bearerToken())))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andReturn();

		String body = result.getResponse().getContentAsString();
		UUID id = jdbc.queryForObject("SELECT id FROM users", UUID.class);
		assertThat(id).isNotNull();
		assertThat(body).isEqualTo("{\"id\":\"" + id + "\"}");
		assertThat(body).doesNotContain("firebaseUid", "issuer", "subject");
		assertThat(count("users")).isEqualTo(1);
	}

	@Test
	void deleteMeAuthenticatedReturns204EmptyBodyAndSetsDeletedAt() throws Exception {
		MvcResult result = mockMvc.perform(delete("/me").header(HttpHeaders.AUTHORIZATION, bearer(InProcessJwtFixture.bearerToken())))
			.andExpect(status().isNoContent())
			.andReturn();

		assertThat(result.getResponse().getContentAsString()).isEmpty();
		assertThat(count("users")).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT deleted_at FROM users", Timestamp.class)).isNotNull();
	}

	@Test
	void getMeWithoutAuthorizationIs401() throws Exception {
		mockMvc.perform(get("/me"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void deleteMeWithoutAuthorizationIs401() throws Exception {
		mockMvc.perform(delete("/me"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void expiredBearerIs401() throws Exception {
		String authorization = bearer(InProcessJwtFixture.expiredBearerToken());
		mockMvc.perform(get("/me").header(HttpHeaders.AUTHORIZATION, authorization))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(delete("/me").header(HttpHeaders.AUTHORIZATION, authorization))
			.andExpect(status().isUnauthorized());
		assertThat(count("users")).isEqualTo(0);
	}

	@Test
	void deleteMeWithoutProvisioningFailsClosedUnauthorized() {
		MeController controller = new MeController(absentIdentityProvisioning());
		UserId userId = new UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"));

		assertThatThrownBy(() -> controller.delete(userId))
			.isInstanceOf(ResponseStatusException.class)
			.satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED));
	}

	@Test
	void afterDeleteSameIssuerSubjectRemainsDeletedAndDoesNotProvisionAgain() throws Exception {
		String token = InProcessJwtFixture.bearerToken();
		mockMvc.perform(delete("/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isNoContent())
			.andExpect(content().string(""));

		mockMvc.perform(get("/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(delete("/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isUnauthorized());

		String fresh = InProcessJwtFixture.bearerToken();
		mockMvc.perform(get("/me").header(HttpHeaders.AUTHORIZATION, bearer(fresh)))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(delete("/me").header(HttpHeaders.AUTHORIZATION, bearer(fresh)))
			.andExpect(status().isUnauthorized());

		assertThat(count("users")).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT deleted_at FROM users", Timestamp.class)).isNotNull();
	}

	private int count(String table) {
		Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
		assertThat(count).isNotNull();
		return count;
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}

	private static ObjectProvider<IdentityProvisioning> absentIdentityProvisioning() {
		return new ObjectProvider<>() {
			@Override
			public IdentityProvisioning getObject() {
				return null;
			}

			@Override
			public IdentityProvisioning getObject(Object... args) {
				return null;
			}
		};
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
