package com.aifashionvault.shared;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadIdTest {

	@Test
	void rejectsNullUuid() {
		assertThatThrownBy(() -> new UploadId(null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void equalWhenUuidEqual() {
		UUID uuid = UUID.fromString("11111111-1111-1111-1111-111111111111");
		assertThat(new UploadId(uuid)).isEqualTo(new UploadId(uuid));
		assertThat(new UploadId(uuid).value()).isEqualTo(uuid);
	}

	@Test
	void wrapsUuidOnly() {
		assertThat(UploadId.class.getRecordComponents()).hasSize(1);
		assertThat(UploadId.class.getRecordComponents()[0].getType()).isEqualTo(UUID.class);
		assertThatThrownBy(() -> UploadId.class.getConstructor(String.class))
			.isInstanceOf(NoSuchMethodException.class);
	}
}
