package com.aifashionvault.platform.objectstore;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import com.aifashionvault.platform.environment.VaultEnvironmentProperties;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

public final class S3ObjectStore {

	private static final Duration PUT_TTL_CAP = Duration.ofMinutes(10);
	private static final Duration GET_TTL_CAP = Duration.ofMinutes(5);
	private static final Duration TEMP_DELETE_AGE = Duration.ofHours(20);
	private static final Duration TEMP_ALERT_AGE = Duration.ofHours(22);

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

	public String bucket(StorageBoundary boundary) {
		if (boundary == null) {
			throw new IllegalArgumentException("storage boundary is required");
		}
		return switch (boundary) {
			case AVATAR -> buckets.avatarBucket();
			case VAULT -> buckets.vaultBucket();
			case LOOK -> buckets.lookBucket();
			case TEMP -> buckets.tempBucket();
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
		return new PresignedAccess(object, signed.httpRequest().method().name(), uri(signed.url()), signed.expiration());
	}

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
		return new PresignedAccess(object, signed.httpRequest().method().name(), uri(signed.url()), signed.expiration());
	}

	public void delete(ObjectRef object) {
		requireObject(object);
		s3.deleteObject(DeleteObjectRequest.builder()
			.bucket(bucket(object.boundary()))
			.key(object.objectKey())
			.build());
	}

	public void cleanupTemp() {
		Instant now = clock.instant();
		String tempBucket = buckets.tempBucket();
		String token = null;
		do {
			ListObjectsV2Request.Builder request = ListObjectsV2Request.builder().bucket(tempBucket);
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

	private void reconcile(String tempBucket, S3Object obj, Instant now) {
		if (obj == null || obj.key() == null || obj.key().isBlank() || obj.lastModified() == null) {
			return;
		}
		Duration age = Duration.between(obj.lastModified(), now);
		if (age.compareTo(TEMP_DELETE_AGE) >= 0) {
			s3.deleteObject(DeleteObjectRequest.builder().bucket(tempBucket).key(obj.key()).build());
		}
		if (age.compareTo(TEMP_ALERT_AGE) >= 0) {
			alert.aged(new ObjectRef(StorageBoundary.TEMP, obj.key()), age);
		}
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
