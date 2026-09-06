package com.aifashionvault.shared;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservationFacadeTest {

	private static final String CANARY_SIGNED_URL =
		"https://cdn.example.test/media/gen-item.mp4?X-Amz-Signature=canary-signature-0123";
	private static final String CANARY_BEARER_TOKEN = "canary-bearer-token-9f8e7d6c5b4a";
	private static final String CANARY_MEDIA_BASE64 = "data:image/png;base64,QUJDREVGRw==";
	private static final String CANARY_GENERATION_ID = "generation_0192c6f0-canary-id";

	@Test
	void spanNamesMatchFrozenVocabulary() {
		assertThat(ObservationFacade.SHARE_RESOLVE).isEqualTo("share.resolve");
		assertThat(ObservationFacade.VAULT_CREATE).isEqualTo("vault.create");
		assertThat(ObservationFacade.GENERATION_QUEUE_WAIT).isEqualTo("generation.queue_wait");
		assertThat(ObservationFacade.GENERATION_PROVIDER).isEqualTo("generation.provider");
		assertThat(ObservationFacade.GENERATION_POLL).isEqualTo("generation.poll");
		assertThat(ObservationFacade.GENERATION_QUALITY).isEqualTo("generation.quality");
		assertThat(ObservationFacade.GENERATION_STORE).isEqualTo("generation.store");
		assertThat(ObservationFacade.NOTIFICATION_SEND).isEqualTo("notification.send");
	}

	@Test
	void validPairsBecomeLowCardinalityKeyValuesOnAStartedObservation() {
		TestObservationRegistry registry = TestObservationRegistry.create();
		ObservationFacade facade = new ObservationFacade(registry);

		Observation observation =
			facade.start(ObservationFacade.SHARE_RESOLVE, "resolver=URL_SYNTAX", "outcome=RESOLVED");
		observation.stop();

		registry.assertThat()
			.hasSingleObservationThat()
			.hasNameEqualTo("share.resolve")
			.hasLowCardinalityKeyValue("resolver", "URL_SYNTAX")
			.hasLowCardinalityKeyValue("outcome", "RESOLVED");
	}

	@Test
	void startInvokesRegistryHandlerAndReturnsStartedObservation() {
		ObservationRegistry registry = ObservationRegistry.create();
		CapturingHandler handler = new CapturingHandler();
		registry.observationConfig().observationHandler(handler);
		ObservationFacade facade = new ObservationFacade(registry);

		Observation observation =
			facade.start(ObservationFacade.GENERATION_PROVIDER, "provider=FASHN", "outcome=ACCEPTED");

		assertThat(handler.started).isNotNull();
		assertThat(handler.started.getName()).isEqualTo("generation.provider");
		assertThat(handler.started.getLowCardinalityKeyValues())
			.containsExactlyInAnyOrder(KeyValue.of("provider", "FASHN"), KeyValue.of("outcome", "ACCEPTED"));
		assertThat(handler.stopped).isNull();

		observation.stop();

		assertThat(handler.stopped).isSameAs(handler.started);
	}

	@Test
	void absentRegistryDegradesToNoopWithoutThrowing() {
		ObservationFacade facade = new ObservationFacade(null);

		Observation observation = facade.start(ObservationFacade.VAULT_CREATE, "outcome=CREATED");

		assertThat(observation).isNotNull();
		assertThat(observation.isNoop()).isTrue();
		assertThatCode(() -> {
			observation.error(new IllegalStateException("vault-create-failure"));
			observation.stop();
		}).doesNotThrowAnyException();
	}

	@Test
	void noPairsStartsObservationWithoutKeyValues() {
		TestObservationRegistry registry = TestObservationRegistry.create();
		ObservationFacade facade = new ObservationFacade(registry);

		facade.start(ObservationFacade.GENERATION_QUEUE_WAIT).stop();

		registry.assertThat()
			.hasSingleObservationThat()
			.hasNameEqualTo("generation.queue_wait");
		registry.assertThat().hasHandledContextsThatSatisfy(contexts -> {
			assertThat(contexts).hasSize(1);
			assertThat(contexts.get(0).getLowCardinalityKeyValues()).isEmpty();
		});
	}

	@Test
	void multipleDelimitersKeepSuffixAfterFirstDelimiter() {
		TestObservationRegistry registry = TestObservationRegistry.create();
		ObservationFacade facade = new ObservationFacade(registry);

		facade.start(ObservationFacade.GENERATION_QUALITY, "policy_version=v2=beta").stop();

		registry.assertThat()
			.hasSingleObservationThat()
			.hasNameEqualTo("generation.quality")
			.hasLowCardinalityKeyValue("policy_version", "v2=beta");
	}

	@Test
	void nullNameRejectedBeforeAnyObservationStarts() {
		TestObservationRegistry registry = TestObservationRegistry.create();
		ObservationFacade facade = new ObservationFacade(registry);

		assertThatThrownBy(() -> facade.start(null, "outcome=CREATED"))
			.isInstanceOf(IllegalArgumentException.class);

		registry.assertThat().doesNotHaveAnyObservation();
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "   ", "\t" })
	void blankNameRejectedBeforeAnyObservationStarts(String name) {
		TestObservationRegistry registry = TestObservationRegistry.create();
		ObservationFacade facade = new ObservationFacade(registry);

		assertThatThrownBy(() -> facade.start(name, "outcome=CREATED"))
			.isInstanceOf(IllegalArgumentException.class);

		registry.assertThat().doesNotHaveAnyObservation();
	}

	@Test
	void nullKeyValuesArrayRejectedBeforeAnyObservationStarts() {
		TestObservationRegistry registry = TestObservationRegistry.create();
		ObservationFacade facade = new ObservationFacade(registry);

		assertThatThrownBy(() -> facade.start(ObservationFacade.GENERATION_POLL, (String[]) null))
			.isInstanceOf(IllegalArgumentException.class);

		registry.assertThat().doesNotHaveAnyObservation();
	}

	@ParameterizedTest
	@MethodSource("malformedKeyValues")
	void malformedPairsRejectedBeforeAnyObservationStarts(String[] keyValues) {
		TestObservationRegistry registry = TestObservationRegistry.create();
		ObservationFacade facade = new ObservationFacade(registry);

		assertThatThrownBy(() -> facade.start(ObservationFacade.NOTIFICATION_SEND, keyValues))
			.isInstanceOf(IllegalArgumentException.class);

		registry.assertThat().doesNotHaveAnyObservation();
	}

	@Test
	void facadeAddsNoAttributesBeyondProvidedPairsAndLeaksNoCanaries() {
		TestObservationRegistry registry = TestObservationRegistry.create();
		ObservationFacade facade = new ObservationFacade(registry);

		facade.start(ObservationFacade.GENERATION_STORE, "outcome=STORED").stop();

		registry.assertThat().hasHandledContextsThatSatisfy(contexts -> {
			assertThat(contexts).hasSize(1);
			Observation.Context context = contexts.get(0);
			assertThat(context.getLowCardinalityKeyValues()).containsExactly(KeyValue.of("outcome", "STORED"));
			assertThat(context.getHighCardinalityKeyValues()).isEmpty();
			String exported = context.getName() + context.getAllKeyValues().toString();
			assertThat(exported)
				.doesNotContain(CANARY_SIGNED_URL, CANARY_BEARER_TOKEN, CANARY_MEDIA_BASE64, CANARY_GENERATION_ID);
		});
	}

	@Test
	void registryFailureDoesNotPropagateForValidCalls() {
		ObservationRegistry registry = ObservationRegistry.create();
		registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
			@Override
			public boolean supportsContext(Observation.Context context) {
				return true;
			}

			@Override
			public void onStart(Observation.Context context) {
				throw new IllegalStateException("canary handler failure");
			}
		});
		ObservationFacade facade = new ObservationFacade(registry);

		Observation observation = facade.start(ObservationFacade.NOTIFICATION_SEND, "outcome=ACCEPTED");

		assertThat(observation).isNotNull();
		assertThatCode(observation::stop).doesNotThrowAnyException();
	}

	@Test
	void handlerFailuresOnErrorAndStopDoNotEscapeTheFacadeLifecycle() {
		ObservationRegistry registry = ObservationRegistry.create();
		registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
			@Override
			public boolean supportsContext(Observation.Context context) {
				return true;
			}

			@Override
			public void onEvent(Observation.Event event, Observation.Context context) {
				throw new IllegalStateException("canary onError handler failure");
			}

			@Override
			public void onStop(Observation.Context context) {
				throw new IllegalStateException("canary onStop handler failure");
			}
		});
		ObservationFacade facade = new ObservationFacade(registry);

		Observation observation = facade.start(ObservationFacade.NOTIFICATION_SEND);

		assertThat(observation).isNotNull();
		assertThatCode(() -> {
			facade.keyValue(observation, "outcome", "ACCEPTED");
			facade.error(observation, new IllegalStateException("canary send failure"));
			facade.stop(observation);
		}).doesNotThrowAnyException();
	}

	@Test
	void lifecycleOperationsOnStoppedOrNullObservationsDoNotThrow() {
		TestObservationRegistry registry = TestObservationRegistry.create();
		ObservationFacade facade = new ObservationFacade(registry);
		Observation observation = facade.start(ObservationFacade.VAULT_CREATE, "outcome=CREATED");
		facade.stop(observation);

		assertThatCode(() -> {
			facade.keyValue(observation, "stage", "late");
			facade.error(observation, new IllegalStateException("late failure"));
			facade.stop(observation);
			facade.keyValue(null, "outcome", "CREATED");
			facade.keyValue(observation, null, "CREATED");
			facade.keyValue(observation, "outcome", null);
			facade.error(null, new IllegalStateException("orphan failure"));
			facade.error(observation, null);
			facade.stop(null);
		}).doesNotThrowAnyException();

		registry.assertThat().hasSingleObservationThat().hasBeenStopped();
	}

	private static Stream<Arguments> malformedKeyValues() {
		return Stream.of(
			Arguments.of((Object) new String[] { "outcome" }),
			Arguments.of((Object) new String[] { "=CREATED" }),
			Arguments.of((Object) new String[] { "   =CREATED" }),
			Arguments.of((Object) new String[] { "outcome=" }),
			Arguments.of((Object) new String[] { "outcome=   " }),
			Arguments.of((Object) new String[] { null }),
			Arguments.of((Object) new String[] { "outcome=RESOLVED", null }),
			Arguments.of((Object) new String[] { "outcome=RESOLVED", "broken" }));
	}

	private static final class CapturingHandler implements ObservationHandler<Observation.Context> {

		private Observation.Context started;
		private Observation.Context stopped;

		@Override
		public boolean supportsContext(Observation.Context context) {
			return true;
		}

		@Override
		public void onStart(Observation.Context context) {
			this.started = context;
		}

		@Override
		public void onStop(Observation.Context context) {
			this.stopped = context;
		}
	}
}
