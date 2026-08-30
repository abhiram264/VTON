package com.aifashionvault.generation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.aifashionvault.shared.GenerationAttemptId;

public interface CallbackInboxStore {

	void register(GenerationAttemptId attemptId, String tokenHash, Instant receivedAt);

	Optional<CallbackInbox> findByDedupeHash(String dedupeHash);

	List<CallbackInbox> findByAttempt(GenerationAttemptId attemptId);

	boolean accept(String tokenHash, String candidateProviderJobId, String safeTerminalHint, Instant now);
}
