package com.aifashionvault.platform.generation;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aifashionvault.platform.queue.GenerationWorkLease;
import com.aifashionvault.platform.queue.GenerationWorkQueue;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationWorkWorkerTest {

	private static final GenerationWorkLease LEASE = new GenerationWorkLease(
		UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
		"rh-abc");

	private GenerationWorkQueue queue;
	private GenerationWorkProcessor processor;
	private GenerationWorkWorker worker;

	@BeforeEach
	void setUp() {
		queue = mock(GenerationWorkQueue.class);
		processor = mock(GenerationWorkProcessor.class);
		worker = new GenerationWorkWorker(queue, processor);
	}

	@Test
	void emptyReceiveDoesNotProcess() {
		when(queue.receive()).thenReturn(Optional.empty());

		worker.poll();

		verify(processor, never()).process(any());
	}

	@Test
	void receivedLeaseIsProcessedOnce() {
		when(queue.receive()).thenReturn(Optional.of(LEASE));

		worker.poll();

		verify(processor, times(1)).process(LEASE);
	}

	@Test
	void processExceptionDoesNotCrashWorker() {
		when(queue.receive()).thenReturn(Optional.of(LEASE));
		doThrow(new RuntimeException("process failed")).when(processor).process(LEASE);

		assertThatCode(() -> worker.poll()).doesNotThrowAnyException();
		verify(processor, times(1)).process(LEASE);
	}
}
