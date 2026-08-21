package com.aifashionvault.platform.queue;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class InMemoryGenerationWorkClaimStore implements GenerationWorkClaimStore {

	private enum State {
		CLAIMED, COMPLETED
	}

	private final ConcurrentHashMap<UUID, State> states = new ConcurrentHashMap<>();

	@Override
	public boolean tryClaim(UUID generationJobId) {
		Objects.requireNonNull(generationJobId, "generationJobId");
		return states.putIfAbsent(generationJobId, State.CLAIMED) == null;
	}

	@Override
	public void complete(UUID generationJobId) {
		Objects.requireNonNull(generationJobId, "generationJobId");
		states.put(generationJobId, State.COMPLETED);
	}
}
