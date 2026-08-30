package com.aifashionvault.look;

import java.util.List;
import java.util.Optional;

import com.aifashionvault.shared.GenerationJobId;
import com.aifashionvault.shared.LookId;
import com.aifashionvault.shared.UserId;

public interface LookStore {

	Look insert(Look look);

	default Optional<Look> insertIfJobActive(Look look) {
		throw new UnsupportedOperationException("insertIfJobActive");
	}

	Optional<Look> findById(LookId id);

	Optional<Look> findByGenerationJobId(GenerationJobId generationJobId);

	/** Internal terminal replay lookup; unlike public reads, includes logically deleted rows. */
	default Optional<Look> findByGenerationJobIdForReplay(GenerationJobId generationJobId) {
		throw new UnsupportedOperationException("findByGenerationJobIdForReplay");
	}

	default Optional<Look> findByIdForUser(LookId id, UserId ownerId) {
		throw new UnsupportedOperationException("findByIdForUser");
	}

	default List<Look> listForUser(UserId ownerId) {
		throw new UnsupportedOperationException("listForUser");
	}

	default boolean logicallyDeleteForUser(LookId id, UserId ownerId) {
		throw new UnsupportedOperationException("logicallyDeleteForUser");
	}
}
