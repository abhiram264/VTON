package com.aifashionvault.platform.objectstore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Importer-specific conditional-create writer for private import media. The
 * generic {@link GenerationMediaStore#putBounded} writes without an import
 * ownership marker and only into generation key shapes, so the importer keeps
 * this dedicated adapter: If-None-Match create, claim metadata recording the
 * owning import attempt, and a losing create deletes its partial object.
 */
public final class ImportObjectWriter {

	private final S3ObjectStore store;
	private final S3Client s3;

	public ImportObjectWriter(S3ObjectStore store) {
		this.store = store;
		this.s3 = null;
	}

	public ImportObjectWriter(S3ObjectStore store, S3Client s3) {
		this.store = store;
		this.s3 = s3;
	}

	/**
	 * Conditionally creates the object. Returns false when an object already
	 * exists at the key. On any non-precondition failure the partial object is
	 * deleted best-effort before the failure propagates.
	 */
	public boolean putIfAbsent(ObjectRef destination, InputStream content, long contentLength, String claimMarker,
			String contentType) {
		if (destination == null || content == null) {
			throw new IllegalArgumentException("destination and content are required");
		}
		if (contentLength < 0) {
			throw new IllegalArgumentException("contentLength must not be negative");
		}
		if (claimMarker == null || claimMarker.isBlank()) {
			throw new IllegalArgumentException("claimMarker is required");
		}
		if (contentType == null || contentType.isBlank()) {
			throw new IllegalArgumentException("contentType is required");
		}
		requireS3();
		byte[] payload = readAll(content);
		PutObjectRequest put = PutObjectRequest.builder()
			.bucket(store.bucket(destination.boundary()))
			.key(destination.objectKey())
			.contentType(contentType)
			.contentLength((long) payload.length)
			.metadata(java.util.Map.of("claim-marker", claimMarker))
			.ifNoneMatch("*")
			.build();
		try {
			s3.putObject(put, RequestBody.fromBytes(payload));
			return true;
		}
		catch (S3Exception ex) {
			if (isPreconditionFailed(ex)) {
				return false;
			}
			deleteQuietly(destination);
			// Service-side S3 failure (e.g. transient 5xx) must surface as the
			// typed store-unavailable failure callers already handle, never raw.
			throw new ObjectStoreExceptions.ObjectStoreUnavailableException("object store is unavailable");
		}
		catch (SdkClientException ex) {
			deleteQuietly(destination);
			throw new ObjectStoreExceptions.ObjectStoreUnavailableException("object store is unavailable");
		}
	}

	private void requireS3() {
		if (s3 == null) {
			throw new IllegalStateException("ImportObjectWriter requires an S3Client");
		}
	}

	private static byte[] readAll(InputStream content) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		try (content) {
			int read;
			while ((read = content.read(buffer)) != -1) {
				out.write(buffer, 0, read);
			}
		}
		catch (IOException ex) {
			throw new ObjectStoreExceptions.ObjectStoreUnavailableException("failed reading fetched content");
		}
		return out.toByteArray();
	}

	private void deleteQuietly(ObjectRef destination) {
		try {
			store.delete(destination);
		}
		catch (RuntimeException ignored) {
			// best-effort partial cleanup
		}
	}

	private static boolean isPreconditionFailed(software.amazon.awssdk.core.exception.SdkServiceException ex) {
		if (ex.statusCode() == 412 || ex.statusCode() == 409) {
			return true;
		}
		String code = ex instanceof software.amazon.awssdk.awscore.exception.AwsServiceException aws
				&& aws.awsErrorDetails() != null && aws.awsErrorDetails().errorCode() != null
						? aws.awsErrorDetails().errorCode()
						: "";
		return "PreconditionFailed".equalsIgnoreCase(code) || "ConditionalRequestConflict".equalsIgnoreCase(code);
	}
}
