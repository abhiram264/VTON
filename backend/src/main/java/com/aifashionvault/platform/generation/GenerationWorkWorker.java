package com.aifashionvault.platform.generation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import java.time.Duration;
import org.springframework.scheduling.TaskScheduler;

import com.aifashionvault.platform.logging.LoggingPolicy;
import com.aifashionvault.platform.queue.GenerationWorkLease;
import com.aifashionvault.platform.queue.GenerationWorkQueue;

public final class GenerationWorkWorker {

	private static final Logger log = LoggerFactory.getLogger(GenerationWorkWorker.class);
	private static final Duration POLL_DELAY = Duration.ofMillis(1000);

	private final GenerationWorkQueue queue;
	private final GenerationWorkProcessor processor;

	public GenerationWorkWorker(GenerationWorkQueue queue, GenerationWorkProcessor processor) {
		this(queue, processor, null);
	}

	public GenerationWorkWorker(GenerationWorkQueue queue, GenerationWorkProcessor processor, TaskScheduler scheduler) {
		if (queue == null) {
			throw new IllegalArgumentException("queue is required");
		}
		if (processor == null) {
			throw new IllegalArgumentException("processor is required");
		}
		this.queue = queue;
		this.processor = processor;
		if (scheduler != null) {
			scheduler.scheduleWithFixedDelay(this::poll, POLL_DELAY);
		}
	}

	void poll() {
		queue.receive().ifPresent(this::processWithScope);
	}

	private void processWithScope(GenerationWorkLease lease) {
		try {
			MDC.put(LoggingPolicy.KEY_GENERATION_ID, lease.generationJobId().toString());
			MDC.remove(LoggingPolicy.KEY_PROVIDER);
			MDC.remove(LoggingPolicy.KEY_STATUS);
			MDC.remove(LoggingPolicy.KEY_LATENCY_MS);
			MDC.remove(LoggingPolicy.KEY_ERROR_CODE);
			try {
				processor.process(lease);
			}
			catch (RuntimeException ex) {
				MDC.put(LoggingPolicy.KEY_GENERATION_ID, lease.generationJobId().toString());
				MDC.remove(LoggingPolicy.KEY_PROVIDER);
				MDC.remove(LoggingPolicy.KEY_STATUS);
				MDC.remove(LoggingPolicy.KEY_LATENCY_MS);
				MDC.remove(LoggingPolicy.KEY_ERROR_CODE);
				log.info("generation jobId={}", lease.generationJobId());
			}
		}
		finally {
			MDC.remove(LoggingPolicy.KEY_GENERATION_ID);
			MDC.remove(LoggingPolicy.KEY_PROVIDER);
			MDC.remove(LoggingPolicy.KEY_STATUS);
			MDC.remove(LoggingPolicy.KEY_LATENCY_MS);
			MDC.remove(LoggingPolicy.KEY_ERROR_CODE);
		}
	}
}
