package com.aifashionvault.platform.importer;

import java.net.URI;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifashionvault.importer.ResolutionStatus;
import com.aifashionvault.importer.SourceReference;
import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;

class GenericUrlResolverTest {

	private static final UserId OWNER = new UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
	private static final URI SOURCE_URL = URI.create("https://source.example.test/item");

	private final GenericUrlResolver resolver = new GenericUrlResolver();

	@Test
	void supportsAnySourceWithUrl() {
		assertThat(resolver.supports(new SourceReference(SOURCE_URL, null))).isTrue();
		assertThat(resolver.supports(new SourceReference(SOURCE_URL,
				new com.aifashionvault.shared.UploadId(UUID.randomUUID())))).isTrue();
	}

	@Test
	void resolvesNeedsImageWithSafeSourceReferenceOnly() {
		var result = resolver.resolve(new SourceReference(SOURCE_URL, null));

		assertThat(result.status()).isEqualTo(ResolutionStatus.NEEDS_IMAGE);
		assertThat(result.reason())
			.isEqualTo(com.aifashionvault.importer.ResolutionReason.URL_RESOLUTION_FAILED);
		assertThat(result.canonicalSourceUrl()).isEqualTo(SOURCE_URL);
		assertThat(result.storageKey()).isNull();
		assertThat(result.title()).isNull();
		assertThat(result.siteName()).isNull();
	}

	@Test
	void neverReportsReadyOrFabricatesMetadata() {
		var result = resolver.resolve(new SourceReference(SOURCE_URL, null));

		assertThat(result.status()).isNotEqualTo(ResolutionStatus.RESOLVED);
		assertThat(result.title()).isNull();
		assertThat(result.siteName()).isNull();
	}

	@Test
	void urlWithoutVerifiedUploadOnlyIsTheTerminalNeedImageCase() {
		// OWNER scoping is irrelevant to this resolver: it must behave identically
		// for every owner, confirming deterministic terminal behavior.
		assertThat(resolver.supports(new SourceReference(SOURCE_URL, null))).isTrue();
		assertThat(resolver.resolve(new SourceReference(SOURCE_URL, null)).status())
			.isEqualTo(ResolutionStatus.NEEDS_IMAGE);
	}
}
