package com.aifashionvault.platform.web;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C12 capability gate: the Studio multi-item create path is off unless the
 * deployment turns it on, and it validates the request identity before anything
 * else. Both rejections happen before any collaborator is touched, which is why
 * this needs no database.
 */
class GenerationMultiItemGateTest {

	private final GenerationApiProperties properties = new GenerationApiProperties();

	private GenerationCreationService service() {
		return new GenerationCreationService(
			null, null, properties, Clock.systemUTC(), null, null, null, true);
	}

	@Test
	void multiItemCreateIsDisabledByDefaultAndTouchesNothing() {
		properties.setMultiItemEnabled(false);
		GenerationMultiRequest request = new GenerationMultiRequest(
			null, List.of(UUID.randomUUID()), "standard");

		assertThatThrownBy(() -> service().createMulti(new UserId(UUID.randomUUID()), "key", request, null, null))
			.isInstanceOfSatisfying(GenerationCreationService.Problem.class, problem -> {
				assertThat(problem.code()).isEqualTo(GenerationCreationService.MULTI_ITEM_DISABLED);
				assertThat(problem.status().value()).isEqualTo(503);
			});
	}

	@Test
	void missingIdempotencyKeyIsRejectedBeforeTheCapabilityGate() {
		properties.setMultiItemEnabled(true);
		GenerationMultiRequest request = new GenerationMultiRequest(
			null, List.of(UUID.randomUUID()), "standard");

		assertThatThrownBy(() -> service().createMulti(new UserId(UUID.randomUUID()), "  ", request, null, null))
			.isInstanceOfSatisfying(GenerationCreationService.Problem.class, problem -> {
				assertThat(problem.code()).isEqualTo(GenerationCreationService.MISSING_IDEMPOTENCY_KEY);
				assertThat(problem.status().value()).isEqualTo(400);
			});
	}
}
