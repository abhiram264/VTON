package com.aifashionvault.generation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.aifashionvault.shared.VaultItemId;

public record RenderPlan(int version, List<RenderStep> steps) {

	public record PlannedProduct(VaultItemId vaultItemId, String category, String slot) {

		public PlannedProduct {
			if (vaultItemId == null) {
				throw new IllegalArgumentException("vaultItemId is required");
			}
		}
	}

	public RenderPlan {
		if (version < 1) {
			throw new IllegalArgumentException("version must be >= 1");
		}
		if (steps == null || steps.isEmpty()) {
			throw new IllegalArgumentException("steps is required");
		}
		steps = List.copyOf(steps);
		Set<Integer> indexes = new HashSet<>();
		for (int i = 0; i < steps.size(); i++) {
			RenderStep step = steps.get(i);
			if (step.stepIndex() != i + 1) {
				throw new IllegalArgumentException("steps must be contiguous from 1");
			}
			if (!indexes.add(step.stepIndex())) {
				throw new IllegalArgumentException("step indexes must be unique");
			}
		}
	}

	public static RenderPlan oneProductPerStep(List<PlannedProduct> products) {
		if (products == null) {
			throw new IllegalArgumentException("products is required");
		}
		if (products.isEmpty()) {
			return new RenderPlan(1, List.of(new RenderStep(UUID.randomUUID(), 1, null, List.of())));
		}
		List<PlannedProduct> ordered = products.stream()
			.sorted(Comparator
				.comparing(PlannedProduct::category, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
				.thenComparing(product -> product.vaultItemId().value()))
			.toList();
		List<RenderStep> planned = new ArrayList<>(ordered.size());
		int stepIndex = 1;
		for (PlannedProduct product : ordered) {
			planned.add(new RenderStep(
				UUID.randomUUID(),
				stepIndex++,
				null,
				List.of(new RenderStep.Item(
					UUID.randomUUID(),
					1,
					product.vaultItemId(),
					product.category(),
					product.slot()))));
		}
		return new RenderPlan(1, List.copyOf(planned));
	}
}
