package com.aifashionvault.platform.importer;

import java.net.URI;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.aifashionvault.importer.ResolutionReason;
import com.aifashionvault.importer.ResolutionResult;
import com.aifashionvault.importer.ResolutionStatus;
import com.aifashionvault.importer.SourceReference;
import com.aifashionvault.importer.SourceResolver;
import com.aifashionvault.shared.ObservationFacade;
import com.aifashionvault.shared.StorageKey;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.common.KeyValue;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.tck.TestObservationRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceResolverMetricsTest {

	private static final SourceReference SOURCE =
		new SourceReference(URI.create("https://example.test/item"), null);
	private static final String CANARY_SIGNED_URL =
		"https://cdn.example.test/media.mp4?X-Amz-Signature=canary-signature-0123";
	private static final String CANARY_TITLE = "canary-title-9f8e7d";
	private static final String CANARY_TOKEN = "canary-bearer-token-0123";

	@ParameterizedTest
	@MethodSource("outcomes")
	void recordsExactlyOneOutcomeAndDurationWithFrozenTags(ResolutionResult result) {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		SourceResolver decorated = new SourceResolverMetrics(registry).decorate("GENERIC_OPEN_GRAPH", resolver(true, result));

		assertThat(decorated.supports(SOURCE)).isTrue();
		assertThat(decorated.resolve(SOURCE)).isSameAs(result);

		String reason = result.reason() == null ? "NONE" : result.reason().name();
		assertThat(registry.find(SourceResolverMetrics.OUTCOME)
			.tags("resolver", "GENERIC_OPEN_GRAPH", "status", result.status().name(), "reason", reason)
			.counter().count()).isEqualTo(1);
		assertThat(registry.find(SourceResolverMetrics.DURATION)
			.tags("resolver", "GENERIC_OPEN_GRAPH", "status", result.status().name())
			.timer().count()).isEqualTo(1);
		assertThat(registry.getMeters()).hasSize(2);
		assertFrozenTagKeys(registry.getMeters());
	}

	@Test
	void supportsFalseEmitsNoMetric() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		SourceResolver decorated = new SourceResolverMetrics(registry).decorate("GENERIC_URL", resolver(false, unsupported()));

		assertThat(decorated.supports(SOURCE)).isFalse();

		assertThat(registry.getMeters()).isEmpty();
	}

	@Test
	void exceptionRecordsBoundedStatusAndRethrowsSameInstance() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		IllegalStateException failure = new IllegalStateException("secret.example.test/object-key");
		SourceResolver delegate = new SourceResolver() {
			@Override
			public boolean supports(SourceReference source) {
				return true;
			}

			@Override
			public ResolutionResult resolve(SourceReference source) {
				throw failure;
			}
		};
		SourceResolver decorated = new SourceResolverMetrics(registry).decorate("SHARED_IMAGE", delegate);

		assertThatThrownBy(() -> decorated.resolve(SOURCE)).isSameAs(failure);
		assertThat(registry.find(SourceResolverMetrics.OUTCOME)
			.tags("resolver", "SHARED_IMAGE", "status", "EXCEPTION", "reason", "NONE")
			.counter().count()).isEqualTo(1);
		assertThat(registry.find(SourceResolverMetrics.DURATION)
			.tags("resolver", "SHARED_IMAGE", "status", "EXCEPTION")
			.timer().count()).isEqualTo(1);
		assertThat(registry.getMeters().toString()).doesNotContain("secret.example.test", "object-key");
		assertFrozenTagKeys(registry.getMeters());
	}

	@Test
	void absentRegistryIsNoOpAndInvalidResolverIdsAreRejected() {
		SourceResolver delegate = resolver(true, unsupported());
		SourceResolver decorated = new SourceResolverMetrics(null).decorate("GENERIC_URL", delegate);

		assertThat(decorated).isSameAs(delegate);
		assertThatThrownBy(() -> new SourceResolverMetrics(new SimpleMeterRegistry())
			.decorate("resolver-from-class-name", delegate))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@ParameterizedTest
	@MethodSource("spanOutcomes")
	void shareResolveSpanCarriesResolverAndFrozenOutcomeOnly(ResolutionResult result, String expectedOutcome) {
		SimpleMeterRegistry meters = new SimpleMeterRegistry();
		TestObservationRegistry spans = TestObservationRegistry.create();
		SourceResolver decorated = new SourceResolverMetrics(meters, new ObservationFacade(spans))
			.decorate("GENERIC_OPEN_GRAPH", resolver(true, result));

		assertThat(decorated.resolve(SOURCE)).isSameAs(result);

		spans.assertThat().hasSingleObservationThat()
			.hasNameEqualTo(ObservationFacade.SHARE_RESOLVE)
			.hasLowCardinalityKeyValue("resolver", "GENERIC_OPEN_GRAPH")
			.hasLowCardinalityKeyValue("outcome", expectedOutcome)
			.hasBeenStopped();
		spans.assertThat().hasHandledContextsThatSatisfy(contexts -> {
			assertThat(contexts).hasSize(1);
			assertThat(contexts.get(0).getLowCardinalityKeyValues()).containsExactlyInAnyOrder(
				KeyValue.of("resolver", "GENERIC_OPEN_GRAPH"), KeyValue.of("outcome", expectedOutcome));
			assertThat(contexts.get(0).getName() + contexts.get(0).getAllKeyValues())
				.doesNotContain("canary-signature", CANARY_TITLE, CANARY_TOKEN);
		});
		assertThat(meters.find(SourceResolverMetrics.OUTCOME).counter().count()).isEqualTo(1);
		assertThat(meters.find(SourceResolverMetrics.DURATION).timer().count()).isEqualTo(1);
	}

	@Test
	void exceptionEmitsFailedSpanMarksErrorAndRethrowsSameInstance() {
		TestObservationRegistry spans = TestObservationRegistry.create();
		IllegalStateException failure = new IllegalStateException("secret.example.test " + CANARY_TOKEN);
		SourceResolver delegate = new SourceResolver() {
			@Override
			public boolean supports(SourceReference source) {
				return true;
			}

			@Override
			public ResolutionResult resolve(SourceReference source) {
				throw failure;
			}
		};
		SourceResolver decorated = new SourceResolverMetrics(new SimpleMeterRegistry(), new ObservationFacade(spans))
			.decorate("SHARED_IMAGE", delegate);

		assertThatThrownBy(() -> decorated.resolve(SOURCE)).isSameAs(failure);

		spans.assertThat().hasSingleObservationThat()
			.hasNameEqualTo(ObservationFacade.SHARE_RESOLVE)
			.hasLowCardinalityKeyValue("resolver", "SHARED_IMAGE")
			.hasLowCardinalityKeyValue("outcome", "FAILED")
			.hasBeenStopped();
		spans.assertThat().hasHandledContextsThatSatisfy(contexts -> {
			assertThat(contexts).hasSize(1);
			assertThat(contexts.get(0).getError()).isSameAs(failure);
			assertThat(contexts.get(0).getName() + contexts.get(0).getAllKeyValues())
				.doesNotContain("secret.example.test", CANARY_TOKEN);
		});
	}

	@Test
	void onStartHandlerFailureDoesNotEscapeAndOriginalResultIsUnchanged() {
		SimpleMeterRegistry meters = new SimpleMeterRegistry();
		TestObservationRegistry spans = TestObservationRegistry.create();
		spans.observationConfig().observationHandler(failingHandler(FailingStage.ON_START));
		ResolutionResult result = new ResolutionResult(ResolutionStatus.RESOLVED, new StorageKey("safe/test.jpg"),
			null, null, null, null);
		SourceResolver decorated = new SourceResolverMetrics(meters, new ObservationFacade(spans))
			.decorate("GENERIC_OPEN_GRAPH", resolver(true, result));

		assertThat(decorated.resolve(SOURCE)).isSameAs(result);
		assertThat(meters.find(SourceResolverMetrics.OUTCOME).counter().count()).isEqualTo(1);
	}

	@Test
	void onErrorHandlerFailureDoesNotEscapeAndOriginalErrorStillPropagates() {
		SimpleMeterRegistry meters = new SimpleMeterRegistry();
		TestObservationRegistry spans = TestObservationRegistry.create();
		spans.observationConfig().observationHandler(failingHandler(FailingStage.ON_ERROR));
		IllegalStateException failure = new IllegalStateException("canary resolver failure");
		SourceResolver decorated = new SourceResolverMetrics(meters, new ObservationFacade(spans))
			.decorate("SHARED_IMAGE", resolverThatThrows(failure));

		assertThatThrownBy(() -> decorated.resolve(SOURCE)).isSameAs(failure);
		assertThat(meters.find(SourceResolverMetrics.OUTCOME).counter().count()).isEqualTo(1);
	}

	@Test
	void onStopHandlerFailureDoesNotEscapeAndOriginalResultIsUnchanged() {
		SimpleMeterRegistry meters = new SimpleMeterRegistry();
		TestObservationRegistry spans = TestObservationRegistry.create();
		spans.observationConfig().observationHandler(failingHandler(FailingStage.ON_STOP));
		ResolutionResult result = new ResolutionResult(ResolutionStatus.RESOLVED, new StorageKey("safe/test.jpg"),
			null, null, null, null);
		SourceResolver decorated = new SourceResolverMetrics(meters, new ObservationFacade(spans))
			.decorate("GENERIC_OPEN_GRAPH", resolver(true, result));

		assertThat(decorated.resolve(SOURCE)).isSameAs(result);
		assertThat(meters.find(SourceResolverMetrics.OUTCOME).counter().count()).isEqualTo(1);
	}

	@Test
	void canaryResultFieldsNeverReachSpanKeyValues() {
		TestObservationRegistry spans = TestObservationRegistry.create();
		ResolutionResult result = new ResolutionResult(ResolutionStatus.RESOLVED, new StorageKey("vault/item.jpg"),
			URI.create(CANARY_SIGNED_URL), CANARY_TITLE, null, null);
		SourceResolver decorated = new SourceResolverMetrics(new SimpleMeterRegistry(), new ObservationFacade(spans))
			.decorate("SHARED_IMAGE", resolver(true, result));

		decorated.resolve(SOURCE);

		spans.assertThat().hasHandledContextsThatSatisfy(contexts -> {
			assertThat(contexts).hasSize(1);
			String exported = contexts.get(0).getName() + contexts.get(0).getAllKeyValues();
			assertThat(exported).doesNotContain("canary-signature", CANARY_TITLE, "media.mp4", "base64");
		});
	}

	@Test
	void absentFacadeStillRecordsMetersWithoutSpansOrExceptions() {
		SimpleMeterRegistry meters = new SimpleMeterRegistry();
		SourceResolver legacy = new SourceResolverMetrics(meters).decorate("GENERIC_URL", resolver(true, unsupported()));
		SourceResolver nullFacade = new SourceResolverMetrics(meters, null).decorate("GENERIC_URL", resolver(true, unsupported()));

		assertThat(legacy.resolve(SOURCE)).isNotNull();
		assertThat(nullFacade.resolve(SOURCE)).isNotNull();

		assertThat(meters.find(SourceResolverMetrics.OUTCOME).counter().count()).isEqualTo(2);
		assertThat(meters.find(SourceResolverMetrics.DURATION).timer().count()).isEqualTo(2);
	}

	private static Stream<Arguments> spanOutcomes() {
		return Stream.of(
			Arguments.of(new ResolutionResult(ResolutionStatus.RESOLVED, new StorageKey("safe/test.jpg"),
				URI.create(CANARY_SIGNED_URL), CANARY_TITLE, null, null), "RESOLVED"),
			Arguments.of(new ResolutionResult(ResolutionStatus.NEEDS_IMAGE, null, null, null, null,
				ResolutionReason.URL_RESOLUTION_FAILED), "FAILED"),
			Arguments.of(new ResolutionResult(ResolutionStatus.RETRYABLE_FAILURE, null, null, null, null,
				ResolutionReason.DEPENDENCY_UNAVAILABLE), "FAILED"),
			Arguments.of(new ResolutionResult(ResolutionStatus.TERMINAL_FAILURE, null, null, null, null,
				ResolutionReason.INVALID_SOURCE), "FAILED"),
			Arguments.of(unsupported(), "UNSUPPORTED"));
	}

	private static Stream<ResolutionResult> outcomes() {
		return Stream.of(
			new ResolutionResult(ResolutionStatus.RESOLVED, new StorageKey("safe/test.jpg"), null, null, null, null),
			new ResolutionResult(ResolutionStatus.NEEDS_IMAGE, null, null, null, null,
				ResolutionReason.URL_RESOLUTION_FAILED),
			new ResolutionResult(ResolutionStatus.RETRYABLE_FAILURE, null, null, null, null,
				ResolutionReason.DEPENDENCY_UNAVAILABLE),
			new ResolutionResult(ResolutionStatus.TERMINAL_FAILURE, null, null, null, null,
				ResolutionReason.INVALID_SOURCE),
			unsupported());
	}

	private static SourceResolver resolver(boolean supported, ResolutionResult result) {
		return new SourceResolver() {
			@Override
			public boolean supports(SourceReference source) {
				return supported;
			}

			@Override
			public ResolutionResult resolve(SourceReference source) {
				return result;
			}
		};
	}

	private static SourceResolver resolverThatThrows(RuntimeException failure) {
		return new SourceResolver() {
			@Override
			public boolean supports(SourceReference source) {
				return true;
			}

			@Override
			public ResolutionResult resolve(SourceReference source) {
				throw failure;
			}
		};
	}

	private static ObservationHandler<Observation.Context> failingHandler(FailingStage stage) {
		return new ObservationHandler<Observation.Context>() {

			@Override
			public boolean supportsContext(Observation.Context context) {
				return true;
			}

			@Override
			public void onStart(Observation.Context context) {
				if (stage == FailingStage.ON_START) {
					throw new IllegalStateException("canary onStart handler failure");
				}
			}

			@Override
			public void onEvent(Observation.Event event, Observation.Context context) {
				if (stage == FailingStage.ON_ERROR) {
					throw new IllegalStateException("canary onError handler failure");
				}
			}

			@Override
			public void onStop(Observation.Context context) {
				if (stage == FailingStage.ON_STOP) {
					throw new IllegalStateException("canary onStop handler failure");
				}
			}
		};
	}

	private enum FailingStage {
		ON_START,
		ON_ERROR,
		ON_STOP
	}

	private static ResolutionResult unsupported() {
		return new ResolutionResult(ResolutionStatus.UNSUPPORTED, null, null, null, null, null);
	}

	private static void assertFrozenTagKeys(List<Meter> meters) {
		for (Meter meter : meters) {
			List<String> keys = meter.getId().getTags().stream().map(Tag::getKey).toList();
			if (SourceResolverMetrics.OUTCOME.equals(meter.getId().getName())) {
				assertThat(keys).containsExactly("reason", "resolver", "status");
			} else {
				assertThat(keys).containsExactly("resolver", "status");
			}
		}
	}
}
