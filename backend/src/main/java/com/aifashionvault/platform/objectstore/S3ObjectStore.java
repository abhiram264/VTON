package com.aifashionvault.platform.objectstore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aifashionvault.platform.environment.VaultEnvironmentProperties;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.StorageDomain;
import com.aifashionvault.shared.UserId;

import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkServiceException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

public final class S3ObjectStore implements ObjectStore, GenerationMediaStore {

	private static final Duration PUT_TTL_CAP = Duration.ofMinutes(10);
	private static final Duration GET_TTL_CAP = Duration.ofMinutes(5);
	private static final Duration TEMP_DELETE_AGE = Duration.ofHours(20);
	private static final Duration TEMP_ALERT_AGE = Duration.ofHours(22);
	private static final String CLAIM_MARKER = "claim-marker";
	private static final String MISSING_MESSAGE = "staged object is missing";
	private static final String UNAVAILABLE_MESSAGE = "object store is unavailable";
	private static final Logger log = LoggerFactory.getLogger(S3ObjectStore.class);

	private final S3Client s3;
	private final S3Presigner presigner;
	private final VaultEnvironmentProperties.S3 buckets;
	private final Clock clock;
	private final TempObjectAgeAlert alert;

	public S3ObjectStore(
		S3Client s3,
		S3Presigner presigner,
		VaultEnvironmentProperties.S3 buckets,
		Clock clock,
		TempObjectAgeAlert alert
	) {
		if (s3 == null || presigner == null || buckets == null || clock == null || alert == null) {
			throw new IllegalArgumentException("s3, presigner, buckets, clock, and alert are required");
		}
		this.s3 = s3;
		this.presigner = presigner;
		this.buckets = buckets;
		this.clock = clock;
		this.alert = alert;
	}

	public String bucket(StorageDomain boundary) {
		if (boundary == null) {
			throw new IllegalArgumentException("storage boundary is required");
		}
		return switch (boundary) {
			case AVATAR -> buckets.avatarBucket();
			case VAULT -> buckets.vaultBucket();
			case TEMP -> buckets.tempBucket();
			case LOOK -> buckets.lookBucket();
		};
	}

	public PresignedAccess presignPut(ObjectRef object, Duration ttl) {
		requireObject(object);
		requireTtl(ttl, PUT_TTL_CAP);
		PutObjectRequest put = PutObjectRequest.builder()
			.bucket(bucket(object.boundary()))
			.key(object.objectKey())
			.build();
		PresignedPutObjectRequest signed = presigner.presignPutObject(request -> request
			.signatureDuration(ttl)
			.putObjectRequest(put));
		return new PresignedAccess(
			object,
			signed.httpRequest().method().name(),
			uri(signed.url()),
			flattenHeaders(signed.signedHeaders()),
			signed.expiration());
	}

	@Override
	public PresignedAccess presignGet(ObjectRef object, Duration ttl) {
		requireObject(object);
		requireTtl(ttl, GET_TTL_CAP);
		GetObjectRequest get = GetObjectRequest.builder()
			.bucket(bucket(object.boundary()))
			.key(object.objectKey())
			.build();
		PresignedGetObjectRequest signed = presigner.presignGetObject(request -> request
			.signatureDuration(ttl)
			.getObjectRequest(get));
		return new PresignedAccess(
			object,
			signed.httpRequest().method().name(),
			uri(signed.url()),
			flattenHeaders(signed.signedHeaders()),
			signed.expiration());
	}

	@Override
	public UploadCapability presignStagingPut(UserId userId, UploadId uploadId, MediaDeclaration declaration) {
		return presignStagingPut(userId, uploadId, declaration, UploadPurpose.GENERIC);
	}

	@Override
	public UploadCapability presignStagingPut(
		UserId userId,
		UploadId uploadId,
		MediaDeclaration declaration,
		UploadPurpose purpose
	) {
		if (userId == null || uploadId == null || declaration == null || purpose == null) {
			throw new IllegalArgumentException("userId, uploadId, declaration, and purpose are required");
		}
		ObjectRef object = new ObjectRef(StorageDomain.TEMP, StorageKeys.stagingKey(userId, uploadId, purpose).value());
		PutObjectRequest put = PutObjectRequest.builder()
			.bucket(bucket(object.boundary()))
			.key(object.objectKey())
			.contentType(declaration.contentType())
			.contentLength(declaration.contentLength())
			.ifNoneMatch("*")
			.build();
		PresignedPutObjectRequest signed = presigner.presignPutObject(request -> request
			.signatureDuration(Duration.ofMinutes(10))
			.putObjectRequest(put));
		return new UploadCapability(
			uploadId,
			signed.httpRequest().method().name(),
			uri(signed.url()),
			stagingHeaders(signed, declaration),
			signed.expiration());
	}

	@Override
	public ObjectMetadata head(ObjectRef object) {
		requireObject(object);
		try {
			HeadObjectResponse response = s3.headObject(HeadObjectRequest.builder()
				.bucket(bucket(object.boundary()))
				.key(object.objectKey())
				.build());
			String contentType = response.contentType();
			String version = response.eTag();
			if (contentType == null || contentType.isBlank() || version == null || version.isBlank()) {
				throw unavailable();
			}
			long length = response.contentLength() == null ? 0L : response.contentLength();
			return new ObjectMetadata(length, contentType, version);
		}
		catch (ObjectStoreExceptions.ObjectStoreUnavailableException ex) {
			throw ex;
		}
		catch (S3Exception ex) {
			throw translate(ex);
		}
		catch (SdkClientException ex) {
			throw unavailable();
		}
	}

	@Override
	public byte[] readBounded(ObjectRef object, String expectedVersion, int maxBytes) {
		requireObject(object);
		GetObjectRequest.Builder get = GetObjectRequest.builder()
			.bucket(bucket(object.boundary()))
			.key(object.objectKey());
		if (expectedVersion != null && !expectedVersion.isBlank()) {
			get.ifMatch(expectedVersion);
		}
		try (ResponseInputStream<GetObjectResponse> in = s3.getObject(get.build())) {
			return readAtMost(in, maxBytes);
		}
		catch (S3Exception ex) {
			throw translate(ex);
		}
		catch (SdkClientException | IOException ex) {
			throw unavailable();
		}
	}

	@Override
	public void delete(ObjectRef object) {
		requireObject(object);
		try {
			s3.deleteObject(DeleteObjectRequest.builder()
				.bucket(bucket(object.boundary()))
				.key(object.objectKey())
				.build());
		}
		catch (S3Exception ex) {
			if (isMissing(ex)) {
				return;
			}
			throw translate(ex);
		}
		catch (SdkClientException ex) {
			throw unavailable();
		}
	}

	@Override
	public List<StoredObject> list(StorageDomain boundary, String prefix) {
		if (boundary == null) {
			throw new IllegalArgumentException("storage boundary is required");
		}
		if (prefix == null) {
			throw new IllegalArgumentException("prefix is required");
		}
		List<StoredObject> objects = new ArrayList<>();
		String token = null;
		do {
			ListObjectsV2Request.Builder request = ListObjectsV2Request.builder()
				.bucket(bucket(boundary))
				.prefix(prefix);
			if (token != null) {
				request.continuationToken(token);
			}
			ListObjectsV2Response page = s3.listObjectsV2(request.build());
			for (S3Object object : page.contents()) {
				if (object == null || object.key() == null || object.key().isBlank()) {
					continue;
				}
				objects.add(new StoredObject(object.key(), object.lastModified()));
			}
			token = Boolean.TRUE.equals(page.isTruncated()) ? page.nextContinuationToken() : null;
		}
		while (token != null);
		return objects;
	}

	@Override
	public ClaimResult claim(ClaimCommand command) {
		if (command == null) {
			throw new IllegalArgumentException("command is required");
		}
		ObjectRef source = claimSource(command);
		ObjectRef destination = new ObjectRef(command.destinationBoundary(), command.destination().value());
		return copyWithClaimMarker(source, destination, command.sourceVersion(), command.claimMarker());
	}

	@Override
	public void putBounded(StorageKey dest, InputStream source, long maxBytes, String mediaType) {
		if (dest == null || source == null) {
			throw new IllegalArgumentException("dest and source are required");
		}
		if (maxBytes <= 0) {
			throw new IllegalArgumentException("maxBytes must be positive");
		}
		if (mediaType == null || mediaType.isBlank()) {
			throw new IllegalArgumentException("mediaType is required");
		}
		byte[] payload = readBoundedPayload(source, maxBytes);
		PutObjectRequest put = PutObjectRequest.builder()
			.bucket(bucket(generationMediaBoundary(dest)))
			.key(dest.value())
			.contentType(mediaType)
			.contentLength((long) payload.length)
			.ifNoneMatch("*")
			.build();
		try {
			s3.putObject(put, RequestBody.fromBytes(payload));
		}
		catch (S3Exception ex) {
			if (isPreconditionFailed(ex)) {
				return;
			}
			throw translate(ex);
		}
		catch (SdkClientException ex) {
			throw unavailable();
		}
	}

	@Override
	public ClaimResult claim(GenerationClaimCommand command) {
		if (command == null) {
			throw new IllegalArgumentException("command is required");
		}
		ObjectRef source = new ObjectRef(StorageDomain.TEMP, command.sourceTempKey().value());
		ObjectRef destination = new ObjectRef(StorageDomain.LOOK, command.destinationLookKey().value());
		return copyWithClaimMarker(source, destination, command.sourceVersion(), command.claimMarker());
	}

	@Override
	public void preflightProductInput(ProductInputClaimCommand command) {
		validateProductInput(command);
	}

	@Override
	public ClaimResult claimProductInput(ProductInputClaimCommand command) {
		ValidatedProductInput validated = validateProductInput(command);
		ObjectRef destination = productInputDestination(command);
		return copyWithClaimMarker(
			validated.source(),
			destination,
			validated.metadata().version(),
			command.generationId().value() + ":" + command.destinationMediaId().value(),
			validated.contentType());
	}

	@Override
	public void releaseProductInput(ProductInputClaimCommand command) {
		if (command == null) throw new IllegalArgumentException("command is required");
		delete(productInputDestination(command));
	}

	private ValidatedProductInput validateProductInput(ProductInputClaimCommand command) {
		if (command == null) throw new IllegalArgumentException("command is required");
		ObjectRef source = new ObjectRef(
			StorageDomain.TEMP,
			StorageKeys.stagingKey(command.ownerId(), command.productUploadId(), UploadPurpose.GENERIC).value());
		ObjectMetadata sourceMetadata = head(source);
		try {
			String normalizedType = ImageMediaPolicy.requireDeclaration(
				sourceMetadata.contentType(), sourceMetadata.contentLength());
			byte[] bytes = readBounded(source, sourceMetadata.version(), boundedReadCap(sourceMetadata.contentLength()));
			ImageMediaType inspectedType = new ImageInspector().inspect(bytes).mediaType();
			if (!(normalizedType.equals("image/jpeg") && inspectedType == ImageMediaType.JPEG)
				&& !(normalizedType.equals("image/png") && inspectedType == ImageMediaType.PNG))
				throw new IllegalArgumentException("image type does not match stored content type");
			return new ValidatedProductInput(source, sourceMetadata, normalizedType);
		}
		catch (IllegalArgumentException | ObjectStoreExceptions.ImageValidationException ex) {
			throw new ObjectStoreExceptions.ImageValidationException("invalid product upload");
		}
	}

	private static ObjectRef productInputDestination(ProductInputClaimCommand command) {
		return new ObjectRef(StorageDomain.TEMP, StorageKeys.generationInputKey(
			command.ownerId(), command.generationId().value(), command.destinationMediaId().value()).value());
	}

	private record ValidatedProductInput(ObjectRef source, ObjectMetadata metadata, String contentType) {}
	@Override
	public void purgeUserObjects(UserId userId) {
		if (userId == null) {
			throw new IllegalArgumentException("userId is required");
		}
		String id = userId.value().toString();
		purgePrefix(StorageDomain.AVATAR, "users/" + id + "/");
		purgePrefix(StorageDomain.TEMP, "users/" + id + "/generation-inputs/");
		purgePrefix(StorageDomain.VAULT, "users/" + id + "/");
		purgePrefix(StorageDomain.LOOK, "users/" + id + "/");
		purgePrefix(StorageDomain.TEMP, "temp/" + id + "/");
	}

	@Override
	public void cleanupTemp() {
		Instant now = clock.instant();
		String tempBucket = buckets.tempBucket();
		String token = null;
		do {
			ListObjectsV2Request.Builder request = ListObjectsV2Request.builder()
				.bucket(tempBucket)
				.prefix("temp/");
			if (token != null) {
				request.continuationToken(token);
			}
			ListObjectsV2Response page = s3.listObjectsV2(request.build());
			for (S3Object obj : page.contents()) {
				reconcile(tempBucket, obj, now);
			}
			token = Boolean.TRUE.equals(page.isTruncated()) ? page.nextContinuationToken() : null;
		}
		while (token != null);
	}

	private ObjectRef claimSource(ClaimCommand command) {
		ObjectRef avatar = new ObjectRef(
			StorageDomain.TEMP,
			StorageKeys.stagingKey(command.userId(), command.uploadId(), UploadPurpose.AVATAR).value());
		try {
			head(avatar);
			return avatar;
		}
		catch (ObjectStoreExceptions.StagedObjectMissingException ignored) {
			return new ObjectRef(
				StorageDomain.TEMP,
				StorageKeys.stagingKey(command.userId(), command.uploadId(), UploadPurpose.GENERIC).value());
		}
	}
	private ClaimResult copyWithClaimMarker(
		ObjectRef source,
		ObjectRef destination,
		String sourceVersion,
		String claimMarker
	) {
		return copyWithClaimMarker(source, destination, sourceVersion, claimMarker, null);
	}

	private ClaimResult copyWithClaimMarker(
		ObjectRef source,
		ObjectRef destination,
		String sourceVersion,
		String claimMarker,
		String contentType
	) {
		CopyObjectRequest.Builder builder = CopyObjectRequest.builder()
			.sourceBucket(bucket(source.boundary()))
			.sourceKey(source.objectKey())
			.destinationBucket(bucket(destination.boundary()))
			.destinationKey(destination.objectKey())
			.metadataDirective(MetadataDirective.REPLACE)
			.metadata(Map.of(CLAIM_MARKER, claimMarker))
			.copySourceIfMatch(sourceVersion)
			.overrideConfiguration(override -> override.putHeader("If-None-Match", "*"));
		if (contentType != null) {
			builder.contentType(contentType);
		}
		try {
			s3.copyObject(builder.build());
			return new ClaimResult.Copied();
		}
		catch (S3Exception ex) {
			if (isPreconditionFailed(ex)) {
				return classifyExistingDestination(destination, claimMarker);
			}
			throw translate(ex);
		}
		catch (SdkClientException ex) {
			throw unavailable();
		}
	}

	private ClaimResult classifyExistingDestination(ObjectRef destination, String claimMarker) {
		try {
			HeadObjectResponse head = s3.headObject(HeadObjectRequest.builder()
				.bucket(bucket(destination.boundary()))
				.key(destination.objectKey())
				.build());
			if (claimMarker.equals(metadataValue(head.metadata(), CLAIM_MARKER))) {
				return new ClaimResult.Replay();
			}
			return new ClaimResult.Conflict();
		}
		catch (S3Exception ex) {
			throw translate(ex);
		}
		catch (SdkClientException ex) {
			throw unavailable();
		}
	}

	private void purgePrefix(StorageDomain boundary, String prefix) {
		String bucketName = bucket(boundary);
		String token = null;
		do {
			ListObjectsV2Request.Builder request = ListObjectsV2Request.builder().bucket(bucketName).prefix(prefix);
			if (token != null) {
				request.continuationToken(token);
			}
			ListObjectsV2Response page = s3.listObjectsV2(request.build());
			for (S3Object obj : page.contents()) {
				if (obj == null || obj.key() == null || obj.key().isBlank()) {
					continue;
				}
				deleteForErasure(bucketName, obj.key());
			}
			token = Boolean.TRUE.equals(page.isTruncated()) ? page.nextContinuationToken() : null;
		}
		while (token != null);
	}

	private void reconcile(String tempBucket, S3Object obj, Instant now) {
		if (obj == null || obj.key() == null || obj.key().isBlank() || obj.lastModified() == null) {
			return;
		}
		Duration age = Duration.between(obj.lastModified(), now);
		if (age.compareTo(TEMP_ALERT_AGE) >= 0) {
			alert.aged(new ObjectRef(StorageDomain.TEMP, obj.key()), age);
		}
		if (age.compareTo(TEMP_DELETE_AGE) >= 0) {
			deleteQuietly(tempBucket, obj.key());
		}
	}

	private void deleteForErasure(String bucketName, String key) {
		try {
			s3.deleteObject(DeleteObjectRequest.builder().bucket(bucketName).key(key).build());
		}
		catch (RuntimeException ex) {
			log.error("erasure delete failed: {}/{} was not removed; account deletion is not complete.", bucketName, key, ex);
			throw new ObjectStoreExceptions.ObjectStoreUnavailableException(
					"object store is unavailable while erasing an account object");
		}
	}

	private void deleteQuietly(String bucketName, String key) {
		try {
			s3.deleteObject(DeleteObjectRequest.builder().bucket(bucketName).key(key).build());
		}
		catch (RuntimeException ex) {
			// The temp-retention sweep is explicitly best-effort: a later sweep retries
			// the object, so a per-object failure must not stop this one.
			log.error("temp retention sweep could not delete {}/{}", bucketName, key, ex);
		}
	}

	private static Map<String, String> stagingHeaders(PresignedPutObjectRequest signed, MediaDeclaration declaration) {
		Map<String, String> headers = new LinkedHashMap<>(flattenHeaders(signed.signedHeaders()));
		Map<String, String> http = signed.httpRequest() == null
			? Map.of()
			: flattenHeaders(signed.httpRequest().headers());
		copyIfAbsent(headers, http, "content-type");
		copyIfAbsent(headers, http, "content-length");
		copyIfAbsent(headers, http, "if-none-match");
		putIfAbsent(headers, "content-type", declaration.contentType());
		putIfAbsent(headers, "content-length", Long.toString(declaration.contentLength()));
		putIfAbsent(headers, "if-none-match", "*");
		return headers;
	}

	private static Map<String, String> flattenHeaders(Map<String, List<String>> headers) {
		if (headers == null || headers.isEmpty()) {
			return Map.of();
		}
		Map<String, String> flat = new LinkedHashMap<>();
		headers.forEach((name, values) -> {
			if (name != null && values != null && !values.isEmpty() && values.getFirst() != null) {
				flat.put(name, values.getFirst());
			}
		});
		return flat;
	}

	private static void copyIfAbsent(Map<String, String> target, Map<String, String> source, String name) {
		putIfAbsent(target, name, headerIgnoreCase(source, name));
	}

	private static void putIfAbsent(Map<String, String> target, String name, String value) {
		if (value == null || headerIgnoreCase(target, name) != null) {
			return;
		}
		target.put(name, value);
	}

	private static String headerIgnoreCase(Map<String, String> headers, String name) {
		for (Map.Entry<String, String> entry : headers.entrySet()) {
			if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
				return entry.getValue();
			}
		}
		return null;
	}

	private static String metadataValue(Map<String, String> metadata, String key) {
		if (metadata == null || metadata.isEmpty()) {
			return null;
		}
		String direct = metadata.get(key);
		if (direct != null) {
			return direct;
		}
		for (Map.Entry<String, String> entry : metadata.entrySet()) {
			if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
				return entry.getValue();
			}
		}
		return null;
	}

	private static StorageDomain generationMediaBoundary(StorageKey key) {
		String value = key.value();
		if (value.startsWith("temp/")) {
			return StorageDomain.TEMP;
		}
		if (value.contains("/generation-inputs/")) {
			return StorageDomain.TEMP;
		}
		if (value.contains("/looks/")) {
			return StorageDomain.LOOK;
		}
		throw new IllegalArgumentException("generation media destination must be a generation TEMP, input, or LOOK key");
	}

	private static byte[] readBoundedPayload(InputStream in, long maxBytes) {
		if (maxBytes > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("maxBytes exceeds supported bound");
		}
		int max = (int) maxBytes;
		try {
			int chunk = Math.min(8192, Math.max(1, max));
			byte[] buffer = new byte[chunk];
			ByteArrayOutputStream out = new ByteArrayOutputStream(chunk);
			int remaining = max;
			int read;
			while (remaining > 0 && (read = in.read(buffer, 0, Math.min(buffer.length, remaining))) != -1) {
				out.write(buffer, 0, read);
				remaining -= read;
			}
			if (remaining == 0 && in.read() != -1) {
				throw new IllegalArgumentException("object exceeds maxBytes");
			}
			return out.toByteArray();
		}
		catch (IOException ex) {
			throw unavailable();
		}
	}

	private static byte[] readAtMost(InputStream in, int maxBytes) throws IOException {
		if (in == null || maxBytes <= 0) {
			return new byte[0];
		}
		int chunk = Math.min(8192, maxBytes);
		byte[] buffer = new byte[chunk];
		ByteArrayOutputStream out = new ByteArrayOutputStream(chunk);
		int remaining = maxBytes;
		int read;
		while (remaining > 0 && (read = in.read(buffer, 0, Math.min(buffer.length, remaining))) != -1) {
			out.write(buffer, 0, read);
			remaining -= read;
		}
		return out.toByteArray();
	}

	private static int boundedReadCap(long contentLength) {
		return (int) Math.min(contentLength, ImageMediaPolicy.MAX_BYTES);
	}

	private static RuntimeException translate(S3Exception ex) {
		if (isMissing(ex)) {
			return missing();
		}
		return unavailable();
	}

	private static boolean isMissing(SdkServiceException ex) {
		if (ex instanceof NoSuchKeyException) {
			return true;
		}
		String code = errorCode(ex);
		return "NoSuchKey".equalsIgnoreCase(code) || "NotFound".equalsIgnoreCase(code) || "404".equals(code);
	}

	private static boolean isPreconditionFailed(SdkServiceException ex) {
		if (ex.statusCode() == 412 || ex.statusCode() == 409) {
			return true;
		}
		String code = errorCode(ex);
		return "PreconditionFailed".equalsIgnoreCase(code) || "ConditionalRequestConflict".equalsIgnoreCase(code);
	}

	private static String errorCode(SdkServiceException ex) {
		if (ex instanceof AwsServiceException aws
			&& aws.awsErrorDetails() != null
			&& aws.awsErrorDetails().errorCode() != null) {
			return aws.awsErrorDetails().errorCode();
		}
		return "";
	}

	private static ObjectStoreExceptions.StagedObjectMissingException missing() {
		return new ObjectStoreExceptions.StagedObjectMissingException(MISSING_MESSAGE);
	}

	private static ObjectStoreExceptions.ObjectStoreUnavailableException unavailable() {
		return new ObjectStoreExceptions.ObjectStoreUnavailableException(UNAVAILABLE_MESSAGE);
	}

	private static void requireObject(ObjectRef object) {
		if (object == null) {
			throw new IllegalArgumentException("object is required");
		}
	}

	private static void requireTtl(Duration ttl, Duration cap) {
		if (ttl == null || ttl.isZero() || ttl.isNegative() || ttl.compareTo(cap) > 0) {
			throw new IllegalArgumentException("ttl must be positive and at most " + cap);
		}
	}

	private static URI uri(URL url) {
		try {
			return url.toURI();
		}
		catch (URISyntaxException ex) {
			throw new IllegalStateException("presigned url is not a valid URI");
		}
	}
}
