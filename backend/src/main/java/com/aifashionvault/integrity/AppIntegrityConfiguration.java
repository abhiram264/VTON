package com.aifashionvault.integrity;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AppIntegrityConfiguration.Properties.class)
public class AppIntegrityConfiguration {
    @Bean
    AppIntegrityVerifier appIntegrityVerifier(
        Properties properties,
        ObjectProvider<IntegrityEvidenceProvider> providers
    ) {
        IntegrityEvidenceProvider provider = providers.getIfAvailable(() -> ignored -> ProviderVerification.unavailable());
        return new DefaultAppIntegrityVerifier(
            properties.required(),
            new AndroidIntegrityAdapter(provider, properties.androidAppId()),
            new IosIntegrityAdapter(provider, properties.iosAppId()),
            Clock.systemUTC());
    }

    @ConfigurationProperties(prefix = "vault.integrity")
    public record Properties(boolean required, String androidAppId, String iosAppId) {
        public Properties() {
            this(false, null, null);
        }
        public Properties(boolean required) {
            this(required, null, null);
        }
    }
}
