package com.aifashionvault.platform.security;

import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.aifashionvault.platform.logging.LoggingPolicy;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.user.IdentityProvisioning;
import com.aifashionvault.user.IdentityResolution;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ExternalBearerAuthenticationFilterTest {

	private static final String ISSUER = "https://securetoken.google.com/app";
	private static final String SUBJECT = "firebase-uid-abc";
	private static final String TOKEN = "eyJhbGciOiJIUzI1NiJ9.super-secret-payload.sig";
	private static final UserId USER_ID = new UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"));

	private ExternalTokenVerifier verifier;
	private IdentityProvisioning provisioning;
	private ObjectProvider<IdentityProvisioning> provisioningProvider;
	private ExternalBearerAuthenticationFilter filter;
	private FilterChain chain;

	@BeforeEach
	void setUp() {
		verifier = mock(ExternalTokenVerifier.class);
		provisioning = mock(IdentityProvisioning.class);
		provisioningProvider = mock(ObjectProvider.class);
		when(provisioningProvider.getIfAvailable()).thenReturn(provisioning);
		filter = new ExternalBearerAuthenticationFilter(verifier, provisioningProvider);
		chain = mock(FilterChain.class);
		MDC.clear();
	}

	@AfterEach
	void tearDown() {
		SecurityContextHolder.clearContext();
		MDC.clear();
	}

	@Test
	void missingAuthorizationReturns401WithoutCallingVerify() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(new MockHttpServletRequest("GET", "/me"), response, chain);

		assertThat(response.getStatus()).isEqualTo(401);
		verifyNoInteractions(verifier);
		verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
		assertThat(MDC.get(LoggingPolicy.KEY_USER_ID)).isNull();
	}

	@Test
	void nonBearerAuthorizationReturns401WithoutCallingVerify() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/me");
		request.addHeader("Authorization", "Basic dXNlcjpwYXNz");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(401);
		verifyNoInteractions(verifier);
		verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
		assertThat(MDC.get(LoggingPolicy.KEY_USER_ID)).isNull();
	}

	@Test
	void blankBearerTokenReturns401WithoutCallingVerify() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/me");
		request.addHeader("Authorization", "Bearer    ");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(401);
		verifyNoInteractions(verifier);
		verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
		assertThat(MDC.get(LoggingPolicy.KEY_USER_ID)).isNull();
	}

	@Test
	void activeResolutionSetsUserIdPrincipal() throws Exception {
		when(verifier.verify(TOKEN)).thenReturn(new VerifiedExternalIdentity(ISSUER, SUBJECT));
		when(provisioning.resolve(ISSUER, SUBJECT)).thenReturn(new IdentityResolution.Active(USER_ID));
		MockHttpServletRequest request = bearerRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, chain);

		verify(verifier).verify(TOKEN);
		verify(provisioning).resolve(ISSUER, SUBJECT);
		verify(chain).doFilter(request, response);
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		assertThat(authentication).isNotNull();
		assertThat(authentication.isAuthenticated()).isTrue();
		assertThat(authentication.getPrincipal()).isEqualTo(USER_ID);
		assertThat(authentication.getPrincipal()).isInstanceOf(UserId.class);
		assertThat(authentication.getPrincipal()).isNotInstanceOf(VerifiedExternalIdentity.class);
		assertThat(authentication.getCredentials()).isNull();
		assertThat(String.valueOf(authentication.getPrincipal())).doesNotContain(TOKEN, SUBJECT, "firebaseUid");
	}

	@Test
	void activeResolutionExposesUserIdInMdcDuringChainAndCleansUpInFinally() throws Exception {
		when(verifier.verify(TOKEN)).thenReturn(new VerifiedExternalIdentity(ISSUER, SUBJECT));
		when(provisioning.resolve(ISSUER, SUBJECT)).thenReturn(new IdentityResolution.Active(USER_ID));
		MockHttpServletRequest request = bearerRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();

		final String[] capturedUserId = new String[1];
		filter.doFilter(request, response, (req, res) -> {
			capturedUserId[0] = MDC.get(LoggingPolicy.KEY_USER_ID);
			assertThat(capturedUserId[0]).isEqualTo(USER_ID.value().toString());
			assertThat(capturedUserId[0]).doesNotContain(TOKEN, SUBJECT, ISSUER);
		});

		assertThat(capturedUserId[0]).isEqualTo(USER_ID.value().toString());
		assertThat(MDC.get(LoggingPolicy.KEY_USER_ID)).isNull();
	}

	@Test
	void removesStaleUserIdInFinallyEvenWhenPreviouslySet() throws Exception {
		when(verifier.verify(TOKEN)).thenReturn(new VerifiedExternalIdentity(ISSUER, SUBJECT));
		when(provisioning.resolve(ISSUER, SUBJECT)).thenReturn(new IdentityResolution.Active(USER_ID));
		String priorUserId = "prior-user-uuid";
		MDC.put(LoggingPolicy.KEY_USER_ID, priorUserId);
		MockHttpServletRequest request = bearerRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, (req, res) -> {
			assertThat(MDC.get(LoggingPolicy.KEY_USER_ID)).isEqualTo(USER_ID.value().toString());
		});

		assertThat(MDC.get(LoggingPolicy.KEY_USER_ID)).isNull();
	}

	@Test
	void consecutiveRequestsOnSameThreadDoNotLeakUserId() throws Exception {
		when(verifier.verify(TOKEN)).thenReturn(new VerifiedExternalIdentity(ISSUER, SUBJECT));
		when(provisioning.resolve(ISSUER, SUBJECT)).thenReturn(new IdentityResolution.Active(USER_ID));
		MockHttpServletRequest request1 = bearerRequest();
		MockHttpServletResponse response1 = new MockHttpServletResponse();

		filter.doFilter(request1, response1, (req, res) -> {
			assertThat(MDC.get(LoggingPolicy.KEY_USER_ID)).isEqualTo(USER_ID.value().toString());
		});
		assertThat(MDC.get(LoggingPolicy.KEY_USER_ID)).isNull();

		// Second request is unauthenticated, should not see USER_ID
		MockHttpServletResponse response2 = new MockHttpServletResponse();
		filter.doFilter(new MockHttpServletRequest("GET", "/me"), response2, chain);
		assertThat(MDC.get(LoggingPolicy.KEY_USER_ID)).isNull();
	}

	@Test
	void deletedResolutionReturns401() throws Exception {
		when(verifier.verify(TOKEN)).thenReturn(new VerifiedExternalIdentity(ISSUER, SUBJECT));
		when(provisioning.resolve(ISSUER, SUBJECT)).thenReturn(new IdentityResolution.Deleted(USER_ID));
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(bearerRequest(), response, chain);

		assertThat(response.getStatus()).isEqualTo(401);
		verify(verifier).verify(TOKEN);
		verify(provisioning).resolve(ISSUER, SUBJECT);
		verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
		assertThat(MDC.get(LoggingPolicy.KEY_USER_ID)).isNull();
	}

	@Test
	void tokenVerificationExceptionReturns401WithoutLeakingToken() throws Exception {
		when(verifier.verify(TOKEN)).thenThrow(new TokenVerificationException(TokenVerificationFailure.INVALID));
		Logger logger = (Logger) LoggerFactory.getLogger("com.aifashionvault.platform.security");
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		MockHttpServletResponse response = new MockHttpServletResponse();
		try {
			filter.doFilter(bearerRequest(), response, chain);

			assertThat(response.getStatus()).isEqualTo(401);
			assertThat(response.getContentAsString()).doesNotContain(TOKEN, "Authorization", "super-secret-payload");
			assertThat(response.getHeaderNames()).allSatisfy(name ->
				assertThat(response.getHeader(name)).doesNotContain(TOKEN));
			assertThat(appender.list).allSatisfy(event ->
				assertThat(event.getFormattedMessage()).doesNotContain(TOKEN, "Authorization", "super-secret-payload"));
			verify(provisioning, never()).resolve(anyString(), anyString());
			verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
			assertThat(MDC.get(LoggingPolicy.KEY_USER_ID)).isNull();
		}
		finally {
			logger.detachAppender(appender);
		}
	}

	@Test
	void missingIdentityProvisioningReturns401() throws Exception {
		when(verifier.verify(TOKEN)).thenReturn(new VerifiedExternalIdentity(ISSUER, SUBJECT));
		when(provisioningProvider.getIfAvailable()).thenReturn(null);
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(bearerRequest(), response, chain);

		assertThat(response.getStatus()).isEqualTo(401);
		verify(verifier).verify(TOKEN);
		verify(provisioning, never()).resolve(anyString(), anyString());
		verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
		assertThat(MDC.get(LoggingPolicy.KEY_USER_ID)).isNull();
	}

	@Test
	void cleansUpMdcEvenWhenDownstreamThrows() {
		when(verifier.verify(TOKEN)).thenReturn(new VerifiedExternalIdentity(ISSUER, SUBJECT));
		when(provisioning.resolve(ISSUER, SUBJECT)).thenReturn(new IdentityResolution.Active(USER_ID));
		MockHttpServletRequest request = bearerRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();

		try {
			filter.doFilter(request, response, (req, res) -> {
				throw new ServletException("Controller boom");
			});
		}
		catch (Exception ignored) {
		}

		assertThat(MDC.get(LoggingPolicy.KEY_USER_ID)).isNull();
	}

	@Test
	void getActuatorHealthDoesNotRequireAuthentication() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
		request.setServletPath("/actuator/health");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, chain);

		verifyNoInteractions(verifier);
		verify(chain).doFilter(request, response);
		assertThat(response.getStatus()).isEqualTo(200);
	}

	@Test
	void missingBearerTokenRemovesStaleUserIdDuringFilterAndCleansUpAfterward() throws Exception {
		String staleUserId = "stale-user-uuid";
		MDC.put(LoggingPolicy.KEY_USER_ID, staleUserId);
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(new MockHttpServletRequest("GET", "/me"), response, chain);

		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(MDC.get(LoggingPolicy.KEY_USER_ID)).isNull();
	}

	@Test
	void invalidBearerTokenRemovesStaleUserIdDuringFilterAndCleansUpAfterward() throws Exception {
		when(verifier.verify(TOKEN)).thenThrow(new TokenVerificationException(TokenVerificationFailure.INVALID));
		String staleUserId = "stale-user-uuid";
		MDC.put(LoggingPolicy.KEY_USER_ID, staleUserId);
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(bearerRequest(), response, chain);

		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(MDC.get(LoggingPolicy.KEY_USER_ID)).isNull();
	}

	@Test
	void missingIdentityProvisioningRemovesStaleUserIdDuringFilterAndCleansUpAfterward() throws Exception {
		when(verifier.verify(TOKEN)).thenReturn(new VerifiedExternalIdentity(ISSUER, SUBJECT));
		when(provisioningProvider.getIfAvailable()).thenReturn(null);
		String staleUserId = "stale-user-uuid";
		MDC.put(LoggingPolicy.KEY_USER_ID, staleUserId);
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(bearerRequest(), response, chain);

		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(MDC.get(LoggingPolicy.KEY_USER_ID)).isNull();
	}

	@Test
	void deletedResolutionRemovesStaleUserIdDuringFilterAndCleansUpAfterward() throws Exception {
		when(verifier.verify(TOKEN)).thenReturn(new VerifiedExternalIdentity(ISSUER, SUBJECT));
		when(provisioning.resolve(ISSUER, SUBJECT)).thenReturn(new IdentityResolution.Deleted(USER_ID));
		String staleUserId = "stale-user-uuid";
		MDC.put(LoggingPolicy.KEY_USER_ID, staleUserId);
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(bearerRequest(), response, chain);

		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(MDC.get(LoggingPolicy.KEY_USER_ID)).isNull();
	}

	@Test
	void activeResolutionRemovesStaleUserIdEvenWhenDownstreamThrows() {
		when(verifier.verify(TOKEN)).thenReturn(new VerifiedExternalIdentity(ISSUER, SUBJECT));
		when(provisioning.resolve(ISSUER, SUBJECT)).thenReturn(new IdentityResolution.Active(USER_ID));
		String staleUserId = "stale-user-uuid";
		MDC.put(LoggingPolicy.KEY_USER_ID, staleUserId);
		MockHttpServletRequest request = bearerRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();

		try {
			filter.doFilter(request, response, (req, res) -> {
				assertThat(MDC.get(LoggingPolicy.KEY_USER_ID)).isEqualTo(USER_ID.value().toString());
				throw new ServletException("Downstream error");
			});
		}
		catch (Exception ignored) {
		}

		assertThat(MDC.get(LoggingPolicy.KEY_USER_ID)).isNull();
	}

	private static MockHttpServletRequest bearerRequest() {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/me");
		request.addHeader("Authorization", "Bearer " + TOKEN);
		return request;
	}
}
