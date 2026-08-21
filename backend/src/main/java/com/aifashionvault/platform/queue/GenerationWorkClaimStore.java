package com.aifashionvault.platform.queue;

import java.util.UUID;

public interface GenerationWorkClaimStore {

	boolean tryClaim(UUID generationJobId);

	void complete(UUID generationJobId);
}
