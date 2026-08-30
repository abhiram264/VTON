package com.aifashionvault.platform.importer;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifashionvault.importer.ImportMediaAccess;
import com.aifashionvault.importer.ImportResolver;
import com.aifashionvault.importer.ResolutionStatus;
import com.aifashionvault.importer.SourceReference;
import com.aifashionvault.shared.ShareImportId;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SharedImageResolverTest {

	private static final UserId OWNER = new UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
	private static final ShareImportId IMPORT_ID =
			new ShareImportId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
	private static final UploadId UPLOAD_ID =
			new UploadId(UUID.fromString("33333333-3333-3333-3333-333333333333"));
	private static final URI SOURCE_URL = URI.create("https://source.example.test/item");
	private static final ImportMediaAccess.VerifiedImage VERIFIED = new ImportMediaAccess.VerifiedImage(
			UPLOAD_ID, "image/png", 4096L, "version-1");

	private final Fakes.FakeMediaAccess media = new Fakes.FakeMediaAccess();
	private final Fakes.FakeTransport transport = new Fakes.FakeTransport();
	private final SharedImageResolver resolver = new SharedImageResolver(media, OWNER, IMPORT_ID);

	private SourceReference imageOnly() {
		return new SourceReference(null, UPLOAD_ID);
	}

	private SourceReference imageAndUrl() {
		return new SourceReference(SOURCE_URL, UPLOAD_ID);
	}

	@Test
	void supportsOnlySourcesWithVerifiedUpload() {
		assertThat(resolver.supports(imageOnly())).isTrue();
		assertThat(resolver.supports(imageAndUrl())).isTrue();
		assertThat(resolver.supports(new SourceReference(SOURCE_URL, null))).isFalse();
	}

	@Test
	void claimableUploadResolvesReadyUnderOwnerImportKey() {
		media.verified.put(UPLOAD_ID, VERIFIED);

		var result = resolver.resolve(imageOnly());

		assertThat(result.status()).isEqualTo(ResolutionStatus.RESOLVED);
		assertThat(result.storageKey()).isNotNull();
		assertThat(Fakes.keyText(result.storageKey()))
			.contains(OWNER.value().toString())
			.contains(IMPORT_ID.value().toString());
		assertThat(media.claimed).hasSize(1);
	}

	@Test
	void imageUrlKeepsUrlAsMetadataOnlyWithoutAnyFetch() {
		media.verified.put(UPLOAD_ID, VERIFIED);

		var result = resolver.resolve(imageAndUrl());

		assertThat(result.status()).isEqualTo(ResolutionStatus.RESOLVED);
		assertThat(result.canonicalSourceUrl()).isEqualTo(SOURCE_URL);
		assertThat(transport.requested).isEmpty();
	}

	@Test
	void unclaimableUploadFallsThroughAsUnsupported() {
		assertThat(resolver.resolve(imageOnly()).status()).isEqualTo(ResolutionStatus.UNSUPPORTED);
		assertThat(media.claimed).isEmpty();
	}

	@Test
	void conflictingClaimFallsThroughAsUnsupported() {
		media.verified.put(UPLOAD_ID, VERIFIED);
		SharedImageResolver claiming = new SharedImageResolver(media, OWNER, IMPORT_ID);
		media.conflictOn = claiming.resolve(imageOnly()).storageKey();

		var second = resolver.resolve(imageOnly());

		assertThat(second.status()).isEqualTo(ResolutionStatus.UNSUPPORTED);
	}

	@Test
	void objectStoreUnavailabilityIsRetryableDependencyFailure() {
		media.verified.put(UPLOAD_ID, VERIFIED);
		media.unavailable = true;

		assertThatThrownBy(() -> resolver.resolve(imageOnly()))
			.isInstanceOf(ImporterRetryableException.class)
			.extracting(ex -> Fakes.retryableReason(ex))
			.isEqualTo(com.aifashionvault.importer.ResolutionReason.DEPENDENCY_UNAVAILABLE);
	}

	@Test
	void sharedImageWinsOverGenericChainOnImageBearingSource() {
		media.verified.put(UPLOAD_ID, VERIFIED);
		ImportResolver chain = new ImportResolver(List.of(
				resolver,
				new GenericOpenGraphResolver(
						transport, new Fakes.FakeDns(), new com.aifashionvault.platform.objectstore.ImageInspector(),
						media, OWNER, IMPORT_ID, "attempt-1"),
				new GenericUrlResolver()));

		var result = chain.resolve(imageAndUrl());

		assertThat(result.status()).isEqualTo(ResolutionStatus.RESOLVED);
		assertThat(transport.requested).isEmpty();
	}
}
