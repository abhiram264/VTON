package com.aifashionvault.platform.web;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import com.aifashionvault.platform.logging.LoggingPolicy;

/**
 * Filter that establishes and echoes a validated UUID request correlation id in MDC and response headers.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class RequestIdFilter extends OncePerRequestFilter {

	public static final String HEADER_NAME = "X-Request-Id";

	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain
	) throws ServletException, IOException {
		String requestId = resolveRequestId(request.getHeader(HEADER_NAME));
		response.setHeader(HEADER_NAME, requestId);

		MDC.put(LoggingPolicy.KEY_REQUEST_ID, requestId);
		try {
			filterChain.doFilter(request, response);
		}
		finally {
			MDC.remove(LoggingPolicy.KEY_REQUEST_ID);
		}
	}

	private static String resolveRequestId(String header) {
		if (header != null && !header.isBlank()) {
			String candidate = header.trim();
			try {
				UUID parsed = UUID.fromString(candidate);
				if (parsed.toString().equalsIgnoreCase(candidate)) {
					return parsed.toString();
				}
			}
			catch (IllegalArgumentException ignored) {
			}
		}
		return UUID.randomUUID().toString();
	}
}
