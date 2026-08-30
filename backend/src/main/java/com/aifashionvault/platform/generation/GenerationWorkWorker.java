package com.aifashionvault.platform.generation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import com.aifashionvault.platform.queue.GenerationWorkQueue;

public final class GenerationWorkWorker {

	private static final Logger log = LoggerFactory.getLogger(GenerationWorkWorker.class);

	private final GenerationWorkQueue queue;
	private final GenerationWorkProcessor processor;

	public GenerationWorkWorker(GenerationWorkQueue queue, GenerationWorkProcessor processor) {
		if (queue == null) {
			throw new IllegalArgumentException("queue is required");
		}
		if (processor == null) {
			throw new IllegalArgumentException("processor is required");
		}
		this.queue = queue;
		this.processor = processor;
	}

	@Scheduled(fixedDelay = 1000)
	void poll() {
		queue.receive().ifPresent(lease -> {
			try {
				processor.process(lease);
			}
			catch (RuntimeException ex) {
				log.info("generation jobId={}", lease.generationJobId());
			}
		});
	}
}
