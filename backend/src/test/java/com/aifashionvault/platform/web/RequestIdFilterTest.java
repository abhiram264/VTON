package com.aifashionvault.platform.web;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.aifashionvault.platform.logging.LoggingPolicy;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdFilterTest {

	private RequestIdFilter filter;

	@BeforeEach
	void setUp() {
		filter = new RequestIdFilter();
		MDC.clear();
	}

	@AfterEach
	void tearDown() {
		MDC.clear();
	}

	@Test
	void validUuidInboundHeaderIsReusedAndEchoed() throws Exception {
		String inputUuid = "123e4567-e89b-12d3-a456-426614174000";
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader(RequestIdFilter.HEADER_NAME, inputUuid);
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, (req, res) -> {
			assertThat(MDC.get(LoggingPolicy.KEY_REQUEST_ID)).isEqualTo(inputUuid);
		});

		assertThat(response.getHeader(RequestIdFilter.HEADER_NAME)).isEqualTo(inputUuid);
		assertThat(MDC.get(LoggingPolicy.KEY_REQUEST_ID)).isNull();
	}

	@Test
	void missingHeaderGeneratesValidUuidAndEchoes() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();

		final String[] seen = new String[1];
		filter.doFilter(request, response, (req, res) -> {
			seen[0] = MDC.get(LoggingPolicy.KEY_REQUEST_ID);
		});

		assertThat(seen[0]).isNotNull();
		assertThat(UUID.fromString(seen[0])).isNotNull();
		assertThat(response.getHeader(RequestIdFilter.HEADER_NAME)).isEqualTo(seen[0]);
		assertThat(MDC.get(LoggingPolicy.KEY_REQUEST_ID)).isNull();
	}

	@Test
	void malformedOrOversizedHeaderProducesFreshUuid() throws Exception {
		String malformed = "not-a-valid-uuid-with-garbage";
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader(RequestIdFilter.HEADER_NAME, malformed);
		MockHttpServletResponse response = new MockHttpServletResponse();

		final String[] seen = new String[1];
		filter.doFilter(request, response, (req, res) -> {
			seen[0] = MDC.get(LoggingPolicy.KEY_REQUEST_ID);
		});

		assertThat(seen[0]).isNotNull();
		assertThat(seen[0]).isNotEqualTo(malformed);
		assertThat(UUID.fromString(seen[0])).isNotNull();
		assertThat(response.getHeader(RequestIdFilter.HEADER_NAME)).isEqualTo(seen[0]);
		assertThat(MDC.get(LoggingPolicy.KEY_REQUEST_ID)).isNull();
	}

	@Test
	void removesStaleMdcValueInFinally() throws Exception {
		String prior = "prior-request-id";
		MDC.put(LoggingPolicy.KEY_REQUEST_ID, prior);

		MockHttpServletRequest request = new MockHttpServletRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, (req, res) -> {
			assertThat(MDC.get(LoggingPolicy.KEY_REQUEST_ID)).isNotEqualTo(prior);
		});

		assertThat(MDC.get(LoggingPolicy.KEY_REQUEST_ID)).isNull();
	}

	@Test
	void consecutiveRequestsOnSameThreadDoNotLeakRequestId() throws Exception {
		MockHttpServletRequest req1 = new MockHttpServletRequest();
		MockHttpServletResponse res1 = new MockHttpServletResponse();
		String[] captured1 = new String[1];
		filter.doFilter(req1, res1, (req, res) -> {
			captured1[0] = MDC.get(LoggingPolicy.KEY_REQUEST_ID);
		});

		assertThat(captured1[0]).isNotNull();
		assertThat(MDC.get(LoggingPolicy.KEY_REQUEST_ID)).isNull();

		MockHttpServletRequest req2 = new MockHttpServletRequest();
		MockHttpServletResponse res2 = new MockHttpServletResponse();
		String[] captured2 = new String[1];
		filter.doFilter(req2, res2, (req, res) -> {
			captured2[0] = MDC.get(LoggingPolicy.KEY_REQUEST_ID);
		});

		assertThat(captured2[0]).isNotNull();
		assertThat(captured2[0]).isNotEqualTo(captured1[0]);
		assertThat(MDC.get(LoggingPolicy.KEY_REQUEST_ID)).isNull();
	}

	@Test
	void cleansUpEvenWhenDownstreamThrows() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();

		try {
			filter.doFilter(request, response, (req, res) -> {
				throw new ServletException("Downstream boom");
			});
		}
		catch (Exception ignored) {
		}

		assertThat(MDC.get(LoggingPolicy.KEY_REQUEST_ID)).isNull();
	}
}
