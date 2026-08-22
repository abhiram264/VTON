package com.aifashionvault.platform.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigurationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private SecurityFilterChain securityFilterChain;

	@Test
	void getActuatorHealthIsAnonymousOk() throws Exception {
		mockMvc.perform(get("/actuator/health"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void unauthenticatedRequestIsUnauthorized() throws Exception {
		mockMvc.perform(get("/me"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void nonBearerAuthorizationIsUnauthorized() throws Exception {
		mockMvc.perform(get("/me").header("Authorization", "Basic dXNlcjpwYXNz"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void postWithoutCsrfTokenIsUnauthorizedNotForbidden() throws Exception {
		mockMvc.perform(post("/me"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void unauthenticatedRequestDoesNotCreateSession() throws Exception {
		mockMvc.perform(get("/me"))
			.andExpect(status().isUnauthorized())
			.andExpect(header().doesNotExist("Set-Cookie"))
			.andExpect(result -> assertThat(result.getRequest().getSession(false)).isNull());
	}

	@Test
	void filterChainIncludesExternalBearerFilterAndNotOauth2ResourceServer() {
		assertThat(securityFilterChain.getFilters())
			.anyMatch(ExternalBearerAuthenticationFilter.class::isInstance);
		assertThat(securityFilterChain.getFilters())
			.noneMatch(filter -> filter.getClass().getName().contains("oauth2")
				|| filter.getClass().getSimpleName().equals("BearerTokenAuthenticationFilter"));
	}

	@Test
	void configurationDoesNotReferenceOauth2OrSiblingImplementations() throws Exception {
		String configuration = readOwnedSource("SecurityConfiguration.java");
		String filter = readOwnedSource("ExternalBearerAuthenticationFilter.java");

		assertThat(configuration).doesNotContain("oauth2ResourceServer", "JwtExternalTokenVerifier", "JdbcIdentityProvisioning");
		assertThat(filter).doesNotContain("oauth2ResourceServer", "JwtExternalTokenVerifier", "JdbcIdentityProvisioning");
	}

	private static String readOwnedSource(String fileName) throws IOException {
		Path direct = Path.of("src/main/java/com/aifashionvault/platform/security", fileName);
		if (Files.isRegularFile(direct)) {
			return Files.readString(direct, StandardCharsets.UTF_8);
		}
		return Files.readString(
			Path.of("backend/src/main/java/com/aifashionvault/platform/security", fileName),
			StandardCharsets.UTF_8);
	}
}
