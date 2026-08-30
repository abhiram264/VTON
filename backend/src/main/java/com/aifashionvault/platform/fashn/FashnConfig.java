package com.aifashionvault.platform.fashn;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.aifashionvault.generation.vton.DefaultProviderRegistry;
import com.aifashionvault.generation.vton.ProviderRegistry;
import com.aifashionvault.platform.environment.VaultEnvironmentProperties;
import com.aifashionvault.platform.objectstore.ObjectStore;

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
    RestClient fashnRestClient(VaultEnvironmentProperties properties) {
        return fashnRestClientBuilder(properties.fashn().apiKey()).build();
    }

    @Bean
    FashnClient fashnClient(RestClient fashnRestClient) {
        return new FashnClient(fashnRestClient);
    }

    @Bean
    FashnVtonProvider fashnVtonProvider(FashnMapper fashnMapper, FashnClient fashnClient, ObjectStore objectStore) {
        return new FashnVtonProvider(fashnMapper, fashnClient, objectStore);
    }

    @Bean
    ProviderRegistry providerRegistry(FashnVtonProvider fashnVtonProvider) {
        return new DefaultProviderRegistry(List.of(fashnVtonProvider), fashnVtonProvider.providerKey(), Map.of());
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
