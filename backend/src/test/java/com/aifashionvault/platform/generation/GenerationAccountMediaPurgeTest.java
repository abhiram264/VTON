package com.aifashionvault.platform.generation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.aifashionvault.generation.GenerationStore;
import com.aifashionvault.platform.objectstore.ObjectStore;
import com.aifashionvault.shared.AccountMediaPurge;
import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class GenerationAccountMediaPurgeTest {

	private static final Instant NOW = Instant.parse("2026-08-26T12:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
	private static final UserId USER_ID = new UserId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));

	@Test
	void implementsAccountMediaPurge() {
		assertThat(newPurge(mock(GenerationStore.class), mock(ObjectStore.class)))
			.isInstanceOf(AccountMediaPurge.class);
	}

	@Test
	void purgeForUserMarksJobsPurgesObjectsThenDeletesRows() {
		GenerationStore generationStore = mock(GenerationStore.class);
		ObjectStore objectStore = mock(ObjectStore.class);

		newPurge(generationStore, objectStore).purgeForUser(USER_ID);

		InOrder order = inOrder(generationStore, objectStore);
		order.verify(generationStore).markAllSubjectDeleted(USER_ID, NOW);
		order.verify(objectStore).purgeUserObjects(USER_ID);
		order.verify(generationStore).deleteUserLinkedGenerationData(USER_ID);
		verifyNoMoreInteractions(generationStore, objectStore);
	}

	@Test
	void rejectsNullConstructorArgsAndUserId() {
		GenerationStore generationStore = mock(GenerationStore.class);
		ObjectStore objectStore = mock(ObjectStore.class);

		assertThatThrownBy(() -> new GenerationAccountMediaPurge(null, objectStore, CLOCK))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("generationStore is required");
		assertThatThrownBy(() -> new GenerationAccountMediaPurge(generationStore, null, CLOCK))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("objectStore is required");
		assertThatThrownBy(() -> new GenerationAccountMediaPurge(generationStore, objectStore, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("clock is required");
		assertThatThrownBy(() -> newPurge(generationStore, objectStore).purgeForUser(null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("userId is required");
		verifyNoInteractions(generationStore, objectStore);
	}

	@Test
	void sourceHasNoProviderTypesAndDoesNotClaimRemoteErasure() throws Exception {
		String source = Files.readString(productionFile(
			"platform", "generation", "GenerationAccountMediaPurge.java"), StandardCharsets.UTF_8);
		String config = Files.readString(productionFile(
			"platform", "generation", "GenerationProcessorConfiguration.java"), StandardCharsets.UTF_8);

		assertThat(source)
			.contains("implements AccountMediaPurge")
			.contains("markAllSubjectDeleted")
			.contains("purgeUserObjects")
			.doesNotContain("VtonProvider")
			.doesNotContain("Fashn")
			.doesNotContain("ProviderJobId")
			.doesNotContain("ProviderRegistry")
			.doesNotContain("com.aifashionvault.generation.vton")
			.doesNotContain("Logger")
			.doesNotContain("erasure")
			.doesNotContain("erased");
		assertThat(config)
			.contains("GenerationAccountMediaPurge")
			.contains("@ConditionalOnBean({ GenerationStore.class, ObjectStore.class })");
	}

	private static GenerationAccountMediaPurge newPurge(GenerationStore store, ObjectStore objectStore) {
		return new GenerationAccountMediaPurge(store, objectStore, CLOCK);
	}

	private static Path productionFile(String... parts) {
		Path relative = Path.of("src", "main", "java", "com", "aifashionvault");
		for (String part : parts) {
			relative = relative.resolve(part);
		}
		Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
		Path direct = cwd.resolve(relative);
		if (Files.isRegularFile(direct)) {
			return direct;
		}
		Path underBackend = cwd.resolve("backend").resolve(relative);
		if (Files.isRegularFile(underBackend)) {
			return underBackend;
		}
		throw new IllegalStateException("Cannot locate " + relative + " from " + cwd);
	}
}
