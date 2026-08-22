package com.aifashionvault.platform.security;

import java.util.UUID;

import jakarta.servlet.FilterChain;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

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
	}

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void missingAuthorizationReturns401WithoutCallingVerify() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(new MockHttpServletRequest("GET", "/me"), response, chain);

		assertThat(response.getStatus()).isEqualTo(401);
		verifyNoInteractions(verifier);
		verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
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

	private static MockHttpServletRequest bearerRequest() {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/me");
		request.addHeader("Authorization", "Bearer " + TOKEN);
		return request;
	}
}
