package com.aifashionvault.shared;

import java.lang.reflect.RecordComponent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorageKeyTest {

	@Test
	void validObjectKeyConstructs() {
		StorageKey key = new StorageKey("users/11111111-1111-1111-1111-111111111111/avatars/22222222-2222-2222-2222-222222222222.jpg");

		assertThat(key.value()).isEqualTo("users/11111111-1111-1111-1111-111111111111/avatars/22222222-2222-2222-2222-222222222222.jpg");
	}

	@Test
	void surroundingWhitespaceIsStripped() {
		StorageKey key = new StorageKey("  users/u/vault/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa.jpg  ");

		assertThat(key.value()).isEqualTo("users/u/vault/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa.jpg");
	}

	@Test
	void nullValueThrowsIllegalArgumentException() {
		assertThatThrownBy(() -> new StorageKey(null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void blankValueThrowsIllegalArgumentException() {
		assertThatThrownBy(() -> new StorageKey(""))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new StorageKey("   "))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void httpAndHttpsUrlsThrowIllegalArgumentException() {
		assertThatThrownBy(() -> new StorageKey("http://example.invalid/users/u/avatars/a.jpg"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new StorageKey("https://configured-avatar.s3.amazonaws.com/users/u/avatars/a.jpg"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new StorageKey("HTTPS://example.invalid/users/u/avatars/a.jpg"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void dataUrisThrowIllegalArgumentException() {
		assertThatThrownBy(() -> new StorageKey("data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new StorageKey("DATA:image/jpeg;base64,/9j/4AAQ"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void obviousBase64ImagePayloadsThrowIllegalArgumentException() {
		assertThatThrownBy(() -> new StorageKey("/9j/4AAQSkZJRgABAQAAAQABAAD/2wBD"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new StorageKey("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void durableSignedUrlStringsThrowIllegalArgumentException() {
		assertThatThrownBy(() -> new StorageKey(
			"users/u/avatars/a.jpg?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=AKIA&X-Amz-Signature=abcdef"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new StorageKey(
			"https://bucket.s3.amazonaws.com/users/u/avatars/a.jpg?X-Amz-Signature=abcdef"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new StorageKey("users/u/avatars/a.jpg?x-amz-algorithm=AWS4-HMAC-SHA256"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void doesNotDependOnPlatformObjectStore() {
		assertThat(StorageKey.class.getPackageName()).isEqualTo("com.aifashionvault.shared");
		assertThat(StorageKey.class.getRecordComponents())
			.extracting(RecordComponent::getType)
			.containsExactly(String.class);
	}
}
