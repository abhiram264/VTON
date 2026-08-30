package com.aifashionvault.platform.fashn;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class FashnClient {
    static final int MAX_BODY_BYTES = (int) (8L * 1024 * 1024);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RETRY_AFTER_HEADER = "Retry-After";
    private static final String DATE_HEADER = "Date";

    private final RestClient restClient;
    private final Clock clock;

    public FashnClient(RestClient restClient) {
        this(restClient, Clock.systemUTC());
    }

    FashnClient(RestClient restClient, Clock clock) {
        if (restClient == null) {
            throw new IllegalArgumentException("REST client is required");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock is required");
        }
        this.restClient = restClient;
        this.clock = clock;
    }

    public FashnHttpResponse postRun(Map<String, Object> payload) {
        byte[] json = encode(payload);
        try {
            return restClient.post()
                .uri("/v1/run")
                .contentType(MediaType.APPLICATION_JSON)
                .body(json)
                .exchange((request, response) -> read(response, clock.instant()));
        } catch (ResourceAccessException ex) {
            return translate(ex);
        } catch (RestClientException ex) {
            return FashnHttpResponse.indeterminate();
        }
    }

    public FashnHttpResponse getStatus(String predictionId) {
        try {
            return restClient.get()
                .uri("/v1/status/{id}", predictionId)
                .exchange((request, response) -> read(response, clock.instant()));
        } catch (ResourceAccessException ex) {
            return translate(ex);
        } catch (RestClientException ex) {
            return FashnHttpResponse.indeterminate();
        }
    }

    @Override
    public String toString() {
        return "FashnClient[]";
    }

    private static byte[] encode(Map<String, Object> payload) {
        try {
            return MAPPER.writeValueAsBytes(payload);
        } catch (JacksonException ex) {
            throw new IllegalStateException("request encoding failed");
        }
    }

    private static FashnHttpResponse read(
        RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response,
        Instant responseTime
    ) throws IOException {
        int status = response.getStatusCode().value();
        HttpHeaders headers = response.getHeaders();
        String credits = firstHeader(headers, FashnMapper.CREDITS_HEADER);
        Instant dateHeaderAt = parseDateHeader(firstHeader(headers, DATE_HEADER));
        Instant retryAfterAt = FashnHttpResponse.parseRetryAfter(
            firstHeader(headers, RETRY_AFTER_HEADER), responseTime, dateHeaderAt);
        byte[] body = readAtMost(response.getBody(), MAX_BODY_BYTES);
        if (body == null) {
            return FashnHttpResponse.http(status, credits, null, true, retryAfterAt, dateHeaderAt);
        }
        return FashnHttpResponse.http(status, credits, parse(body), false, retryAfterAt, dateHeaderAt);
    }

    private static JsonNode parse(byte[] body) {
        if (body.length == 0) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(body);
            return node == null || node.isNull() ? null : node;
        } catch (JacksonException ex) {
            return null;
        }
    }

    private static byte[] readAtMost(InputStream body, int maxBytes) throws IOException {
        if (body == null) {
            return new byte[0];
        }
        byte[] read = body.readNBytes(maxBytes + 1);
        if (read.length > maxBytes) {
            return null;
        }
        return read;
    }

    private static String firstHeader(HttpHeaders headers, String name) {
        if (headers == null) {
            return null;
        }
        String value = headers.getFirst(name);
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static Instant parseDateHeader(String value) {
        if (value == null) {
            return null;
        }
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeException ex) {
            return null;
        }
    }

    private static FashnHttpResponse translate(ResourceAccessException ex) {
        Throwable cause = rootCause(ex);
        if (cause instanceof ConnectException
            || cause instanceof UnknownHostException
            || cause instanceof NoRouteToHostException
            || cause instanceof UnresolvedAddressException) {
            return FashnHttpResponse.unsent();
        }
        return FashnHttpResponse.indeterminate();
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
