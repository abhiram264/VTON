package com.aifashionvault.shared;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientRequestIdTest {

	@Test
	void nonBlankValueConstructs() {
		ClientRequestId id = new ClientRequestId("req-123");

		assertThat(id.value()).isEqualTo("req-123");
	}

	@Test
	void nullValueThrowsIllegalArgumentException() {
		assertThatThrownBy(() -> new ClientRequestId(null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void blankValueThrowsIllegalArgumentException() {
		assertThatThrownBy(() -> new ClientRequestId(""))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ClientRequestId("   "))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
