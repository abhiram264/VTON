package com.aifashionvault.platform.queue;

import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public final class GenerationWorkOutboxRelay {

	private static final Logger log = LoggerFactory.getLogger(GenerationWorkOutboxRelay.class);
	static final String ERROR_SQS_PUBLISH = "SQS_PUBLISH";

	private final WorkOutboxStore store;
	private final GenerationWorkQueue queue;

	public GenerationWorkOutboxRelay(WorkOutboxStore store, GenerationWorkQueue queue) {
		this.store = Objects.requireNonNull(store, "store");
		this.queue = Objects.requireNonNull(queue, "queue");
	}

	@Scheduled(fixedDelay = 1000)
	void scheduledPublish() {
		publishEligible();
	}

	void publishEligible() {
		while (true) {
			Optional<WorkOutboxStore.Claim> claimed = store.claimNext();
			if (claimed.isEmpty()) {
				return;
			}
			publish(claimed.get());
		}
	}

	private void publish(WorkOutboxStore.Claim claim) {
		try {
			queue.send(claim.generationJobId());
			store.markSent(claim.outboxId());
		}
		catch (RuntimeException ex) {
			store.markPublishFailed(claim.outboxId(), ERROR_SQS_PUBLISH);
			log.info("generation jobId={} code={}", claim.generationJobId(), ERROR_SQS_PUBLISH);
		}
	}
}
