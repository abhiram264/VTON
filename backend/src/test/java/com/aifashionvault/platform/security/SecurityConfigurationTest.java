package com.aifashionvault.platform.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.aifashionvault.platform.web.RequestIdFilter;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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

	@Autowired
	private MeterRegistry meterRegistry;

	@Test
	void getActuatorHealthIsAnonymousOk() throws Exception {
		mockMvc.perform(get("/actuator/health"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void unauthenticatedRequestIsUnauthorizedAndEchoesRequestId() throws Exception {
		MvcResult result = mockMvc.perform(get("/me"))
			.andExpect(status().isUnauthorized())
			.andExpect(header().exists(RequestIdFilter.HEADER_NAME))
			.andReturn();

		String requestId = result.getResponse().getHeader(RequestIdFilter.HEADER_NAME);
		assertThat(requestId).isNotNull();
		assertThat(UUID.fromString(requestId)).isNotNull();
	}

	@Test
	void unauthenticatedRequestEchoesValidClientRequestId() throws Exception {
		String customRequestId = "123e4567-e89b-12d3-a456-426614174000";
		mockMvc.perform(get("/me").header(RequestIdFilter.HEADER_NAME, customRequestId))
			.andExpect(status().isUnauthorized())
			.andExpect(header().string(RequestIdFilter.HEADER_NAME, customRequestId));
	}

	@Test
	void unauthenticatedRequestReplacesInvalidClientRequestId() throws Exception {
		String invalidRequestId = "not-a-valid-uuid";
		MvcResult result = mockMvc.perform(get("/me").header(RequestIdFilter.HEADER_NAME, invalidRequestId))
			.andExpect(status().isUnauthorized())
			.andExpect(header().exists(RequestIdFilter.HEADER_NAME))
			.andReturn();

		String echoed = result.getResponse().getHeader(RequestIdFilter.HEADER_NAME);
		assertThat(echoed).isNotNull();
		assertThat(echoed).isNotEqualTo(invalidRequestId);
		assertThat(UUID.fromString(echoed)).isNotNull();
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
	void unauthenticatedRequestRecordsAuthFailureMissingCounter() throws Exception {
		double initial = meterRegistry.find(AuthMetrics.AUTH_FAILURE)
			.tag("reason", AuthMetrics.REASON_MISSING)
			.counter() != null
				? meterRegistry.find(AuthMetrics.AUTH_FAILURE).tag("reason", AuthMetrics.REASON_MISSING).counter().count()
				: 0.0;

		mockMvc.perform(get("/me"))
			.andExpect(status().isUnauthorized());

		Counter missingCounter = meterRegistry.find(AuthMetrics.AUTH_FAILURE)
			.tag("reason", AuthMetrics.REASON_MISSING)
			.counter();
		assertThat(missingCounter).isNotNull();
		assertThat(missingCounter.count()).isGreaterThan(initial);
	}

	@Test
	void malformedAuthorizationRecordsAuthFailureMalformedCounter() throws Exception {
		double initial = meterRegistry.find(AuthMetrics.AUTH_FAILURE)
			.tag("reason", AuthMetrics.REASON_MALFORMED)
			.counter() != null
				? meterRegistry.find(AuthMetrics.AUTH_FAILURE).tag("reason", AuthMetrics.REASON_MALFORMED).counter().count()
				: 0.0;

		mockMvc.perform(get("/me").header("Authorization", "Basic dXNlcjpwYXNz"))
			.andExpect(status().isUnauthorized());

		Counter malformedCounter = meterRegistry.find(AuthMetrics.AUTH_FAILURE)
			.tag("reason", AuthMetrics.REASON_MALFORMED)
			.counter();
		assertThat(malformedCounter).isNotNull();
		assertThat(malformedCounter.count()).isGreaterThan(initial);
	}

	@Test
	void filterChainIncludesRequestIdFilterBeforeExternalBearerFilterAndNotOauth2ResourceServer() {
		List<jakarta.servlet.Filter> filters = securityFilterChain.getFilters();
		int requestIdFilterIndex = -1;
		int externalBearerFilterIndex = -1;
		for (int i = 0; i < filters.size(); i++) {
			jakarta.servlet.Filter f = filters.get(i);
			if (f instanceof RequestIdFilter) {
				requestIdFilterIndex = i;
			}
			else if (f instanceof ExternalBearerAuthenticationFilter) {
				externalBearerFilterIndex = i;
			}
		}
		assertThat(requestIdFilterIndex).isGreaterThanOrEqualTo(0);
		assertThat(externalBearerFilterIndex).isGreaterThan(requestIdFilterIndex);
		assertThat(filters)
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
