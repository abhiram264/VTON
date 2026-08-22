package com.aifashionvault.platform.web;

import java.lang.reflect.RecordComponent;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MeResponseJsonTest {

	private static final String CANONICAL_ID = "11111111-1111-1111-1111-111111111111";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Test
	void hasExactlyOneStringIdComponent() {
		assertThat(MeResponse.class.getRecordComponents())
			.extracting(RecordComponent::getName)
			.containsExactly("id")
			.doesNotContain("firebaseUid", "issuer", "subject");
		assertThat(MeResponse.class.getRecordComponents())
			.extracting(RecordComponent::getType)
			.containsExactly(String.class);
	}

	@Test
	void serializesCanonicalUuidAsIdOnly() throws Exception {
		String json = MAPPER.writeValueAsString(new MeResponse(CANONICAL_ID));

		assertThat(json).isEqualTo("{\"id\":\"" + CANONICAL_ID + "\"}");
	}

	@Test
	void serializedJsonOmitsFirebaseUidIssuerAndSubject() throws Exception {
		String json = MAPPER.writeValueAsString(new MeResponse(CANONICAL_ID));

		assertThat(json).doesNotContain("firebaseUid", "issuer", "subject");
	}

	@Test
	void rejectsNullId() {
		assertThatThrownBy(() -> new MeResponse(null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsBlankId() {
		assertThatThrownBy(() -> new MeResponse(""))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new MeResponse("   "))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
