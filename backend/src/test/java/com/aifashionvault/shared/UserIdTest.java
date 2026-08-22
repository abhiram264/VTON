package com.aifashionvault.shared;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserIdTest {

	@Test
	void rejectsNullUuid() {
		assertThatThrownBy(() -> new UserId(null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void equalWhenUuidEqual() {
		UUID uuid = UUID.fromString("11111111-1111-1111-1111-111111111111");
		assertThat(new UserId(uuid)).isEqualTo(new UserId(uuid));
		assertThat(new UserId(uuid).value()).isEqualTo(uuid);
	}

	@Test
	void wrapsUuidOnly() {
		assertThat(UserId.class.getRecordComponents()).hasSize(1);
		assertThat(UserId.class.getRecordComponents()[0].getType()).isEqualTo(UUID.class);
		assertThatThrownBy(() -> UserId.class.getConstructor(String.class))
			.isInstanceOf(NoSuchMethodException.class);
	}
}
