package com.aifashionvault.shared;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TypedIdsTest {

	private static final UUID VALUE = UUID.fromString("22222222-2222-2222-2222-222222222222");

	@Test
	void constructsEveryListedId() {
		assertThat(new UserId(VALUE).value()).isEqualTo(VALUE);
		assertThat(new IdentityMappingId(VALUE).value()).isEqualTo(VALUE);
		assertThat(new AvatarProfileId(VALUE).value()).isEqualTo(VALUE);
		assertThat(new AvatarImageId(VALUE).value()).isEqualTo(VALUE);
		assertThat(new ShareImportId(VALUE).value()).isEqualTo(VALUE);
		assertThat(new VaultItemId(VALUE).value()).isEqualTo(VALUE);
		assertThat(new SourceReferenceId(VALUE).value()).isEqualTo(VALUE);
		assertThat(new CollectionId(VALUE).value()).isEqualTo(VALUE);
		assertThat(new StudioSessionId(VALUE).value()).isEqualTo(VALUE);
		assertThat(new StudioItemId(VALUE).value()).isEqualTo(VALUE);
		assertThat(new GenerationJobId(VALUE).value()).isEqualTo(VALUE);
		assertThat(new GenerationAttemptId(VALUE).value()).isEqualTo(VALUE);
		assertThat(new QualityResultId(VALUE).value()).isEqualTo(VALUE);
		assertThat(new LookId(VALUE).value()).isEqualTo(VALUE);
		assertThat(new NotificationId(VALUE).value()).isEqualTo(VALUE);
		assertThat(new ConsentId(VALUE).value()).isEqualTo(VALUE);
	}

	@Test
	void rejectsNullUuid() {
		assertThatThrownBy(() -> new UserId(null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new IdentityMappingId(null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AvatarProfileId(null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AvatarImageId(null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ShareImportId(null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new VaultItemId(null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new SourceReferenceId(null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CollectionId(null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new StudioSessionId(null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new StudioItemId(null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new GenerationJobId(null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new GenerationAttemptId(null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new QualityResultId(null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new LookId(null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new NotificationId(null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ConsentId(null)).isInstanceOf(IllegalArgumentException.class);
	}
}
