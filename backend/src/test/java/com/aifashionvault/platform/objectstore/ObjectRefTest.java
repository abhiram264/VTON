package com.aifashionvault.platform.objectstore;

import com.aifashionvault.shared.StorageDomain;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObjectRefTest {
    @Test
    void storageDomainValuesAreExactlyDefined() {
        assertThat(StorageDomain.values()).containsExactly(
            StorageDomain.AVATAR,
            StorageDomain.VAULT,
            StorageDomain.LOOK,
            StorageDomain.TEMP);
    }

	@Test
	void validVaultObjectKeyConstructs() {
		ObjectRef ref = new ObjectRef(
			StorageDomain.VAULT,
			"users/11111111-1111-1111-1111-111111111111/vault/22222222-2222-2222-2222-222222222222.jpg");

		assertThat(ref.boundary()).isEqualTo(StorageDomain.VAULT);
		assertThat(ref.objectKey()).isEqualTo(
			"users/11111111-1111-1111-1111-111111111111/vault/22222222-2222-2222-2222-222222222222.jpg");
	}


	@Test
	void surroundingWhitespaceIsStripped() {
		ObjectRef ref = new ObjectRef(StorageDomain.VAULT, "  users/u/vault/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa.jpg  ");

		assertThat(ref.objectKey()).isEqualTo("users/u/vault/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa.jpg");
	}

	@Test
	void nullBoundaryThrowsIllegalArgumentException() {

		assertThatThrownBy(() -> new ObjectRef(null, "users/u/vault/a.jpg"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void nullValueThrowsIllegalArgumentException() {
		assertThatThrownBy(() -> new ObjectRef(StorageDomain.VAULT, null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void blankValueThrowsIllegalArgumentException() {
		assertThatThrownBy(() -> new ObjectRef(StorageDomain.VAULT, ""))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ObjectRef(StorageDomain.VAULT, "   "))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void httpAndHttpsUrlsThrowIllegalArgumentException() {
		assertThatThrownBy(() -> new ObjectRef(StorageDomain.VAULT, "http://example.invalid/users/u/avatars/a.jpg"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ObjectRef(
			StorageDomain.AVATAR,
			"https://configured-avatar.s3.amazonaws.com/users/u/avatars/a.jpg"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ObjectRef(StorageDomain.VAULT, "HTTPS://example.invalid/users/u/avatars/a.jpg"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void dataUrisThrowIllegalArgumentException() {
		assertThatThrownBy(() -> new ObjectRef(
			StorageDomain.VAULT,
			"data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ObjectRef(StorageDomain.VAULT, "DATA:image/jpeg;base64,/9j/4AAQ"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void obviousBase64ImagePayloadsThrowIllegalArgumentException() {
		assertThatThrownBy(() -> new ObjectRef(StorageDomain.VAULT, "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBD"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ObjectRef(
			StorageDomain.VAULT,
			"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ObjectRef(StorageDomain.VAULT, "R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ObjectRef(StorageDomain.VAULT, "UklGRiQAAABXRUJQVlA4IBgAAAAwAQCdASoBAAEAAwA0JaQAA3AA/vuUAAA="))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ObjectRef(StorageDomain.VAULT, "IVBORW0KGGOAAAANSUhEUg"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ObjectRef(StorageDomain.VAULT, "/9j/\n4AAQSkZJRg"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void durableSignedUrlStringsThrowIllegalArgumentException() {
		assertThatThrownBy(() -> new ObjectRef(
			StorageDomain.VAULT,
			"users/u/avatars/a.jpg?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=AKIA&X-Amz-Signature=abcdef"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ObjectRef(
			StorageDomain.VAULT,
			"https://bucket.s3.amazonaws.com/users/u/avatars/a.jpg?X-Amz-Signature=abcdef"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ObjectRef(
			StorageDomain.VAULT,
			"users/u/avatars/a.jpg?x-amz-algorithm=AWS4-HMAC-SHA256"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void keepsTwoArgBoundaryAndKeyConstructor() {
		assertThat(ObjectRef.class.getConstructors()).hasSize(1);
		assertThat(ObjectRef.class.getConstructors()[0].getParameterTypes())
			.containsExactly(StorageDomain.class, String.class);
	}
}
