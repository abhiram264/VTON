package com.aifashionvault.platform.security;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Supplier;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.aifashionvault.platform.environment.VaultEnvironmentProperties;

@Configuration(proxyBeanMethods = false)
public class ExternalTokenVerifierConfiguration {

	static final URI FIREBASE_JWKS_URI = URI.create(
		"https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com");

	@Bean
	ExternalTokenVerifier externalTokenVerifier(VaultEnvironmentProperties properties) {
		return new JwtExternalTokenVerifier(properties.firebase().projectId(), new LazyJwksDocument(FIREBASE_JWKS_URI));
	}

	private static final class LazyJwksDocument implements Supplier<String> {

		private final URI uri;
		private volatile String document;

		private LazyJwksDocument(URI uri) {
			this.uri = uri;
		}

		@Override
		public String get() {
			String cached = document;
			if (cached != null) {
				return cached;
			}
			synchronized (this) {
				if (document == null) {
					document = fetch(uri);
				}
				return document;
			}
		}

		private static String fetch(URI uri) {
			try {
				HttpClient client = HttpClient.newBuilder()
					.connectTimeout(Duration.ofSeconds(5))
					.build();
				HttpResponse<String> response = client.send(
					HttpRequest.newBuilder(uri)
						.timeout(Duration.ofSeconds(5))
						.GET()
						.build(),
					HttpResponse.BodyHandlers.ofString());
				if (response.statusCode() != 200 || response.body() == null || response.body().isBlank()) {
					throw new IllegalStateException("jwks");
				}
				return response.body();
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("jwks", ex);
			}
			catch (IOException ex) {
				throw new IllegalStateException("jwks", ex);
			}
		}
	}
}
