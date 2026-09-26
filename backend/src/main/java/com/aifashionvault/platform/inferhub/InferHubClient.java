package com.aifashionvault.platform.inferhub;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class InferHubClient {
    static final int MAX_BODY_BYTES = (int) (8L * 1024 * 1024);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient restClient;

    public InferHubClient(RestClient restClient) {
        if (restClient == null) {
            throw new IllegalArgumentException("REST client is required");
        }
        this.restClient = restClient;
    }

    /**
     * One blocking POST to the chat-completions endpoint. The owner-verified
     * generation latency is ~170s, so the client read timeout is 300s and this
     * call is expected to be long-lived.
     */
    public InferHubHttpResponse postRun(Map<String, Object> payload) {
        byte[] json = encode(payload);
        try {
            return restClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(json)
                .exchange((request, response) -> read(response));
        } catch (ResourceAccessException ex) {
            return translate(ex);
        } catch (RestClientException ex) {
            return InferHubHttpResponse.indeterminate();
        }
    }

    @Override
    public String toString() {
        return "InferHubClient[]";
    }

    private static byte[] encode(Map<String, Object> payload) {
        try {
            return MAPPER.writeValueAsBytes(payload);
        } catch (JacksonException ex) {
            throw new IllegalStateException("request encoding failed");
        }
    }

    private static InferHubHttpResponse read(
        RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response
    ) throws IOException {
        int status = response.getStatusCode().value();
        byte[] body = readAtMost(response.getBody(), MAX_BODY_BYTES);
        if (body == null) {
            return InferHubHttpResponse.http(status, null, true);
        }
        return InferHubHttpResponse.http(status, parse(body), false);
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

    private static InferHubHttpResponse translate(ResourceAccessException ex) {
        Throwable cause = rootCause(ex);
        if (cause instanceof ConnectException
            || cause instanceof UnknownHostException
            || cause instanceof NoRouteToHostException
            || cause instanceof UnresolvedAddressException) {
            return InferHubHttpResponse.unsent();
        }
        return InferHubHttpResponse.indeterminate();
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
