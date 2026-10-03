package com.aifashionvault.platform.importer;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.time.Instant;
import java.util.Objects;

import com.aifashionvault.importer.ImportMediaAccess;
import com.aifashionvault.importer.ImportResolverLimits;
import com.aifashionvault.importer.ResolutionReason;
import com.aifashionvault.importer.ResolutionResult;
import com.aifashionvault.importer.ResolutionStatus;
import com.aifashionvault.importer.SourceReference;
import com.aifashionvault.importer.SourceResolver;
import com.aifashionvault.platform.objectstore.ImageInspector;
import com.aifashionvault.platform.objectstore.ImageMediaPolicy;
import com.aifashionvault.platform.objectstore.ImageMediaType;
import com.aifashionvault.platform.objectstore.ObjectStoreExceptions;
import com.aifashionvault.shared.ShareImportId;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UserId;

/**
 * Platform adapter resolving URL imports through bounded, SSRF-safe Open Graph
 * fetching. The HTML document and every OG image URL are fetched under the
 * identical URL/redirect/DNS policy and frozen byte/time budgets; image bytes
 * are inspected by the repository image policy and streamed into private
 * object storage under an owner/import/attempt-derived key. DNS, timeout,
 * remote 5xx, and object-store unavailability surface as retryable failures;
 * invalid or unsafe URLs and absent/unusable media fall through as
 * UNSUPPORTED so the deterministic GenericUrlResolver asks the shopper.
 */
public final class GenericOpenGraphResolver implements SourceResolver {

	private final ImportHttpFetcher fetcher;
	private final ImportDns dns;
	private final ImageInspector imageInspector;
	private final ImportMediaAccess mediaAccess;
	private final UserId ownerId;
	private final ShareImportId importId;
	private final String attemptToken;
	private final Clock clock;

	public GenericOpenGraphResolver(
			ImportHttpFetcher fetcher,
			ImportDns dns,
			ImageInspector imageInspector,
			ImportMediaAccess mediaAccess,
			UserId ownerId,
			ShareImportId importId,
			String attemptToken
	) {
		this(fetcher, dns, imageInspector, mediaAccess, ownerId, importId, attemptToken, Instant::now);
	}

	GenericOpenGraphResolver(
			ImportHttpFetcher fetcher,
			ImportDns dns,
			ImageInspector imageInspector,
			ImportMediaAccess mediaAccess,
			UserId ownerId,
			ShareImportId importId,
			String attemptToken,
			Clock clock
	) {
		this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
		this.dns = Objects.requireNonNull(dns, "dns");
		this.imageInspector = Objects.requireNonNull(imageInspector, "imageInspector");
		this.mediaAccess = Objects.requireNonNull(mediaAccess, "mediaAccess");
		this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
		this.importId = Objects.requireNonNull(importId, "importId");
		this.attemptToken = Objects.requireNonNull(attemptToken, "attemptToken");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	@Override
	public boolean supports(SourceReference source) {
		return source.url() != null;
	}

	@Override
	public ResolutionResult resolve(SourceReference source) {
		if (!supports(source)) {
			return unsupported();
		}
		try {
			return resolveUnderPolicy(source);
		}
		catch (ImportNetworkException ex) {
			return classify(ex);
		}
	}

	private ResolutionResult resolveUnderPolicy(SourceReference source) {
		Instant deadline = clock.instant().plus(ImportResolverLimits.TOTAL_RESOLUTION_TIMEOUT);
		BoundedFetch bounded = new BoundedFetch(fetcher, dns, clock);
		BoundedFetch.Fetched page = bounded.fetch(source.url(), ImportResolverLimits.MAX_HTML_BYTES, deadline);
		if (page.statusCode() >= 500) {
			throw retryable(ResolutionReason.DEPENDENCY_UNAVAILABLE);
		}
		if (page.statusCode() < 200 || page.statusCode() >= 300) {
			return unsupported();
		}
		if (!OpenGraphHtml.isHtmlContentType(page.contentType())) {
			return unsupported();
		}
		Charset charset = OpenGraphHtml.charsetOf(page.contentType());
		OpenGraphHtml.Metadata meta = OpenGraphHtml.parse(new String(page.body(), charset),
				page.finalUri().toString());
		if (meta.ogImage() == null) {
			return unsupported();
		}
		URI imageUri = resolveAgainst(page.finalUri(), SafeHttpUri.parse(meta.ogImage()));
		// Every OG image URL is a fresh hostile URL: identical policy and remaining budget.
		BoundedFetch.Fetched image = bounded.fetch(imageUri, ImportResolverLimits.MAX_IMAGE_BYTES, deadline);
		if (image.statusCode() >= 500) {
			throw retryable(ResolutionReason.DEPENDENCY_UNAVAILABLE);
		}
		if (image.statusCode() < 200 || image.statusCode() >= 300) {
			return unsupported();
		}
		// Spec: an image content type AND byte inspection are both required;
		// a lying or absent type never reaches the image pipeline.
		if (!isImageContentType(image.contentType())) {
			return unsupported();
		}
		ImageInspector.Result inspected;
		try {
			inspected = imageInspector.inspect(image.body());
		}
		catch (ObjectStoreExceptions.ImageValidationException ex) {
			return unsupported();
		}
		String contentType = inspected.mediaType() == ImageMediaType.PNG ? "image/png" : "image/jpeg";
		StorageKey destination = destinationKey(contentType);
		publish(image.body().length, destination, image.body(), contentType);
		return new ResolutionResult(
				ResolutionStatus.RESOLVED,
				destination,
				canonicalUrl(meta, page.finalUri(), source),
				boundedMetadata(meta.title()),
				boundedMetadata(meta.siteName()),
				null);
	}

	private static URI resolveAgainst(URI base, URI raw) {
		try {
			return base.resolve(raw);
		}
		catch (IllegalArgumentException ex) {
			throw new ImportNetworkException(FailureKind.UNSAFE_URL, "image reference is not resolvable", ex);
		}
	}

	/** Repo image policy admits only image/png and image/jpeg fetched media. */
	private static boolean isImageContentType(String contentType) {
		if (contentType == null) {
			return false;
		}
		try {
			ImageMediaPolicy.requireDeclaration(contentType, 1L);
			return true;
		}
		catch (IllegalArgumentException ex) {
			return false;
		}
	}

	private static URI canonicalUrl(OpenGraphHtml.Metadata meta, URI pageUri, SourceReference source) {
		if (meta.ogUrl() != null) {
			try {
				URI canonical = resolveAgainst(pageUri, SafeHttpUri.parse(meta.ogUrl()));
				return SafeHttpUri.requireSafe(canonical);
			}
			catch (ImportNetworkException ignored) {
				// unsafe canonical metadata never fails a resolved import
			}
		}
		return source.url();
	}

	private void publish(int length, StorageKey destination, byte[] body, String contentType) {
		try {
			mediaAccess.publishFetchedImage(new ByteArrayInputStream(body), length, destination, contentType);
		}
		catch (ObjectStoreExceptions.ObjectStoreUnavailableException ex) {
			// R-P2-04: If the object store committed the PUT but returned an ambiguous 5xx/SDK error,
			// an attempt-unique destination object could be orphaned in VAULT. Best-effort cleanup
			// reaps the committed-but-unacknowledged object without risking any other attempt.
			try {
				mediaAccess.deleteObject(destination);
			}
			catch (RuntimeException ignored) {
				// Best-effort cleanup only.
			}
			throw retryable(ResolutionReason.DEPENDENCY_UNAVAILABLE);
		}
		catch (IllegalArgumentException ex) {
			throw new ImportNetworkException(FailureKind.UNUSABLE_CONTENT, "unusable imported content", ex);
		}
	}

	/** Retryable infrastructure failures propagate; unsafe/unusable input falls through. */
	private static ResolutionResult classify(ImportNetworkException ex) {
		return switch (ex.kind()) {
			case TIMEOUT -> throw retryable(ResolutionReason.RESOLUTION_TIMEOUT);
			case DNS_FAILURE, TRANSPORT_UNUSABLE, OBJECT_STORE_UNAVAILABLE -> throw retryable(
					ResolutionReason.DEPENDENCY_UNAVAILABLE);
			case UNSAFE_URL, BAD_REDIRECT, UNUSABLE_CONTENT, REMOTE_SERVER_ERROR -> unsupported();
		};
	}

	private StorageKey destinationKey(String contentType) {
		String extension = "image/png".equals(contentType) ? "png" : "jpg";
		return new StorageKey(
				"users/" + ownerId.value() + "/imports/" + importId.value() + "-" + attemptToken + "." + extension);
	}

	private static String boundedMetadata(String value) {
		if (value == null) {
			return null;
		}
		String stripped = value.strip();
		if (stripped.isEmpty()) {
			return null;
		}
		return stripped.length() > ImportResolverLimits.MAX_SOURCE_METADATA_LENGTH
				? stripped.substring(0, ImportResolverLimits.MAX_SOURCE_METADATA_LENGTH)
				: stripped;
	}

	private static ResolutionResult unsupported() {
		return new ResolutionResult(ResolutionStatus.UNSUPPORTED, null, null, null, null, null);
	}

	private static ImporterRetryableException retryable(ResolutionReason reason) {
		return new ImporterRetryableException(reason, "import dependency failed");
	}

	/** Minimal clock seam so tests can force total-budget exhaustion. */
	interface Clock {

		Instant instant();
	}
}
