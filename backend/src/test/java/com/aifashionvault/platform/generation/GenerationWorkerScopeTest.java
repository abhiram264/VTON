package com.aifashionvault.platform.generation;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.aifashionvault.admission.GlobalCostCircuitBreaker;
import com.aifashionvault.generation.GenerationStore;
import com.aifashionvault.generation.ProviderHealthGate;
import com.aifashionvault.generation.QualityGate;
import com.aifashionvault.generation.vton.VtonProvider;
import com.aifashionvault.look.LookStore;
import com.aifashionvault.notification.NotificationStore;
import com.aifashionvault.platform.logging.LoggingPolicy;
import com.aifashionvault.platform.objectstore.GenerationMediaStore;
import com.aifashionvault.platform.objectstore.ObjectStore;
import com.aifashionvault.platform.queue.GenerationWorkLease;
import com.aifashionvault.platform.queue.GenerationWorkQueue;
import com.aifashionvault.shared.GenerationJobId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GenerationWorkerScopeTest {

	private static final UUID JOB_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final GenerationWorkLease LEASE = new GenerationWorkLease(JOB_UUID, "receipt-1");

	private GenerationWorkQueue queue;
	private GenerationWorkProcessor processor;
	private GenerationWorkWorker worker;

	@BeforeEach
	void setUp() {
		MDC.clear();
		queue = mock(GenerationWorkQueue.class);
		processor = mock(GenerationWorkProcessor.class);
		worker = new GenerationWorkWorker(queue, processor);
	}

	@AfterEach
	void tearDown() {
		MDC.clear();
	}

	@Test
	void workerEstablishesGenerationIdDuringProcessorInvocationAndCleansUpInFinally() {
		when(queue.receive()).thenReturn(Optional.of(LEASE));

		AtomicReference<String> observedGenerationId = new AtomicReference<>();
		doAnswer(invocation -> {
			observedGenerationId.set(MDC.get(LoggingPolicy.KEY_GENERATION_ID));
			return null;
		}).when(processor).process(LEASE);

		worker.poll();

		assertThat(observedGenerationId.get()).isEqualTo(JOB_UUID.toString());
		assertThat(MDC.get(LoggingPolicy.KEY_GENERATION_ID)).isNull();
		assertThat(MDC.get(LoggingPolicy.KEY_PROVIDER)).isNull();
		assertThat(MDC.get(LoggingPolicy.KEY_STATUS)).isNull();
		assertThat(MDC.get(LoggingPolicy.KEY_LATENCY_MS)).isNull();
		assertThat(MDC.get(LoggingPolicy.KEY_ERROR_CODE)).isNull();
	}

	@Test
	void workerCleansUpMdcOnRuntimeExceptionAndPreservesContextInCatch() {
		when(queue.receive()).thenReturn(Optional.of(LEASE));

		MDC.put(LoggingPolicy.KEY_GENERATION_ID, "stale-gen-id");
		MDC.put(LoggingPolicy.KEY_PROVIDER, "stale-provider");
		MDC.put(LoggingPolicy.KEY_STATUS, "stale-status");
		MDC.put(LoggingPolicy.KEY_LATENCY_MS, "123");
		MDC.put(LoggingPolicy.KEY_ERROR_CODE, "stale-error");

		Logger workerLogger = (Logger) LoggerFactory.getLogger(GenerationWorkWorker.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		workerLogger.addAppender(appender);

		try {
			doAnswer(invocation -> {
				MDC.remove(LoggingPolicy.KEY_GENERATION_ID);
				MDC.remove(LoggingPolicy.KEY_PROVIDER);
				MDC.remove(LoggingPolicy.KEY_STATUS);
				MDC.remove(LoggingPolicy.KEY_LATENCY_MS);
				MDC.remove(LoggingPolicy.KEY_ERROR_CODE);
				throw new RuntimeException("processing failed");
			}).when(processor).process(LEASE);
			assertThatCode(() -> worker.poll()).doesNotThrowAnyException();

			assertThat(appender.list).hasSize(1);
			ILoggingEvent logEvent = appender.list.getFirst();
			assertThat(logEvent.getMDCPropertyMap())
				.containsEntry(LoggingPolicy.KEY_GENERATION_ID, JOB_UUID.toString())
				.doesNotContainKey(LoggingPolicy.KEY_PROVIDER)
				.doesNotContainKey(LoggingPolicy.KEY_STATUS)
				.doesNotContainKey(LoggingPolicy.KEY_LATENCY_MS)
				.doesNotContainKey(LoggingPolicy.KEY_ERROR_CODE);

			// After worker poll completes, MDC must be clean
			assertThat(MDC.get(LoggingPolicy.KEY_GENERATION_ID)).isNull();
			assertThat(MDC.get(LoggingPolicy.KEY_PROVIDER)).isNull();
			assertThat(MDC.get(LoggingPolicy.KEY_STATUS)).isNull();
			assertThat(MDC.get(LoggingPolicy.KEY_LATENCY_MS)).isNull();
			assertThat(MDC.get(LoggingPolicy.KEY_ERROR_CODE)).isNull();
		}
		finally {
			workerLogger.detachAppender(appender);
		}
	}

	@Test
	void workerRemovesWorkerOwnedMdcValuesAndDoesNotRestoreStaleWorkerValues() {
		when(queue.receive()).thenReturn(Optional.of(LEASE));

		MDC.put(LoggingPolicy.KEY_REQUEST_ID, "req-123");
		MDC.put(LoggingPolicy.KEY_USER_ID, "user-456");
		MDC.put(LoggingPolicy.KEY_PROVIDER, "pre-existing-provider");
		MDC.put(LoggingPolicy.KEY_STATUS, "pre-existing-status");
		MDC.put(LoggingPolicy.KEY_LATENCY_MS, "999");
		MDC.put(LoggingPolicy.KEY_ERROR_CODE, "pre-existing-error");

		doAnswer(invocation -> {
			assertThat(MDC.get(LoggingPolicy.KEY_REQUEST_ID)).isEqualTo("req-123");
			assertThat(MDC.get(LoggingPolicy.KEY_USER_ID)).isEqualTo("user-456");
			assertThat(MDC.get(LoggingPolicy.KEY_GENERATION_ID)).isEqualTo(JOB_UUID.toString());
			assertThat(MDC.get(LoggingPolicy.KEY_PROVIDER)).isNull();
			assertThat(MDC.get(LoggingPolicy.KEY_STATUS)).isNull();
			assertThat(MDC.get(LoggingPolicy.KEY_LATENCY_MS)).isNull();
			assertThat(MDC.get(LoggingPolicy.KEY_ERROR_CODE)).isNull();
			return null;
		}).when(processor).process(LEASE);

		worker.poll();

		assertThat(MDC.get(LoggingPolicy.KEY_REQUEST_ID)).isEqualTo("req-123");
		assertThat(MDC.get(LoggingPolicy.KEY_USER_ID)).isEqualTo("user-456");
		assertThat(MDC.get(LoggingPolicy.KEY_GENERATION_ID)).isNull();
		assertThat(MDC.get(LoggingPolicy.KEY_PROVIDER)).isNull();
		assertThat(MDC.get(LoggingPolicy.KEY_STATUS)).isNull();
		assertThat(MDC.get(LoggingPolicy.KEY_LATENCY_MS)).isNull();
		assertThat(MDC.get(LoggingPolicy.KEY_ERROR_CODE)).isNull();
	}

	@Test
	void redeliveryStartsCleanWithoutStaleFieldsFromPriorDelivery() {
		when(queue.receive())
			.thenReturn(Optional.of(LEASE))
			.thenReturn(Optional.of(new GenerationWorkLease(JOB_UUID, "receipt-2")));

		AtomicReference<String> firstProvider = new AtomicReference<>();
		AtomicReference<String> secondProvider = new AtomicReference<>();

		doAnswer(invocation -> {
			// Delivery 1 enriches provider
			MDC.put(LoggingPolicy.KEY_PROVIDER, "fashn");
			MDC.put(LoggingPolicy.KEY_STATUS, "FAILED");
			MDC.put(LoggingPolicy.KEY_LATENCY_MS, "1250");
			MDC.put(LoggingPolicy.KEY_ERROR_CODE, "PROVIDER_TIMEOUT");
			firstProvider.set(MDC.get(LoggingPolicy.KEY_PROVIDER));
			return null;
		}).doAnswer(invocation -> {
			// Delivery 2 checks that stale fields are absent before enrichment
			secondProvider.set(MDC.get(LoggingPolicy.KEY_PROVIDER));
			assertThat(MDC.get(LoggingPolicy.KEY_STATUS)).isNull();
			assertThat(MDC.get(LoggingPolicy.KEY_LATENCY_MS)).isNull();
			assertThat(MDC.get(LoggingPolicy.KEY_ERROR_CODE)).isNull();
			return null;
		}).when(processor).process(any());

		worker.poll();
		assertThat(firstProvider.get()).isEqualTo("fashn");
		assertThat(MDC.get(LoggingPolicy.KEY_PROVIDER)).isNull();

		worker.poll();
		assertThat(secondProvider.get()).isNull();
		assertThat(MDC.get(LoggingPolicy.KEY_PROVIDER)).isNull();
	}

	@Test
	void processorEnforcesMdcScopeAndCleansUpEvenWhenDirectlyInvoked() {
		GenerationStore store = mock(GenerationStore.class);
		when(store.findJob(any(GenerationJobId.class))).thenAnswer(inv -> {
			// While inside processor.process(), generation_id must be present in MDC
			assertThat(MDC.get(LoggingPolicy.KEY_GENERATION_ID)).isEqualTo(JOB_UUID.toString());
			return Optional.empty(); // simulate missing job to exit early
		});

		GenerationWorkQueue workQueue = mock(GenerationWorkQueue.class);
		Clock clock = Clock.systemUTC();

		GenerationWorkProcessor realProcessor = new GenerationWorkProcessor(
			store,
			mock(LookStore.class),
			mock(NotificationStore.class),
			workQueue,
			mock(VtonProvider.class),
			mock(AuthorizedGenerationMediaResolver.class),
			mock(PrivacyApprovedInputTransfer.class),
			mock(QualityGate.class),
			mock(GenerationMediaStore.class),
			mock(ObjectStore.class),
			clock,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			mock(GlobalCostCircuitBreaker.class),
			mock(ProviderHealthGate.class)
		);

		realProcessor.process(LEASE);

		// After process completes, MDC must be cleaned up
		assertThat(MDC.get(LoggingPolicy.KEY_GENERATION_ID)).isNull();
		assertThat(MDC.get(LoggingPolicy.KEY_PROVIDER)).isNull();
		assertThat(MDC.get(LoggingPolicy.KEY_STATUS)).isNull();
		assertThat(MDC.get(LoggingPolicy.KEY_LATENCY_MS)).isNull();
		assertThat(MDC.get(LoggingPolicy.KEY_ERROR_CODE)).isNull();
	}
}
