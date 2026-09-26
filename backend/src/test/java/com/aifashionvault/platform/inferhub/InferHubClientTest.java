package com.aifashionvault.platform.inferhub;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.AbstractClientHttpRequest;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the InferHub HTTP client seam. Every response is served by an
 * in-process stub request factory: no network, no key material, no provider call.
 */
class InferHubClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String STUB_BASE_URL = "https://api.inferhub.test";

    private record Captured(URI uri, MediaType contentType, byte[] body) {
    }

    /**
     * Request-factory stub: captures the outgoing request and answers with a canned
     * status/body, or throws the configured failure from the execute path.
     */
    private static final class StubFactory implements ClientHttpRequestFactory {
        private final ClientHttpResponse response;
        private final IOException ioFailure;
        Captured captured;

        StubFactory(ClientHttpResponse response, IOException ioFailure) {
            this.response = response;
            this.ioFailure = ioFailure;
        }

        static StubFactory answering(int status, byte[] body) {
            return new StubFactory(new MockClientHttpResponse(body, status), null);
        }

        static StubFactory failing(IOException cause) {
            return new StubFactory(null, cause);
        }

        @Override
        public ClientHttpRequest createRequest(URI uri, HttpMethod method) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            return new AbstractClientHttpRequest() {
                @Override
                public HttpMethod getMethod() {
                    return method;
                }

                @Override
                public URI getURI() {
                    return uri;
                }

                @Override
                protected OutputStream getBodyInternal(HttpHeaders headers) {
                    return out;
                }

                @Override
                protected ClientHttpResponse executeInternal(HttpHeaders headers) throws IOException {
                    captured = new Captured(uri, headers.getContentType(), out.toByteArray());
                    if (ioFailure != null) {
                        throw ioFailure;
                    }
                    return response;
                }
            };
        }
    }

    private static InferHubClient client(StubFactory factory) {
        RestClient restClient = RestClient.builder()
            .baseUrl(STUB_BASE_URL)
            .requestFactory(factory)
            .build();
        return new InferHubClient(restClient);
    }

    private static Map<String, Object> payload() {
        return Map.of("model", "cb/gemini-3.1-flash-image", "messages", List.of());
    }

    @Test
    void postsJsonToTheChatCompletionsEndpoint() throws Exception {
        StubFactory factory = StubFactory.answering(200, "{}".getBytes(StandardCharsets.UTF_8));
        InferHubHttpResponse response = client(factory).postRun(payload());

        assertThat(factory.captured).isNotNull();
        assertThat(factory.captured.uri()).isEqualTo(URI.create(STUB_BASE_URL + "/v1/chat/completions"));
        assertThat(factory.captured.contentType()).isEqualTo(MediaType.APPLICATION_JSON);
        JsonNode sent = MAPPER.readTree(factory.captured.body());
        assertThat(sent.get("model").asText()).isEqualTo("cb/gemini-3.1-flash-image");
        assertThat(sent.get("messages").isArray()).isTrue();
        assertThat(response.outcome()).isEqualTo(InferHubHttpResponse.Outcome.HTTP);
        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void returnsHttpStatusWithParsedBody() {
        byte[] body = "{\"error\":\"upstream\"}".getBytes(StandardCharsets.UTF_8);
        InferHubHttpResponse response = client(StubFactory.answering(502, body)).postRun(payload());

        assertThat(response.outcome()).isEqualTo(InferHubHttpResponse.Outcome.HTTP);
        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(response.bodyOversized()).isFalse();
        assertThat(response.body().get("error").asText()).isEqualTo("upstream");
    }

    @Test
    void oversizedBodyIsFlaggedAndUnparsed() {
        byte[] body = new byte[InferHubClient.MAX_BODY_BYTES + 1];
        InferHubHttpResponse response = client(StubFactory.answering(200, body)).postRun(payload());

        assertThat(response.outcome()).isEqualTo(InferHubHttpResponse.Outcome.HTTP);
        assertThat(response.bodyOversized()).isTrue();
        assertThat(response.body()).isNull();
    }

    @Test
    void emptyBodyParsesToNullWithoutOversizeFlag() {
        InferHubHttpResponse response = client(StubFactory.answering(200, new byte[0])).postRun(payload());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isNull();
        assertThat(response.bodyOversized()).isFalse();
    }

    @Test
    void connectFailureIsClassifiedUnsent() {
        StubFactory factory = StubFactory.failing(new IOException("refused", new ConnectException("refused")));
        InferHubHttpResponse response = client(factory).postRun(payload());

        assertThat(response.outcome()).isEqualTo(InferHubHttpResponse.Outcome.UNSENT);
        assertThat(response.statusCode()).isEqualTo(0);
    }

    @Test
    void midRequestTimeoutIsClassifiedIndeterminate() {
        StubFactory factory = StubFactory.failing(new SocketTimeoutException("Read timed out"));
        InferHubHttpResponse response = client(factory).postRun(payload());

        assertThat(response.outcome()).isEqualTo(InferHubHttpResponse.Outcome.INDETERMINATE);
        assertThat(response.statusCode()).isEqualTo(0);
    }

    /**
     * Pins the timeout where it must land: the request factory the built RestClient
     * actually uses, not just the constant. JdkClientHttpRequestFactory has no
     * read-timeout getter, so the built factory's field is read reflectively —
     * spring-web is a pinned compile dependency.
     */
    @Test
    void timeoutConfigReachesTheBuiltRestClientFactory() throws Exception {
        assertThat(InferHubConfig.READ_TIMEOUT).isGreaterThanOrEqualTo(Duration.ofSeconds(300));
        assertThat(InferHubConfig.CONNECT_TIMEOUT).isEqualTo(Duration.ofSeconds(10));

        RestClient.Builder builder = InferHubConfig.inferHubRestClientBuilder("test-key");
        java.lang.reflect.Field factoryField = builder.getClass().getDeclaredField("requestFactory");
        factoryField.setAccessible(true);
        Object factory = factoryField.get(builder);
        assertThat(factory).isInstanceOf(JdkClientHttpRequestFactory.class);

        java.lang.reflect.Field readTimeout = JdkClientHttpRequestFactory.class.getDeclaredField("readTimeout");
        readTimeout.setAccessible(true);
        assertThat((Duration) readTimeout.get(factory)).isEqualTo(Duration.ofSeconds(300));
    }
}
