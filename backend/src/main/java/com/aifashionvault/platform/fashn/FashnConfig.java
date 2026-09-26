package com.aifashionvault.platform.fashn;

import java.net.http.HttpClient;
import java.time.Duration;

import com.aifashionvault.platform.environment.ConditionalOnNonBlankApiKey;
import com.aifashionvault.platform.environment.VaultEnvironmentProperties;
import com.aifashionvault.platform.objectstore.ObjectStore;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class FashnConfig {
    static final String BASE_URL = "https://api.fashn.ai";
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    @Bean
    FashnMapper fashnMapper() {
        return new FashnMapper();
    }

    @Bean
    @ConditionalOnNonBlankApiKey("vault.fashn.api-key")
    RestClient fashnRestClient(VaultEnvironmentProperties properties) {
        return fashnRestClientBuilder(properties.fashn().apiKey()).build();
    }

    @Bean
    @ConditionalOnNonBlankApiKey("vault.fashn.api-key")
    FashnClient fashnClient(RestClient fashnRestClient) {
        return new FashnClient(fashnRestClient);
    }

    @Bean
    @ConditionalOnNonBlankApiKey("vault.fashn.api-key")
    FashnVtonProvider fashnVtonProvider(FashnMapper fashnMapper, FashnClient fashnClient, ObjectStore objectStore) {
        return new FashnVtonProvider(fashnMapper, fashnClient, objectStore);
    }

    // The ProviderRegistry bean moved to ProviderRegistryComposition, which composes every
    // registered VtonProvider with the deterministic selection order (forced-provider, then
    // inferhub, then fashn). Two ProviderRegistry @Bean methods would collide.

    /**
     * Whether an image provider is genuinely configured. A blank or absent API key means no
     * {@code ProviderRegistry} bean exists at all, so the default provider is honestly
     * unavailable rather than silently selected. Single source of truth for the
     * create-path admission gate in {@code GenerationCreationService}.
     */
    public static boolean providerConfigured(VaultEnvironmentProperties properties) {
        return properties != null && properties.fashn() != null
            && properties.fashn().apiKey() != null && !properties.fashn().apiKey().isBlank();
    }

    /**
     * Provider-agnostic admission predicate: true when ANY image provider's key is
     * genuinely non-blank (FASHN and/or InferHub). The create path must not answer
     * 503 GENERATION_PROVIDER_UNAVAILABLE on an InferHub-only setup, so the
     * FASHN-specific {@link #providerConfigured} is no longer used for admission.
     */
    public static boolean anyProviderConfigured(VaultEnvironmentProperties properties) {
        return providerConfigured(properties)
            || (properties != null && properties.inferhub() != null
                && properties.inferhub().apiKey() != null && !properties.inferhub().apiKey().isBlank());
    }

    static RestClient.Builder fashnRestClientBuilder(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("FASHN API key is required");
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
