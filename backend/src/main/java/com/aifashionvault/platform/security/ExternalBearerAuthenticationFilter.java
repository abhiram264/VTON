package com.aifashionvault.platform.security;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.aifashionvault.platform.logging.LoggingPolicy;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.user.IdentityProvisioning;
import com.aifashionvault.user.IdentityResolution;

public final class ExternalBearerAuthenticationFilter extends OncePerRequestFilter {

	private static final String BEARER_PREFIX = "Bearer ";
	private static final String HEALTH_PATH = "/actuator/health";

	private final ExternalTokenVerifier verifier;
	private final ObjectProvider<IdentityProvisioning> identityProvisioning;

	public ExternalBearerAuthenticationFilter(
		ExternalTokenVerifier verifier,
		ObjectProvider<IdentityProvisioning> identityProvisioning
	) {
		if (verifier == null || identityProvisioning == null) {
			throw new IllegalArgumentException("verifier and identityProvisioning are required");
		}
		this.verifier = verifier;
		this.identityProvisioning = identityProvisioning;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		if (!HttpMethod.GET.matches(request.getMethod())) {
			return false;
		}
		return HEALTH_PATH.equals(request.getServletPath()) || HEALTH_PATH.equals(request.getRequestURI());
	}

	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain
	) throws ServletException, IOException {
		MDC.remove(LoggingPolicy.KEY_USER_ID);
		try {
			String token = bearerToken(request.getHeader(HttpHeaders.AUTHORIZATION));
			if (token == null) {
				unauthorized(response);
				return;
			}
			try {
				VerifiedExternalIdentity identity = verifier.verify(token);
				IdentityProvisioning provisioning = identityProvisioning.getIfAvailable();
				if (provisioning == null) {
					unauthorized(response);
					return;
				}
				if (provisioning.resolve(identity.issuer(), identity.subject()) instanceof IdentityResolution.Active active) {
					authenticate(active.userId());
					MDC.put(LoggingPolicy.KEY_USER_ID, active.userId().value().toString());
					filterChain.doFilter(request, response);
					return;
				}
				unauthorized(response);
			}
			catch (TokenVerificationException exception) {
				unauthorized(response);
			}
		}
		finally {
			MDC.remove(LoggingPolicy.KEY_USER_ID);
		}
	}

	private static String bearerToken(String authorization) {
		if (authorization == null || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
			return null;
		}
		String token = authorization.substring(BEARER_PREFIX.length()).trim();
		return token.isEmpty() ? null : token;
	}

	private static void authenticate(UserId userId) {
		UsernamePasswordAuthenticationToken authentication =
			UsernamePasswordAuthenticationToken.authenticated(userId, null, List.of());
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(authentication);
		SecurityContextHolder.setContext(context);
	}

	private static void unauthorized(HttpServletResponse response) {
		SecurityContextHolder.clearContext();
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
	}
}
