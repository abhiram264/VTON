package com.aifashionvault.importer;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportResolverTest {

	private static final URI SOURCE_URL = URI.create("https://example.test/page");
	private static final UploadId UPLOAD_ID = new UploadId(UUID.fromString("44444444-4444-4444-4444-444444444444"));
	private static final StorageKey RESOLVED_KEY = new StorageKey("imports/private/resolved-object");

	@Test
	void firstSupportingResolverWins() {
		RecordingResolver first = new RecordingResolver(ResolutionStatus.NEEDS_IMAGE, ResolutionReason.URL_RESOLUTION_FAILED);
		RecordingResolver second = new RecordingResolver(ResolutionStatus.RESOLVED, null);
		ImportResolver resolver = new ImportResolver(List.of(first, second));
		SourceReference source = new SourceReference(SOURCE_URL, UPLOAD_ID);

		ResolutionResult result = resolver.resolve(source);

		assertThat(result.status()).isEqualTo(ResolutionStatus.NEEDS_IMAGE);
		assertThat(result.reason()).isEqualTo(ResolutionReason.URL_RESOLUTION_FAILED);
		assertThat(first.invocations).isEqualTo(1);
		assertThat(second.invocations).isZero();
	}

	@Test
	void unsupportedFallthroughReachesLaterResolver() {
		RecordingResolver unsupported = new RecordingResolver(ResolutionStatus.UNSUPPORTED, null);
		RecordingResolver winner = new RecordingResolver(ResolutionStatus.RESOLVED, null);
		ImportResolver resolver = new ImportResolver(List.of(unsupported, winner));
		SourceReference source = new SourceReference(SOURCE_URL, UPLOAD_ID);

		ResolutionResult result = resolver.resolve(source);

		assertThat(result.status()).isEqualTo(ResolutionStatus.RESOLVED);
		assertThat(result.storageKey()).isEqualTo(RESOLVED_KEY);
		assertThat(unsupported.invocations).isEqualTo(1);
		assertThat(winner.invocations).isEqualTo(1);
	}

	@Test
	void noResolverSupportsMeansUnsupportedResult() {
		RecordingResolver never = new RecordingResolver(ResolutionStatus.RESOLVED, null) {
			@Override
			public boolean supports(SourceReference source) {
				return false;
			}
		};
		ImportResolver resolver = new ImportResolver(List.of(never));

		ResolutionResult result = resolver.resolve(new SourceReference(SOURCE_URL, UPLOAD_ID));

		assertThat(result.status()).isEqualTo(ResolutionStatus.UNSUPPORTED);
		assertThat(never.invocations).isZero();
	}

	@Test
	void resolverRequiresAtLeastOneSource() {
		assertThatThrownBy(() -> new ImportResolver(List.of()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ImportResolver((List<SourceResolver>) null))
			.isInstanceOf(NullPointerException.class);
	}

	@Test
	void nullResolverResultIsRejected() {
		SourceResolver broken = new SourceResolver() {
			@Override
			public boolean supports(SourceReference source) {
				return true;
			}

			@Override
			public ResolutionResult resolve(SourceReference source) {
				return null;
			}
		};
		ImportResolver resolver = new ImportResolver(List.of(broken));

		assertThatThrownBy(() -> resolver.resolve(new SourceReference(SOURCE_URL, UPLOAD_ID)))
			.isInstanceOf(NullPointerException.class);
	}

	@Test
	void commandShapeValidation() {
		UserId owner = new UserId(UUID.fromString("55555555-5555-5555-5555-555555555555"));
		String key = "command-key-0123456789";
		assertThatThrownBy(() -> new ImportCommand(owner, key, ShareImportKind.URL,
			URI.create("ftp://example.test/item"), null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ImportCommand(owner, key, ShareImportKind.URL, SOURCE_URL, UPLOAD_ID))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ImportCommand(owner, key, ShareImportKind.IMAGE, SOURCE_URL, null))
			.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new ImportCommand(owner, key, ShareImportKind.IMAGE_URL, SOURCE_URL, null))
			.isInstanceOf(NullPointerException.class);
	}

	private static class RecordingResolver implements SourceResolver {

		private final ResolutionStatus status;
		private final ResolutionReason reason;
		private int invocations;

		RecordingResolver(ResolutionStatus status, ResolutionReason reason) {
			this.status = status;
			this.reason = reason;
		}

		@Override
		public boolean supports(SourceReference source) {
			return true;
		}

		@Override
		public ResolutionResult resolve(SourceReference source) {
			invocations++;
			return new ResolutionResult(status,
				status == ResolutionStatus.RESOLVED ? RESOLVED_KEY : null,
				null, null, null, reason);
		}
	}
}
