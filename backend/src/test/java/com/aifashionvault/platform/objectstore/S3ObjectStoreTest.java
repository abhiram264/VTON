package com.aifashionvault.platform.objectstore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.aifashionvault.platform.environment.VaultEnvironmentProperties;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class S3ObjectStoreTest {

	private static final Instant NOW = Instant.parse("2026-08-21T12:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
	private static final VaultEnvironmentProperties.S3 BUCKETS = new VaultEnvironmentProperties.S3(
		"configured-avatar",
		"configured-vault",
		"configured-look",
		"configured-temp");

	private S3Presigner presigner;

	@BeforeEach
	void setUpPresigner() {
		presigner = S3Presigner.builder()
			.region(Region.US_EAST_1)
			.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
			.build();
	}

	@AfterEach
	void closePresigner() {
		presigner.close();
	}

	@Nested
	class BucketMapping {

		@Test
		void fourBoundariesMapToDistinctConfiguredBuckets() {
			S3ObjectStore store = store(mock(S3Client.class), (object, age) -> {
			});

			assertThat(store.bucket(StorageBoundary.AVATAR)).isEqualTo("configured-avatar");
			assertThat(store.bucket(StorageBoundary.VAULT)).isEqualTo("configured-vault");
			assertThat(store.bucket(StorageBoundary.LOOK)).isEqualTo("configured-look");
			assertThat(store.bucket(StorageBoundary.TEMP)).isEqualTo("configured-temp");
			assertThat(Set.of(
				store.bucket(StorageBoundary.AVATAR),
				store.bucket(StorageBoundary.VAULT),
				store.bucket(StorageBoundary.LOOK),
				store.bucket(StorageBoundary.TEMP))).hasSize(4);
		}

		@Test
		void presignTargetsTheConfiguredBucketForEachBoundary() {
			S3ObjectStore store = store(mock(S3Client.class), (object, age) -> {
			});

			assertThat(store.presignPut(new ObjectRef(StorageBoundary.AVATAR, "users/u/avatars/a.jpg"), Duration.ofMinutes(10))
				.uri()
				.toString()).contains("configured-avatar");
			assertThat(store.presignGet(new ObjectRef(StorageBoundary.VAULT, "users/u/vault/v.jpg"), Duration.ofMinutes(5))
				.uri()
				.toString()).contains("configured-vault");
			assertThat(store.presignPut(new ObjectRef(StorageBoundary.LOOK, "users/u/looks/l.jpg"), Duration.ofMinutes(1))
				.uri()
				.toString()).contains("configured-look");
			assertThat(store.presignGet(new ObjectRef(StorageBoundary.TEMP, "temp/u/job/t.jpg"), Duration.ofSeconds(30))
				.uri()
				.toString()).contains("configured-temp");
		}
	}

	@Nested
	class PresignCaps {

		@Test
		void putAllowsExactTenMinuteCapAndRejectsAboveOrNonpositive() {
			S3ObjectStore store = store(mock(S3Client.class), (object, age) -> {
			});
			ObjectRef ref = new ObjectRef(StorageBoundary.AVATAR, "users/u/avatars/a.jpg");

			PresignedAccess allowed = store.presignPut(ref, Duration.ofMinutes(10));
			assertThat(allowed.method()).isEqualTo("PUT");
			assertThat(allowed.uri().getQuery()).contains("X-Amz-Expires=600");

			assertThatThrownBy(() -> store.presignPut(ref, Duration.ofMinutes(10).plusNanos(1)))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> store.presignPut(ref, Duration.ofMinutes(11)))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> store.presignPut(ref, Duration.ZERO))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> store.presignPut(ref, Duration.ofMinutes(-1)))
				.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		void getAllowsExactFiveMinuteCapAndRejectsAboveOrNonpositive() {
			S3ObjectStore store = store(mock(S3Client.class), (object, age) -> {
			});
			ObjectRef ref = new ObjectRef(StorageBoundary.LOOK, "users/u/looks/l.jpg");

			PresignedAccess allowed = store.presignGet(ref, Duration.ofMinutes(5));
			assertThat(allowed.method()).isEqualTo("GET");
			assertThat(allowed.uri().getQuery()).contains("X-Amz-Expires=300");

			assertThatThrownBy(() -> store.presignGet(ref, Duration.ofMinutes(5).plusNanos(1)))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> store.presignGet(ref, Duration.ofMinutes(10)))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> store.presignGet(ref, Duration.ZERO))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> store.presignGet(ref, Duration.ofSeconds(-1)))
				.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Nested
	class Privacy {

		@Test
		void objectRefIsAKeyNotAUrlAndRejectsUrlShapedIdentity() {
			ObjectRef ref = new ObjectRef(StorageBoundary.VAULT, "users/u/vault/item.jpg");
			assertThat(ref.objectKey()).isEqualTo("users/u/vault/item.jpg");
			assertThat(ref.objectKey()).doesNotContain("://");

			assertThatThrownBy(() -> new ObjectRef(StorageBoundary.AVATAR, "https://configured-avatar.s3.amazonaws.com/users/u/avatars/a.jpg"))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> new ObjectRef(StorageBoundary.TEMP, "http://example.invalid/temp/a.jpg"))
				.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		void presignReturnsACappedCapabilityNotAPermanentPublicUrl() {
			S3Client s3 = mock(S3Client.class);
			S3ObjectStore store = store(s3, (object, age) -> {
			});
			ObjectRef ref = new ObjectRef(StorageBoundary.AVATAR, "users/u/avatars/a.jpg");

			PresignedAccess put = store.presignPut(ref, Duration.ofMinutes(10));
			PresignedAccess get = store.presignGet(ref, Duration.ofMinutes(5));

			assertThat(put.object()).isEqualTo(ref);
			assertThat(get.object()).isEqualTo(ref);
			assertPrivatePresign(put, "PUT");
			assertPrivatePresign(get, "GET");
			verifyNoInteractions(s3);
		}

		@Test
		void deleteUsesPrivateObjectOperationsOnTheMappedBucket() {
			S3Client s3 = mock(S3Client.class);
			S3ObjectStore store = store(s3, (object, age) -> {
			});
			ObjectRef ref = new ObjectRef(StorageBoundary.VAULT, "users/u/vault/item.jpg");

			store.delete(ref);

			ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
			verify(s3).deleteObject(captor.capture());
			assertThat(captor.getValue().bucket()).isEqualTo("configured-vault");
			assertThat(captor.getValue().key()).isEqualTo("users/u/vault/item.jpg");
			assertThat(captor.getValue().key()).doesNotContain("://");
		}
	}

	@Nested
	class TempCleanup {

		@Test
		void deletesAtAndAfterTwentyHoursAndAlertsAtAndAfterTwentyTwoHours() {
			S3Client s3 = mock(S3Client.class);
			List<Aged> alerts = new ArrayList<>();
			S3ObjectStore store = store(s3, (object, age) -> alerts.add(new Aged(object.objectKey(), age)));

			when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(ListObjectsV2Response.builder()
				.contents(
					obj("future", NOW.plus(Duration.ofHours(1))),
					obj("age-19h-59s", NOW.minus(Duration.ofHours(20)).plusSeconds(1)),
					obj("age-20h-minus-1ns", NOW.minus(Duration.ofHours(20)).plusNanos(1)),
					obj("age-20h", NOW.minus(Duration.ofHours(20))),
					obj("age-20h-plus-1ns", NOW.minus(Duration.ofHours(20)).minusNanos(1)),
					obj("age-22h-minus-1ns", NOW.minus(Duration.ofHours(22)).plusNanos(1)),
					obj("age-22h", NOW.minus(Duration.ofHours(22))),
					obj("age-22h-plus-1ns", NOW.minus(Duration.ofHours(22)).minusNanos(1)))
				.isTruncated(false)
				.build());

			store.cleanupTemp();

			ArgumentCaptor<ListObjectsV2Request> list = ArgumentCaptor.forClass(ListObjectsV2Request.class);
			verify(s3).listObjectsV2(list.capture());
			assertThat(list.getValue().bucket()).isEqualTo("configured-temp");

			ArgumentCaptor<DeleteObjectRequest> deleted = ArgumentCaptor.forClass(DeleteObjectRequest.class);
			verify(s3, times(5)).deleteObject(deleted.capture());
			assertThat(deleted.getAllValues())
				.allMatch(request -> "configured-temp".equals(request.bucket()))
				.extracting(DeleteObjectRequest::key)
				.containsExactly("age-20h", "age-20h-plus-1ns", "age-22h-minus-1ns", "age-22h", "age-22h-plus-1ns");

			assertThat(alerts)
				.extracting(Aged::key)
				.containsExactly("age-22h", "age-22h-plus-1ns");
			assertThat(alerts).allMatch(aged -> aged.age().compareTo(Duration.ofHours(22)) >= 0);
		}

		@Test
		void continuesAcrossListPagesWithoutSleeping() {
			S3Client s3 = mock(S3Client.class);
			S3ObjectStore store = store(s3, (object, age) -> {
			});
			when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(
				ListObjectsV2Response.builder()
					.contents(obj("young", NOW.minus(Duration.ofHours(1))))
					.isTruncated(true)
					.nextContinuationToken("page-2")
					.build(),
				ListObjectsV2Response.builder()
					.contents(obj("abandoned", NOW.minus(Duration.ofHours(20))))
					.isTruncated(false)
					.build());

			store.cleanupTemp();

			ArgumentCaptor<DeleteObjectRequest> deleted = ArgumentCaptor.forClass(DeleteObjectRequest.class);
			verify(s3).deleteObject(deleted.capture());
			assertThat(deleted.getValue().key()).isEqualTo("abandoned");
			verify(s3, times(2)).listObjectsV2(any(ListObjectsV2Request.class));
		}

		@Test
		void doesNotDeleteYoungTempObjects() {
			S3Client s3 = mock(S3Client.class);
			List<ObjectRef> alerts = new ArrayList<>();
			S3ObjectStore store = store(s3, (object, age) -> alerts.add(object));
			when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(ListObjectsV2Response.builder()
				.contents(obj("fresh", NOW.minus(Duration.ofMinutes(5))))
				.isTruncated(false)
				.build());

			store.cleanupTemp();

			verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));
			assertThat(alerts).isEmpty();
		}
	}

	private S3ObjectStore store(S3Client s3, TempObjectAgeAlert alert) {
		return new S3ObjectStore(s3, presigner, BUCKETS, CLOCK, alert);
	}

	private static S3Object obj(String key, Instant lastModified) {
		return S3Object.builder().key(key).lastModified(lastModified).build();
	}

	private static void assertPrivatePresign(PresignedAccess access, String method) {
		assertThat(access.method()).isEqualTo(method);
		assertThat(access.uri()).isNotNull();
		assertThat(access.uri().getQuery()).contains("X-Amz-Algorithm");
		assertThat(access.uri().getQuery()).contains("X-Amz-Signature");
		assertThat(access.uri().toString().toLowerCase()).doesNotContain("x-amz-acl");
		assertThat(access.uri().toString().toLowerCase()).doesNotContain("public-read");
		assertThat(access.expiresAt()).isNotNull();
		assertThat(access.expiresAt()).isBeforeOrEqualTo(Instant.now().plus(Duration.ofMinutes(10)).plusSeconds(5));
	}

	private record Aged(String key, Duration age) {
	}
}
