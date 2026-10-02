package com.aifashionvault.platform.inferhub;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.aifashionvault.platform.environment.ConditionalOnNonBlankApiKey;
import com.aifashionvault.platform.objectstore.GenerationMediaStore;
import com.aifashionvault.platform.objectstore.ObjectStore;

/**
 * InferHub adapter wiring. Beans are gated on a non-blank {@code vault.inferhub.api-key}
 * so an absent {@code VTON_IMAGE_API_KEY} means no provider bean exists at all — the
 * same honest-unavailable posture {@code FashnConfig} establishes. The mapper is NOT
 * gated (pure translation logic, safe to construct), exactly like {@code FashnMapper}.
 * Registry composition and provider selection are owned by the selection seam
 * (ProviderRoutingConfiguration / FashnConfig.providerRegistry side), not here.
 */
@Configuration(proxyBeanMethods = false)
public class InferHubConfig {
    static final String BASE_URL = "https://api.inferhub.dev";
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /**
     * Owner-verified generation latency is ~170s; anything below 300s would tear down
     * a healthy generation. Hard acceptance gate, not a nicety.
     */
    static final Duration READ_TIMEOUT = Duration.ofSeconds(300);

    @Bean
    InferHubMapper inferHubMapper() {
        return new InferHubMapper();
    }

    @Bean
    @ConditionalOnNonBlankApiKey("vault.inferhub.api-key")
    RestClient inferHubRestClient(Environment environment) {
        return inferHubRestClientBuilder(environment.getProperty("vault.inferhub.api-key")).build();
    }

    @Bean
    @ConditionalOnNonBlankApiKey("vault.inferhub.api-key")
    InferHubClient inferHubClient(RestClient inferHubRestClient) {
        return new InferHubClient(inferHubRestClient);
    }

    @Bean
    @ConditionalOnNonBlankApiKey("vault.inferhub.api-key")
    InferHubVtonProvider inferHubVtonProvider(
        InferHubMapper inferHubMapper,
        InferHubClient inferHubClient,
        ObjectStore objectStore,
        GenerationMediaStore generationMediaStore
    ) {
        return new InferHubVtonProvider(inferHubMapper, inferHubClient, objectStore, generationMediaStore);
    }

    static RestClient.Builder inferHubRestClientBuilder(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("InferHub API key is required");
        }
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder()
            .baseUrl(BASE_URL)
            .requestFactory(requestFactory)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
    }
}
