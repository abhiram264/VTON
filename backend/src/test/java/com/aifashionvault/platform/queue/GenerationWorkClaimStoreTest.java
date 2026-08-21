package com.aifashionvault.platform.queue;

import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GenerationWorkClaimStoreTest {

	private static final UUID JOB_A = UUID.fromString("00000000-0000-4000-8000-00000000000a");
	private static final UUID JOB_B = UUID.fromString("00000000-0000-4000-8000-00000000000b");

	@Test
	void duplicateClaimIsSuppressedAfterFirstHandler() {
		InMemoryGenerationWorkClaimStore store = new InMemoryGenerationWorkClaimStore();
		AtomicInteger sideEffects = new AtomicInteger();

		runOnce(store, JOB_A, sideEffects);
		runOnce(store, JOB_A, sideEffects);

		assertThat(sideEffects).hasValue(1);
		assertThat(store.tryClaim(JOB_A)).isFalse();
	}

	@Test
	void replayAfterCompletionDoesNotResurrectClaim() {
		InMemoryGenerationWorkClaimStore store = new InMemoryGenerationWorkClaimStore();
		assertThat(store.tryClaim(JOB_A)).isTrue();
		store.complete(JOB_A);

		assertThat(store.tryClaim(JOB_A)).isFalse();
		store.complete(JOB_A);
		assertThat(store.tryClaim(JOB_A)).isFalse();
	}

	@Test
	void reorderedTerminalStateRejectsOlderDeliveryWithoutCorruptingOtherJobs() {
		InMemoryGenerationWorkClaimStore store = new InMemoryGenerationWorkClaimStore();
		store.complete(JOB_A);

		assertThat(store.tryClaim(JOB_A)).isFalse();
		assertThat(store.tryClaim(JOB_B)).isTrue();
		store.complete(JOB_B);

		assertThat(store.tryClaim(JOB_A)).isFalse();
		assertThat(store.tryClaim(JOB_B)).isFalse();
	}

	@Test
	void reverseArrivalOrderClaimsIndependentJobs() {
		InMemoryGenerationWorkClaimStore store = new InMemoryGenerationWorkClaimStore();
		AtomicInteger sideEffects = new AtomicInteger();

		runOnce(store, JOB_B, sideEffects);
		runOnce(store, JOB_A, sideEffects);

		assertThat(sideEffects).hasValue(2);
		assertThat(store.tryClaim(JOB_A)).isFalse();
		assertThat(store.tryClaim(JOB_B)).isFalse();
	}

	@Test
	void concurrentDuplicateDeliveriesCreateOneHandlerSideEffect() throws Exception {
		for (int i = 0; i < 100; i++) {
			InMemoryGenerationWorkClaimStore store = new InMemoryGenerationWorkClaimStore();
			UUID jobId = UUID.randomUUID();
			AtomicInteger sideEffects = new AtomicInteger();
			CyclicBarrier barrier = new CyclicBarrier(2);
			try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
				Future<?> first = executor.submit(() -> claimAtBarrier(store, jobId, barrier, sideEffects));
				Future<?> second = executor.submit(() -> claimAtBarrier(store, jobId, barrier, sideEffects));
				first.get();
				second.get();
			}
			assertThat(sideEffects).hasValue(1);
			assertThat(store.tryClaim(jobId)).isFalse();
		}
	}

	private static Void claimAtBarrier(
		InMemoryGenerationWorkClaimStore store,
		UUID jobId,
		CyclicBarrier barrier,
		AtomicInteger sideEffects
	) throws Exception {
		barrier.await();
		runOnce(store, jobId, sideEffects);
		return null;
	}

	private static void runOnce(GenerationWorkClaimStore store, UUID jobId, AtomicInteger sideEffects) {
		if (store.tryClaim(jobId)) {
			sideEffects.incrementAndGet();
			store.complete(jobId);
		}
	}
}
