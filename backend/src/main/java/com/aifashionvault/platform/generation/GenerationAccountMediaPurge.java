package com.aifashionvault.platform.generation;

import java.time.Clock;
import java.time.Instant;

import com.aifashionvault.generation.GenerationStore;
import com.aifashionvault.platform.objectstore.ObjectStore;
import com.aifashionvault.shared.AccountMediaPurge;
import com.aifashionvault.shared.UserId;

public class GenerationAccountMediaPurge implements AccountMediaPurge {

	private final GenerationStore generationStore;
	private final ObjectStore objectStore;
	private final Clock clock;

	public GenerationAccountMediaPurge(
		GenerationStore generationStore,
		ObjectStore objectStore,
		Clock clock
	) {
		if (generationStore == null) {
			throw new IllegalArgumentException("generationStore is required");
		}
		if (objectStore == null) {
			throw new IllegalArgumentException("objectStore is required");
		}
		if (clock == null) {
			throw new IllegalArgumentException("clock is required");
		}
		this.generationStore = generationStore;
		this.objectStore = objectStore;
		this.clock = clock;
	}

	@Override
	public void purgeForUser(UserId userId) {
		if (userId == null) {
			throw new IllegalArgumentException("userId is required");
		}
		Instant now = clock.instant();
		generationStore.markAllSubjectDeleted(userId, now);
		objectStore.purgeUserObjects(userId);
		generationStore.deleteUserLinkedGenerationData(userId);
	}
}
