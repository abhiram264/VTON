package com.aifashionvault.generation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifashionvault.shared.GenerationAttemptId;
import com.aifashionvault.shared.GenerationJobId;
import com.aifashionvault.shared.VaultItemId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RenderPlanTest {

	private static final VaultItemId TOP = new VaultItemId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
	private static final VaultItemId BOTTOM = new VaultItemId(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
	private static final VaultItemId SHOE = new VaultItemId(UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"));

	@Test
	void emptyProductsYieldOneEmptyStep() {
		RenderPlan plan = RenderPlan.oneProductPerStep(List.of());

		assertThat(plan.version()).isEqualTo(1);
		assertThat(plan.steps()).hasSize(1);
		assertThat(plan.steps().getFirst().stepIndex()).isEqualTo(1);
		assertThat(plan.steps().getFirst().items()).isEmpty();
	}

	@Test
	void oneProductPerStepOrdersByCategoryThenVaultId() {
		RenderPlan plan = RenderPlan.oneProductPerStep(List.of(
			new RenderPlan.PlannedProduct(SHOE, "shoes", "feet"),
			new RenderPlan.PlannedProduct(BOTTOM, "bottom", "legs"),
			new RenderPlan.PlannedProduct(TOP, "top", "torso")));

		assertThat(plan.steps()).extracting(RenderStep::stepIndex).containsExactly(1, 2, 3);
		assertThat(plan.steps()).extracting(step -> step.items().getFirst().vaultItemId())
			.containsExactly(BOTTOM, SHOE, TOP);
		assertThat(plan.steps()).extracting(step -> step.items().getFirst().itemOrdinal())
			.containsOnly(1);
		assertThat(plan.steps()).allMatch(step -> step.items().size() == 1);
	}

	@Test
	void currentFashnPlanIsOneProductPerStepAndBudgetIsNPlusTwo() {
		RenderPlan plan = RenderPlan.oneProductPerStep(List.of(
			new RenderPlan.PlannedProduct(TOP, "top", "torso"),
			new RenderPlan.PlannedProduct(BOTTOM, "bottom", "legs")));

		assertThat(plan.steps()).hasSize(2);
		assertThat(plan.steps()).allMatch(step -> step.items().size() == 1);
		assertThat(GenerationRetryBudget.forStepCount(plan.steps().size()).maxLogicalAttempts()).isEqualTo(4);
		assertThat(GenerationRetryBudget.forStepCount(1).maxLogicalAttempts()).isEqualTo(3);
	}

	@Test
	void planAndStepsAreImmutable() {
		List<RenderStep> mutable = new ArrayList<>();
		mutable.add(new RenderStep(UUID.randomUUID(), 1, null, List.of()));
		RenderPlan plan = new RenderPlan(1, mutable);
		mutable.add(new RenderStep(UUID.randomUUID(), 2, null, List.of()));

		assertThat(plan.steps()).hasSize(1);
		assertThatThrownBy(() -> plan.steps().add(new RenderStep(UUID.randomUUID(), 2, null, List.of())))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void workOutboxRequiresPendingClaimedOrSent() {
		GenerationJobId jobId = new GenerationJobId(UUID.randomUUID());
		Instant now = Instant.parse("2026-08-22T00:00:00Z");
		WorkOutbox pending = new WorkOutbox(
			UUID.randomUUID(), jobId, now, now, WorkOutbox.PENDING, 0, null, null, null, null);

		assertThat(pending.publishState()).isEqualTo("PENDING");
		assertThatThrownBy(() -> new WorkOutbox(
			UUID.randomUUID(), jobId, now, now, "PUBLISHED", 0, null, null, null, null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void callbackInboxRejectsBlankDedupeHash() {
		assertThatThrownBy(() -> new CallbackInbox(
			UUID.randomUUID(),
			" ",
			new GenerationAttemptId(UUID.randomUUID()),
			"provider-job",
			null,
			Instant.parse("2026-08-22T00:00:00Z")))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
