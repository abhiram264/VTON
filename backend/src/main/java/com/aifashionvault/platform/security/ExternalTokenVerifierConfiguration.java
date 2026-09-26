package com.aifashionvault.platform.security;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Function;
import java.util.function.LongSupplier;
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

	static final class LazyJwksDocument implements JwtExternalTokenVerifier.JwksSource {

		static final long REFRESH_INTERVAL_NANOS = Duration.ofHours(1).toNanos();
		static final long FORCED_REFRESH_INTERVAL_NANOS = Duration.ofMinutes(1).toNanos();

		private final URI uri;
		private final LongSupplier nanoSource;
		private final Function<URI, String> fetcher;
		private volatile String document;
		private volatile long fetchedAtNanos;
		private volatile long lastForcedRefreshNanos;

		LazyJwksDocument(URI uri) {
			this(uri, System::nanoTime, LazyJwksDocument::fetch);
		}

		LazyJwksDocument(URI uri, LongSupplier nanoSource) {
			this(uri, nanoSource, LazyJwksDocument::fetch);
		}

		LazyJwksDocument(URI uri, LongSupplier nanoSource, Function<URI, String> fetcher) {
			this.uri = uri;
			this.nanoSource = nanoSource != null ? nanoSource : System::nanoTime;
			this.fetcher = fetcher != null ? fetcher : LazyJwksDocument::fetch;
		}

		@Override
		public String get() {
			String cached = document;
			if (cached != null && nanoSource.getAsLong() - fetchedAtNanos < REFRESH_INTERVAL_NANOS) {
				return cached;
			}
			synchronized (this) {
				cached = document;
				if (cached == null || nanoSource.getAsLong() - fetchedAtNanos >= REFRESH_INTERVAL_NANOS) {
					cached = fetchAndCache();
				}
				return cached;
			}
		}

		@Override
		public synchronized String refresh() {
			long now = nanoSource.getAsLong();
			if (document != null && lastForcedRefreshNanos != 0
					&& now - lastForcedRefreshNanos < FORCED_REFRESH_INTERVAL_NANOS) {
				return document;
			}
			lastForcedRefreshNanos = now;
			return fetchAndCache();
		}

		private String fetchAndCache() {
			String fetched = fetcher.apply(uri);
			fetchedAtNanos = nanoSource.getAsLong();
			document = fetched;
			return fetched;
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
