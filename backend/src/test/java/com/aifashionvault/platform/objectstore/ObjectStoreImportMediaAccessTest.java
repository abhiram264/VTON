package com.aifashionvault.platform.objectstore;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.aifashionvault.importer.ImportMediaAccess.ClaimOutcome;
import com.aifashionvault.importer.ImportMediaAccess.VerifiedImage;
import com.aifashionvault.platform.environment.VaultEnvironmentProperties;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused regression tests for the slice-27 round-2 findings on
 * {@link ImportObjectWriter} and {@link ObjectStoreImportMediaAccess}:
 * typed translation of service-side S3 failures, one-time staging
 * consumption on claim, and storing the inspected content type.
 */
class ObjectStoreImportMediaAccessTest {

	private static final UserId USER_ID = new UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
	private static final UploadId UPLOAD_ID = new UploadId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
	private static final String STAGING_KEY =
		"temp/11111111-1111-1111-1111-111111111111/uploads/22222222-2222-2222-2222-222222222222";
	private static final String DEST_KEY = "users/11111111-1111-1111-1111-111111111111/imports/item.png";
	private static final VaultEnvironmentProperties.S3 BUCKETS = new VaultEnvironmentProperties.S3(
		"configured-avatar",
		"configured-vault",
		"configured-look",
		"configured-temp");

	private final S3Presigner presigner = S3Presigner.builder()
		.region(Region.US_EAST_1)
		.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
		.build();

	@Test
	void putIfAbsentTranslatesServiceSideS3FailureToUnavailable() {
		S3Client s3 = mock(S3Client.class);
		when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
			.thenThrow(s3Error(500, "InternalError", "s3 unavailable"));
		ImportObjectWriter writer = new ImportObjectWriter(store(s3), s3);

		assertThatThrownBy(() -> writer.putIfAbsent(
			new ObjectRef(StorageBoundary.VAULT, DEST_KEY),
			new ByteArrayInputStream(new byte[] {1, 2, 3}),
			3,
			"claim-marker",
			"image/png"))
			.isInstanceOf(ObjectStoreExceptions.ObjectStoreUnavailableException.class)
			.isNotInstanceOf(S3Exception.class);
	}

	@Test
	void putIfAbsentStillReturnsFalseOnPreconditionFailure() {
		S3Client s3 = mock(S3Client.class);
		when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
			.thenThrow(s3Error(412, "PreconditionFailed", "condition failed"));
		ImportObjectWriter writer = new ImportObjectWriter(store(s3), s3);

		boolean created = writer.putIfAbsent(
			new ObjectRef(StorageBoundary.VAULT, DEST_KEY),
			new ByteArrayInputStream(new byte[] {1, 2, 3}),
			3,
			"claim-marker",
			"image/png");

		assertThat(created).isFalse();
	}

	@Test
	void claimConsumesStagingAfterSuccessfulCopy() {
		S3Client s3 = mock(S3Client.class);
		stubAvatarStagingMissing(s3);
		when(s3.copyObject(any(CopyObjectRequest.class))).thenReturn(CopyObjectResponse.builder().build());
		when(s3.deleteObject(any(DeleteObjectRequest.class)))
			.thenThrow(s3Error(500, "InternalError", "s3 unavailable"));
		S3ObjectStore store = store(s3);
		ObjectStoreImportMediaAccess access = new ObjectStoreImportMediaAccess(store, new ImageInspector());

		ClaimOutcome outcome = access.claimVerifiedUpload(USER_ID, verifiedImage(), new StorageKey(DEST_KEY));

		assertThat(outcome).isEqualTo(ClaimOutcome.CLAIMED);
		ArgumentCaptor<DeleteObjectRequest> deleteCaptor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
		verify(s3, times(1)).deleteObject(deleteCaptor.capture());
		assertThat(deleteCaptor.getValue().bucket()).isEqualTo("configured-temp");
		assertThat(deleteCaptor.getValue().key()).isEqualTo(STAGING_KEY);
	}

	@Test
	void secondClaimOfSameUploadFindsNothingClaimable() {
		S3Client s3 = mock(S3Client.class);
		stubAvatarStagingMissing(s3);
		when(s3.copyObject(any(CopyObjectRequest.class))).thenReturn(CopyObjectResponse.builder().build());
		when(s3.deleteObject(any(DeleteObjectRequest.class))).thenReturn(null);
		S3ObjectStore store = store(s3);
		ObjectStoreImportMediaAccess access = new ObjectStoreImportMediaAccess(store, new ImageInspector());

		ClaimOutcome first = access.claimVerifiedUpload(USER_ID, verifiedImage(), new StorageKey(DEST_KEY));
		assertThat(first).isEqualTo(ClaimOutcome.CLAIMED);

		// The staging upload was consumed by the first claim, so a second claim
		// of the same uploadId (fresh destination) finds no source object.
		when(s3.copyObject(any(CopyObjectRequest.class))).thenThrow(s3Error(404, "NoSuchKey", "staging consumed"));
		assertThatThrownBy(() -> access.claimVerifiedUpload(USER_ID, verifiedImage(),
				new StorageKey("users/11111111-1111-1111-1111-111111111111/imports/other.png")))
			.isInstanceOf(ObjectStoreExceptions.StagedObjectMissingException.class);
	}

	@Test
	void publishFetchedImageStoresTheProvidedContentType() {
		S3Client s3 = mock(S3Client.class);
		when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
			.thenReturn(PutObjectResponse.builder().build());
		S3ObjectStore store = store(s3);
		ObjectStoreImportMediaAccess access = new ObjectStoreImportMediaAccess(
			store, new ImageInspector(), new ImportObjectWriter(store, s3), USER_ID);

		boolean published = access.publishFetchedImage(
			new ByteArrayInputStream(new byte[] {1, 2, 3}),
			3,
			new StorageKey(DEST_KEY),
			"image/png");

		assertThat(published).isTrue();
		ArgumentCaptor<PutObjectRequest> putCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
		verify(s3, times(1)).putObject(putCaptor.capture(), any(RequestBody.class));
		assertThat(putCaptor.getValue().contentType()).isEqualTo("image/png");
	}

	@Test
	void publishFetchedImageRejectsNonImageContentType() {
		S3Client s3 = mock(S3Client.class);
		S3ObjectStore store = store(s3);
		ObjectStoreImportMediaAccess access = new ObjectStoreImportMediaAccess(
			store, new ImageInspector(), new ImportObjectWriter(store, s3), USER_ID);

		assertThatThrownBy(() -> access.publishFetchedImage(
			new ByteArrayInputStream(new byte[] {1, 2, 3}),
			3,
			new StorageKey(DEST_KEY),
			"text/html"))
			.isInstanceOf(IllegalArgumentException.class);
		verify(s3, times(0)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
	}

	private S3ObjectStore store(S3Client s3) {
		return new S3ObjectStore(s3, presigner, BUCKETS, Clock.systemUTC(), (object, age) -> {
		});
	}

	/** claimSource probes the avatar staging key first; it must report missing. */
	private static void stubAvatarStagingMissing(S3Client s3) {
		when(s3.headObject(any(HeadObjectRequest.class)))
			.thenThrow(s3Error(404, "NoSuchKey", "no avatar staging object"));
	}

	private static VerifiedImage verifiedImage() {
		return new VerifiedImage(UPLOAD_ID, "image/png", 3L, "source-v1");
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
}
