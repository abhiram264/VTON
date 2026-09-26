package com.aifashionvault.platform.objectstore;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.aifashionvault.avatar.AvatarMediaAccess;
import com.aifashionvault.platform.environment.VaultEnvironmentProperties;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.StorageDomain;
import com.aifashionvault.shared.UserId;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class S3ObjectStoreTest {

	private static final Instant NOW = Instant.parse("2026-08-21T12:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
	private static final VaultEnvironmentProperties.S3 BUCKETS = new VaultEnvironmentProperties.S3(
		"configured-avatar",
		"configured-vault",
		"configured-look",
		"configured-temp");
	private static final UserId USER_ID = new UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
	private static final UploadId UPLOAD_ID = new UploadId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
	private static final String USER_PREFIX = "users/11111111-1111-1111-1111-111111111111/";
	private static final String TEMP_PREFIX = "temp/11111111-1111-1111-1111-111111111111/";
	private static final String GENERATION_INPUT_PREFIX = "users/11111111-1111-1111-1111-111111111111/generation-inputs/";
	private static final String STAGING_KEY =
		"temp/11111111-1111-1111-1111-111111111111/uploads/22222222-2222-2222-2222-222222222222";
	private static final String AVATAR_STAGING_KEY =
		"temp/11111111-1111-1111-1111-111111111111/avatars/22222222-2222-2222-2222-222222222222";
	private static final String DEST_KEY = "users/11111111-1111-1111-1111-111111111111/avatars/a.jpg";

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
		void boundariesMapToConfiguredPrivateBuckets() {
			S3ObjectStore store = store(mock(S3Client.class), (object, age) -> {
			});

			assertThat(store.bucket(StorageDomain.AVATAR)).isEqualTo("configured-avatar");
			assertThat(store.bucket(StorageDomain.VAULT)).isEqualTo("configured-vault");
			assertThat(store.bucket(StorageDomain.TEMP)).isEqualTo("configured-temp");
			assertThat(store.bucket(StorageDomain.LOOK)).isEqualTo("configured-look");
			assertThat(store.bucket(StorageDomain.TEMP)).isEqualTo("configured-temp");
			assertThat(Set.of(
				store.bucket(StorageDomain.AVATAR),
				store.bucket(StorageDomain.VAULT),
				store.bucket(StorageDomain.LOOK),
				store.bucket(StorageDomain.TEMP))).hasSize(4);
		}

		@Test
		void presignTargetsTheConfiguredBucketForEachBoundary() {
			S3ObjectStore store = store(mock(S3Client.class), (object, age) -> {
			});

			assertThat(store.presignPut(new ObjectRef(StorageDomain.AVATAR, "users/u/avatars/a.jpg"), Duration.ofMinutes(10))
				.uri()
				.toString()).contains("configured-avatar");
			assertThat(store.presignGet(new ObjectRef(StorageDomain.VAULT, "users/u/vault/v.jpg"), Duration.ofMinutes(5))
				.uri()
				.toString()).contains("configured-vault");
			assertThat(store.presignPut(new ObjectRef(StorageDomain.LOOK, "users/u/looks/l.jpg"), Duration.ofMinutes(1))
				.uri()
				.toString()).contains("configured-look");
			assertThat(store.presignGet(new ObjectRef(StorageDomain.TEMP, "temp/u/job/t.jpg"), Duration.ofSeconds(30))
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
			ObjectRef ref = new ObjectRef(StorageDomain.AVATAR, "users/u/avatars/a.jpg");

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
			ObjectRef ref = new ObjectRef(StorageDomain.LOOK, "users/u/looks/l.jpg");

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
			ObjectRef ref = new ObjectRef(StorageDomain.VAULT, "users/u/vault/item.jpg");
			assertThat(ref.objectKey()).isEqualTo("users/u/vault/item.jpg");
			assertThat(ref.objectKey()).doesNotContain("://");

			assertThatThrownBy(() -> new ObjectRef(StorageDomain.AVATAR, "https://configured-avatar.s3.amazonaws.com/users/u/avatars/a.jpg"))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> new ObjectRef(StorageDomain.TEMP, "http://example.invalid/temp/a.jpg"))
				.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		void presignReturnsACappedCapabilityNotAPermanentPublicUrl() {
			S3Client s3 = mock(S3Client.class);
			S3ObjectStore store = store(s3, (object, age) -> {
			});
			ObjectRef ref = new ObjectRef(StorageDomain.AVATAR, "users/u/avatars/a.jpg");

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
			ObjectRef ref = new ObjectRef(StorageDomain.VAULT, "users/u/vault/item.jpg");

			store.delete(ref);

			ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
			verify(s3).deleteObject(captor.capture());
			assertThat(captor.getValue().bucket()).isEqualTo("configured-vault");
			assertThat(captor.getValue().key()).isEqualTo("users/u/vault/item.jpg");
			assertThat(captor.getValue().key()).doesNotContain("://");
		}
	}
	@Nested
	class Listing {

		@Test
		void avatarListingPagesWithTheConfiguredBucketAndPrefixAndMapsSafeMetadata() {
			S3Client s3 = mock(S3Client.class);
			S3ObjectStore store = store(s3, (object, age) -> {
			});
			Instant firstModified = NOW.minus(Duration.ofHours(21));
			when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(
				ListObjectsV2Response.builder()
					.contents(
						obj("users/u/avatars/first.jpg", firstModified),
						obj("", NOW),
						obj("   ", NOW))
					.isTruncated(true)
					.nextContinuationToken("page-2")
					.build(),
				ListObjectsV2Response.builder()
					.contents(obj("users/u/avatars/second.png", null))
					.isTruncated(false)
					.build());

			List<StoredObject> result = store.list(StorageDomain.AVATAR, "users/");

			assertThat(result).containsExactly(
				new StoredObject("users/u/avatars/first.jpg", firstModified),
				new StoredObject("users/u/avatars/second.png", null));
			ArgumentCaptor<ListObjectsV2Request> requests = ArgumentCaptor.forClass(ListObjectsV2Request.class);
			verify(s3, times(2)).listObjectsV2(requests.capture());
			assertThat(requests.getAllValues())
				.extracting(ListObjectsV2Request::bucket)
				.containsExactly("configured-avatar", "configured-avatar");
			assertThat(requests.getAllValues())
				.extracting(ListObjectsV2Request::prefix)
				.containsExactly("users/", "users/");
			assertThat(requests.getAllValues())
				.extracting(ListObjectsV2Request::continuationToken)
				.containsExactly(null, "page-2");
		}

		@Test
		void listingRejectsNullBoundaryAndNullPrefixBeforeCallingS3() {
			S3Client s3 = mock(S3Client.class);
			S3ObjectStore store = store(s3, (object, age) -> {
			});

			assertThatThrownBy(() -> store.list(null, "users/"))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> store.list(StorageDomain.AVATAR, null))
				.isInstanceOf(IllegalArgumentException.class);
			verifyNoInteractions(s3);
		}

		@Test
		void avatarAdapterDelegatesToPersistentListingWithoutReadingMediaOrCreatingCapabilities() {
			ObjectStore objectStore = mock(ObjectStore.class);
			Instant modified = NOW.minus(Duration.ofHours(24));
			when(objectStore.list(StorageDomain.AVATAR, "users/")).thenReturn(List.of(
				new StoredObject("users/u/avatars/first.jpg", modified),
				new StoredObject("users/u/avatars/second.png", null)));
			ObjectStoreAvatarMediaAccess access = new ObjectStoreAvatarMediaAccess(objectStore);

			List<AvatarMediaAccess.PersistentObject> result = access.listPersistentAvatars();

			assertThat(result).containsExactly(
				new AvatarMediaAccess.PersistentObject(new StorageKey("users/u/avatars/first.jpg"), modified),
				new AvatarMediaAccess.PersistentObject(new StorageKey("users/u/avatars/second.png"), null));
			verify(objectStore).list(StorageDomain.AVATAR, "users/");
			verifyNoMoreInteractions(objectStore);
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

		@Test
		void alertsAtTwentyTwoHoursBeforeDeleteEvenWhenDeleteThrows() {
			S3Client s3 = mock(S3Client.class);
			List<String> events = new ArrayList<>();
			S3ObjectStore store = store(s3, (object, age) -> events.add("alert:" + object.objectKey()));
			when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(ListObjectsV2Response.builder()
				.contents(
					obj("age-22h", NOW.minus(Duration.ofHours(22))),
					obj("age-20h", NOW.minus(Duration.ofHours(20))))
				.isTruncated(false)
				.build());
			when(s3.deleteObject(any(DeleteObjectRequest.class))).thenAnswer(invocation -> {
				DeleteObjectRequest request = invocation.getArgument(0);
				events.add("delete:" + request.key());
				if ("age-22h".equals(request.key())) {
					throw SdkClientException.create("https://configured-temp.s3.amazonaws.com/age-22h timed out");
				}
				return null;
			});

			store.cleanupTemp();

			assertThat(events).containsExactly("alert:age-22h", "delete:age-22h", "delete:age-20h");
		}
	}

	@Nested
	class PortAndStagingPut {

		@Test
		void implementsTheObjectStorePort() {
			assertThat(new S3ObjectStore(mock(S3Client.class), presigner, BUCKETS, CLOCK, (object, age) -> {
			})).isInstanceOf(ObjectStore.class);
		}

		@Test
		void stagingPutSignedHeadersIncludeContentTypeLengthAndIfNoneMatch() {
			S3ObjectStore store = store(mock(S3Client.class), (object, age) -> {
			});
			MediaDeclaration declaration = new MediaDeclaration("image/jpeg", 2048L);

			UploadCapability capability = store.presignStagingPut(USER_ID, UPLOAD_ID, declaration);

			assertThat(capability.id()).isEqualTo(UPLOAD_ID);
			assertThat(capability.method()).isEqualTo("PUT");
			assertThat(capability.uri().toString()).contains("configured-temp");
			assertThat(capability.uri().toString()).contains(STAGING_KEY);
			assertThat(capability.uri().getQuery()).contains("X-Amz-Expires=600");
			assertThat(header(capability.signedHeaders(), "content-type")).isEqualTo("image/jpeg");
			assertThat(header(capability.signedHeaders(), "content-length")).isEqualTo("2048");
			assertThat(header(capability.signedHeaders(), "if-none-match")).isEqualTo("*");
			assertThat(capability.toString()).doesNotContain(capability.uri().toString());
		}

		@Test
		void stagingPutWithAvatarPurposeUsesAvatarsGrammar() {
			S3ObjectStore store = store(mock(S3Client.class), (object, age) -> {
			});
			MediaDeclaration declaration = new MediaDeclaration("image/jpeg", 2048L);

			UploadCapability capability = store.presignStagingPut(USER_ID, UPLOAD_ID, declaration, UploadPurpose.AVATAR);

			assertThat(capability.id()).isEqualTo(UPLOAD_ID);
			assertThat(capability.method()).isEqualTo("PUT");
			assertThat(capability.uri().toString()).contains(
				"temp/11111111-1111-1111-1111-111111111111/avatars/22222222-2222-2222-2222-222222222222");
			assertThat(capability.uri().toString()).contains("/avatars/");
			assertThat(capability.uri().toString()).doesNotContain("/uploads/");
			assertThat(header(capability.signedHeaders(), "content-type")).isEqualTo("image/jpeg");
			assertThat(header(capability.signedHeaders(), "content-length")).isEqualTo("2048");
			assertThat(header(capability.signedHeaders(), "if-none-match")).isEqualTo("*");
		}

		@Test
		void putAndGetExpiryQueryRemainSixHundredAndThreeHundred() {
			S3ObjectStore store = store(mock(S3Client.class), (object, age) -> {
			});
			ObjectRef ref = new ObjectRef(StorageDomain.AVATAR, "users/u/avatars/a.jpg");

			assertThat(store.presignPut(ref, Duration.ofMinutes(10)).uri().getQuery()).contains("X-Amz-Expires=600");
			assertThat(store.presignGet(ref, Duration.ofMinutes(5)).uri().getQuery()).contains("X-Amz-Expires=300");
			assertThat(store.presignStagingPut(USER_ID, UPLOAD_ID, new MediaDeclaration("image/png", 12L))
				.uri()
				.getQuery()).contains("X-Amz-Expires=600");
		}
	}

	@Nested
	class HeadAndRead {

		@Test
		void headMapsLengthTypeAndEtagAsVersion() {
			S3Client s3 = mock(S3Client.class);
			S3ObjectStore store = store(s3, (object, age) -> {
			});
			ObjectRef ref = new ObjectRef(StorageDomain.TEMP, STAGING_KEY);
			when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
				.contentLength(88L)
				.contentType("image/png")
				.eTag("\"etag-9\"")
				.build());

			ObjectMetadata metadata = store.head(ref);

			assertThat(metadata.contentLength()).isEqualTo(88L);
			assertThat(metadata.contentType()).isEqualTo("image/png");
			assertThat(metadata.version()).isEqualTo("\"etag-9\"");
			ArgumentCaptor<HeadObjectRequest> captor = ArgumentCaptor.forClass(HeadObjectRequest.class);
			verify(s3).headObject(captor.capture());
			assertThat(captor.getValue().bucket()).isEqualTo("configured-temp");
			assertThat(captor.getValue().key()).isEqualTo(STAGING_KEY);
		}

		@Test
		void headMapsMissingAndUnavailableWithoutUrls() {
			S3Client s3 = mock(S3Client.class);
			S3ObjectStore store = store(s3, (object, age) -> {
			});
			ObjectRef ref = new ObjectRef(StorageDomain.TEMP, STAGING_KEY);

			when(s3.headObject(any(HeadObjectRequest.class)))
				.thenThrow(s3Error(404, "NoSuchKey", "https://configured-temp.s3.amazonaws.com/" + STAGING_KEY));
			assertThatThrownBy(() -> store.head(ref))
				.isInstanceOf(ObjectStoreExceptions.StagedObjectMissingException.class)
				.satisfies(error -> assertThat(error.getMessage()).doesNotContain("://"));

			when(s3.headObject(any(HeadObjectRequest.class)))
				.thenThrow(s3Error(503, "ServiceUnavailable", "https://s3.amazonaws.com/timeout"));
			assertThatThrownBy(() -> store.head(ref))
				.isInstanceOf(ObjectStoreExceptions.ObjectStoreUnavailableException.class)
				.satisfies(error -> assertThat(error.getMessage()).doesNotContain("://"));

			when(s3.headObject(any(HeadObjectRequest.class)))
				.thenThrow(SdkClientException.create("https://configured-temp.s3.amazonaws.com timed out"));
			assertThatThrownBy(() -> store.head(ref))
				.isInstanceOf(ObjectStoreExceptions.ObjectStoreUnavailableException.class)
				.satisfies(error -> assertThat(error.getMessage()).doesNotContain("://"));
		}

		@Test
		void readBoundedCapsBytesAndSendsIfMatchWhenVersionPresent() {
			S3Client s3 = mock(S3Client.class);
			S3ObjectStore store = store(s3, (object, age) -> {
			});
			ObjectRef ref = new ObjectRef(StorageDomain.TEMP, STAGING_KEY);
			when(s3.getObject(any(GetObjectRequest.class)))
				.thenReturn(objectStream(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}));

			byte[] bounded = store.readBounded(ref, "etag-1", 3);

			assertThat(bounded).containsExactly(1, 2, 3);
			ArgumentCaptor<GetObjectRequest> captor = ArgumentCaptor.forClass(GetObjectRequest.class);
			verify(s3).getObject(captor.capture());
			assertThat(captor.getValue().bucket()).isEqualTo("configured-temp");
			assertThat(captor.getValue().key()).isEqualTo(STAGING_KEY);
			assertThat(captor.getValue().ifMatch()).isEqualTo("etag-1");
		}

		@Test
		void readBoundedOmitsIfMatchWhenVersionBlankAndMapsMissing() {
			S3Client s3 = mock(S3Client.class);
			S3ObjectStore store = store(s3, (object, age) -> {
			});
			ObjectRef ref = new ObjectRef(StorageDomain.TEMP, STAGING_KEY);
			when(s3.getObject(any(GetObjectRequest.class)))
				.thenReturn(objectStream(new byte[] {9, 8, 7}))
				.thenThrow(s3Error(404, "NoSuchKey", "https://configured-temp.s3.amazonaws.com/" + STAGING_KEY));

			assertThat(store.readBounded(ref, "  ", 10)).containsExactly(9, 8, 7);
			ArgumentCaptor<GetObjectRequest> captor = ArgumentCaptor.forClass(GetObjectRequest.class);
			verify(s3).getObject(captor.capture());
			assertThat(captor.getValue().ifMatch()).isNull();

			assertThatThrownBy(() -> store.readBounded(ref, null, 10))
				.isInstanceOf(ObjectStoreExceptions.StagedObjectMissingException.class)
				.satisfies(error -> assertThat(error.getMessage()).doesNotContain("://"));
		}
	}

	@Nested
	class Claim {

		@Test
		void returnsCopiedWhenCopySucceeds() {
			S3Client s3 = mock(S3Client.class);
			S3ObjectStore store = store(s3, (object, age) -> {
			});
			stubAvatarStagingMissing(s3, null);
			when(s3.copyObject(any(CopyObjectRequest.class))).thenReturn(CopyObjectResponse.builder().build());

			ClaimResult result = store.claim(command("marker-1"));

			assertThat(result).isInstanceOf(ClaimResult.Copied.class);
			ArgumentCaptor<CopyObjectRequest> captor = ArgumentCaptor.forClass(CopyObjectRequest.class);
			verify(s3).copyObject(captor.capture());
			CopyObjectRequest copy = captor.getValue();
			assertThat(copy.sourceBucket()).isEqualTo("configured-temp");
			assertThat(copy.sourceKey()).isEqualTo(STAGING_KEY);
			assertThat(copy.destinationBucket()).isEqualTo("configured-avatar");
			assertThat(copy.destinationKey()).isEqualTo(DEST_KEY);
			assertThat(copy.copySourceIfMatch()).isEqualTo("source-v1");
			assertThat(copy.metadata()).containsEntry("claim-marker", "marker-1");
		}

		@Test
		void copiesFromAvatarStagingKeyWhenThatObjectExists() {
			S3Client s3 = mock(S3Client.class);
			S3ObjectStore store = store(s3, (object, age) -> {
			});
			when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
				.contentLength(12L)
				.contentType("image/jpeg")
				.eTag("avatar-etag")
				.build());
			when(s3.copyObject(any(CopyObjectRequest.class))).thenReturn(CopyObjectResponse.builder().build());

			ClaimResult result = store.claim(command("marker-1"));

			assertThat(result).isInstanceOf(ClaimResult.Copied.class);
			ArgumentCaptor<HeadObjectRequest> head = ArgumentCaptor.forClass(HeadObjectRequest.class);
			verify(s3).headObject(head.capture());
			assertThat(head.getValue().bucket()).isEqualTo("configured-temp");
			assertThat(head.getValue().key()).isEqualTo(AVATAR_STAGING_KEY);
			ArgumentCaptor<CopyObjectRequest> captor = ArgumentCaptor.forClass(CopyObjectRequest.class);
			verify(s3).copyObject(captor.capture());
			CopyObjectRequest copy = captor.getValue();
			assertThat(copy.sourceBucket()).isEqualTo("configured-temp");
			assertThat(copy.sourceKey()).isEqualTo(AVATAR_STAGING_KEY);
			assertThat(copy.sourceKey()).isEqualTo(
				"temp/11111111-1111-1111-1111-111111111111/avatars/22222222-2222-2222-2222-222222222222");
			assertThat(copy.destinationBucket()).isEqualTo("configured-avatar");
			assertThat(copy.destinationKey()).isEqualTo(DEST_KEY);
			assertThat(copy.copySourceIfMatch()).isEqualTo("source-v1");
			assertThat(copy.metadata()).containsEntry("claim-marker", "marker-1");
		}

		@Test
		void returnsReplayWhenDestMarkerMatches() {
			S3Client s3 = mock(S3Client.class);
			S3ObjectStore store = store(s3, (object, age) -> {
			});
			when(s3.copyObject(any(CopyObjectRequest.class)))
				.thenThrow(s3Error(412, "PreconditionFailed", "https://configured-avatar.s3.amazonaws.com/" + DEST_KEY));
			stubAvatarStagingMissing(s3, HeadObjectResponse.builder()
				.contentType("image/jpeg")
				.contentLength(4L)
				.eTag("dest-etag")
				.metadata(Map.of("claim-marker", "marker-1"))
				.build());

			assertThat(store.claim(command("marker-1"))).isInstanceOf(ClaimResult.Replay.class);
			ArgumentCaptor<HeadObjectRequest> captor = ArgumentCaptor.forClass(HeadObjectRequest.class);
			verify(s3, times(2)).headObject(captor.capture());
			assertThat(captor.getAllValues())
				.extracting(HeadObjectRequest::key)
				.containsExactly(AVATAR_STAGING_KEY, DEST_KEY);
			assertThat(captor.getAllValues().get(1).bucket()).isEqualTo("configured-avatar");
		}

		@Test
		void returnsConflictWhenDestMarkerDiffers() {
			S3Client s3 = mock(S3Client.class);
			S3ObjectStore store = store(s3, (object, age) -> {
			});
			when(s3.copyObject(any(CopyObjectRequest.class)))
				.thenThrow(s3Error(412, "PreconditionFailed", "precondition"));
			stubAvatarStagingMissing(s3, HeadObjectResponse.builder()
				.contentType("image/jpeg")
				.contentLength(4L)
				.eTag("dest-etag")
				.metadata(Map.of("claim-marker", "other-marker"))
				.build());

			assertThat(store.claim(command("marker-1"))).isInstanceOf(ClaimResult.Conflict.class);
		}

		@Test
		void sourceMissingIsStagedObjectMissingWithoutUrls() {
			S3Client s3 = mock(S3Client.class);
			S3ObjectStore store = store(s3, (object, age) -> {
			});
			stubAvatarStagingMissing(s3, null);
			when(s3.copyObject(any(CopyObjectRequest.class)))
				.thenThrow(s3Error(404, "NoSuchKey", "https://configured-temp.s3.amazonaws.com/" + STAGING_KEY));

			assertThatThrownBy(() -> store.claim(command("marker-1")))
				.isInstanceOf(ObjectStoreExceptions.StagedObjectMissingException.class)
				.satisfies(error -> assertThat(error.getMessage()).doesNotContain("://"));
		}
	}

	@Nested
	class Purge {

		@Test
		void purgePagesAllFourUserPrefixesAcrossTwoPages() {
			S3Client s3 = mock(S3Client.class);
			S3ObjectStore store = store(s3, (object, age) -> {
			});
			when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenAnswer(invocation -> {
				ListObjectsV2Request request = invocation.getArgument(0);
				if ("configured-avatar".equals(request.bucket()) && USER_PREFIX.equals(request.prefix())) {
					if (request.continuationToken() == null) {
						return ListObjectsV2Response.builder()
							.contents(obj("avatar-1", NOW))
							.isTruncated(true)
							.nextContinuationToken("page-2")
							.build();
					}
					if ("page-2".equals(request.continuationToken())) {
						return ListObjectsV2Response.builder()
							.contents(obj("avatar-2", NOW))
							.isTruncated(false)
							.build();
					}
				}
				if ("configured-vault".equals(request.bucket()) && USER_PREFIX.equals(request.prefix())) {
					return ListObjectsV2Response.builder().contents(obj("vault-1", NOW)).isTruncated(false).build();
				}
				if ("configured-look".equals(request.bucket()) && USER_PREFIX.equals(request.prefix())) {
					return ListObjectsV2Response.builder().contents(obj("look-1", NOW)).isTruncated(false).build();
				}
				if ("configured-temp".equals(request.bucket()) && TEMP_PREFIX.equals(request.prefix())) {
					return ListObjectsV2Response.builder().contents(obj("temp-1", NOW)).isTruncated(false).build();
				}
				if ("configured-temp".equals(request.bucket()) && GENERATION_INPUT_PREFIX.equals(request.prefix())) {
					return ListObjectsV2Response.builder().contents(obj("gen-input-1", NOW)).isTruncated(false).build();
				}
				return ListObjectsV2Response.builder().isTruncated(false).build();
			});

			store.purgeUserObjects(USER_ID);

			ArgumentCaptor<ListObjectsV2Request> lists = ArgumentCaptor.forClass(ListObjectsV2Request.class);
			verify(s3, times(6)).listObjectsV2(lists.capture());
			assertThat(lists.getAllValues())
				.anyMatch(request -> "configured-avatar".equals(request.bucket())
					&& USER_PREFIX.equals(request.prefix())
					&& request.continuationToken() == null)
				.anyMatch(request -> "configured-avatar".equals(request.bucket())
					&& USER_PREFIX.equals(request.prefix())
					&& "page-2".equals(request.continuationToken()))
				.anyMatch(request -> "configured-vault".equals(request.bucket()) && USER_PREFIX.equals(request.prefix()))
				.anyMatch(request -> "configured-look".equals(request.bucket()) && USER_PREFIX.equals(request.prefix()))
				.anyMatch(request -> "configured-temp".equals(request.bucket()) && TEMP_PREFIX.equals(request.prefix()))
				.anyMatch(request -> "configured-temp".equals(request.bucket())
					&& GENERATION_INPUT_PREFIX.equals(request.prefix()));

			ArgumentCaptor<DeleteObjectRequest> deleted = ArgumentCaptor.forClass(DeleteObjectRequest.class);
			verify(s3, times(6)).deleteObject(deleted.capture());
			assertThat(deleted.getAllValues())
				.extracting(DeleteObjectRequest::key)
				.containsExactly("avatar-1", "avatar-2", "gen-input-1", "vault-1", "look-1", "temp-1");
		}

		@Test
		void purgeSurfacesAFailingObjectDeleteInsteadOfReportingCompletedErasure() {
			S3Client s3 = mock(S3Client.class);
			S3ObjectStore store = store(s3, (object, age) -> {
			});
			when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenAnswer(invocation -> {
				ListObjectsV2Request request = invocation.getArgument(0);
				if ("configured-vault".equals(request.bucket()) && USER_PREFIX.equals(request.prefix())) {
					return ListObjectsV2Response.builder().contents(obj("vault-1", NOW)).isTruncated(false).build();
				}
				return ListObjectsV2Response.builder().isTruncated(false).build();
			});
			when(s3.deleteObject(any(DeleteObjectRequest.class))).thenAnswer(invocation -> {
				DeleteObjectRequest request = invocation.getArgument(0);
				if ("vault-1".equals(request.key())) {
					throw SdkClientException.create("https://configured-vault.s3.amazonaws.com/vault-1 timed out");
				}
				return null;
			});

			assertThatThrownBy(() -> store.purgeUserObjects(USER_ID))
				.isInstanceOf(ObjectStoreExceptions.ObjectStoreUnavailableException.class);
		}

		@Test
		void tempRetentionSweepStaysBestEffortWhenItsDeleteFails() {
			S3Client s3 = mock(S3Client.class);
			S3ObjectStore store = store(s3, (object, age) -> {
			});
			when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(ListObjectsV2Response.builder()
				.contents(obj("age-20h", NOW.minus(Duration.ofHours(20))))
				.isTruncated(false)
				.build());
			when(s3.deleteObject(any(DeleteObjectRequest.class)))
				.thenThrow(SdkClientException.create("https://configured-temp.s3.amazonaws.com/age-20h timed out"));

			assertThatCode(() -> store.cleanupTemp()).doesNotThrowAnyException();

			verify(s3).deleteObject(any(DeleteObjectRequest.class));
		}
	}

	@Nested
	class GenerationMedia {

		private static final String GENERATION_TEMP_KEY =
			"temp/11111111-1111-1111-1111-111111111111/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb.jpg";
		private static final String GENERATION_LOOK_KEY =
			"users/11111111-1111-1111-1111-111111111111/looks/cccccccc-cccc-cccc-cccc-cccccccccccc.jpg";

		@Test
		void implementsGenerationMediaStore() {
			assertThat(store(mock(S3Client.class), (object, age) -> {
			})).isInstanceOf(GenerationMediaStore.class);
		}

		@Test
		void putBoundedWritesPrivateTempObjectWithinBoundUsingIfNoneMatch() {
			S3Client s3 = mock(S3Client.class);
			S3ObjectStore store = store(s3, (object, age) -> {
			});
			byte[] payload = {1, 2, 3, 4};

			store.putBounded(new StorageKey(GENERATION_TEMP_KEY), new ByteArrayInputStream(payload), 4, "image/jpeg");

			ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
			verify(s3).putObject(captor.capture(), any(RequestBody.class));
			PutObjectRequest put = captor.getValue();
			assertThat(put.bucket()).isEqualTo("configured-temp");
			assertThat(put.key()).isEqualTo(GENERATION_TEMP_KEY);
			assertThat(put.ifNoneMatch()).isEqualTo("*");
			assertThat(put.contentType()).isEqualTo("image/jpeg");
			assertThat(put.contentLength()).isEqualTo(4L);
			assertThat(put.acl()).isNull();
			assertThat(put.key()).doesNotContain("://");
		}

		@Test
		void putBoundedRejectsOversizedStreamWithoutPut() {
			S3Client s3 = mock(S3Client.class);
			S3ObjectStore store = store(s3, (object, age) -> {
			});

			assertThatThrownBy(() -> store.putBounded(
				new StorageKey(GENERATION_TEMP_KEY),
				new ByteArrayInputStream(new byte[] {1, 2, 3, 4}),
				3,
				"image/jpeg"))
				.isInstanceOf(IllegalArgumentException.class);
			verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
		}

		@Test
		void putBoundedTreatsExistingObjectAsReplay() {
			S3Client s3 = mock(S3Client.class);
			S3ObjectStore store = store(s3, (object, age) -> {
			});
			when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
				.thenThrow(s3Error(412, "PreconditionFailed", "https://configured-temp.s3.amazonaws.com/" + GENERATION_TEMP_KEY));

			store.putBounded(
				new StorageKey(GENERATION_TEMP_KEY),
				new ByteArrayInputStream(new byte[] {9, 8, 7}),
				10,
				"image/png");

			verify(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));
		}

		@Test
		void generationClaimCopiesTempToLookThenReplays() {
			S3Client s3 = mock(S3Client.class);
			S3ObjectStore store = store(s3, (object, age) -> {
			});
			when(s3.copyObject(any(CopyObjectRequest.class)))
				.thenReturn(CopyObjectResponse.builder().build())
				.thenThrow(s3Error(412, "PreconditionFailed", "https://configured-look.s3.amazonaws.com/" + GENERATION_LOOK_KEY));
			when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
				.contentType("image/jpeg")
				.contentLength(4L)
				.eTag("look-etag")
				.metadata(Map.of("claim-marker", "gen-marker"))
				.build());
			GenerationClaimCommand command = new GenerationClaimCommand(
				USER_ID,
				new StorageKey(GENERATION_TEMP_KEY),
				new StorageKey(GENERATION_LOOK_KEY),
				"source-v1",
				"gen-marker");

			assertThat(store.claim(command)).isInstanceOf(ClaimResult.Copied.class);
			assertThat(store.claim(command)).isInstanceOf(ClaimResult.Replay.class);

			ArgumentCaptor<CopyObjectRequest> captor = ArgumentCaptor.forClass(CopyObjectRequest.class);
			verify(s3, times(2)).copyObject(captor.capture());
			CopyObjectRequest copy = captor.getAllValues().getFirst();
			assertThat(copy.sourceBucket()).isEqualTo("configured-temp");
			assertThat(copy.sourceKey()).isEqualTo(GENERATION_TEMP_KEY);
			assertThat(copy.destinationBucket()).isEqualTo("configured-look");
			assertThat(copy.destinationKey()).isEqualTo(GENERATION_LOOK_KEY);
			assertThat(copy.copySourceIfMatch()).isEqualTo("source-v1");
			assertThat(copy.metadata()).containsEntry("claim-marker", "gen-marker");
			assertThat(copy.sourceKey()).doesNotContain("://");
			assertThat(copy.destinationKey()).doesNotContain("://");
		}
	}


	private S3ObjectStore store(S3Client s3, TempObjectAgeAlert alert) {
		return new S3ObjectStore(s3, presigner, BUCKETS, CLOCK, alert);
	}

	private static S3Object obj(String key, Instant lastModified) {
		return S3Object.builder().key(key).lastModified(lastModified).build();
	}

	private static void stubAvatarStagingMissing(S3Client s3, HeadObjectResponse destHead) {
		when(s3.headObject(any(HeadObjectRequest.class))).thenAnswer(invocation -> {
			HeadObjectRequest request = invocation.getArgument(0);
			if (AVATAR_STAGING_KEY.equals(request.key())) {
				throw s3Error(404, "NoSuchKey", "https://configured-temp.s3.amazonaws.com/" + AVATAR_STAGING_KEY);
			}
			if (DEST_KEY.equals(request.key()) && destHead != null) {
				return destHead;
			}
			throw s3Error(404, "NoSuchKey", "missing");
		});
	}

	private static ClaimCommand command(String claimMarker) {
		return new ClaimCommand(
			USER_ID,
			UPLOAD_ID,
			new StorageKey(DEST_KEY),
			StorageDomain.AVATAR,
			claimMarker,
			"source-v1");
	}

	private static String header(Map<String, String> headers, String name) {
		return headers.entrySet()
			.stream()
			.filter(entry -> entry.getKey() != null && entry.getKey().equalsIgnoreCase(name))
			.map(Map.Entry::getValue)
			.findFirst()
			.orElse(null);
	}

	private static S3Exception s3Error(int status, String code, String message) {
		return (S3Exception) S3Exception.builder()
			.statusCode(status)
			.message(message)
			.awsErrorDetails(AwsErrorDetails.builder()
				.errorCode(code)
				.errorMessage(message)
				.serviceName("S3")
				.build())
			.build();
	}

	private static ResponseInputStream<GetObjectResponse> objectStream(byte[] payload) {
		return new ResponseInputStream<>(
			GetObjectResponse.builder().contentLength((long) payload.length).build(),
			new ByteArrayInputStream(payload));
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
