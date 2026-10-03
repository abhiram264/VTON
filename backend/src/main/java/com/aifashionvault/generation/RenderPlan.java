package com.aifashionvault.generation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.aifashionvault.shared.VaultItemId;

public record RenderPlan(int version, List<RenderStep> steps) {

	/**
	 * A product exactly as the Studio submitted it, carrying the revisions the
	 * plan was built from so a plan replays the submitted state.
	 */
	public record PlannedProduct(
		VaultItemId vaultItemId,
		String category,
		String slot,
		Integer assetRevision,
		Integer profileRevision) {

		public PlannedProduct {
			if (vaultItemId == null) {
				throw new IllegalArgumentException("vaultItemId is required");
			}
		}

		public PlannedProduct(VaultItemId vaultItemId, String category, String slot) {
			this(vaultItemId, category, slot, null, null);
		}

		/**
		 * The submitted revisions in the durable {@code key=value;} form the
		 * generation snapshot already uses. A product with no revisions yields
		 * null, so a plan built without them keeps an empty capability snapshot.
		 */
		String snapshot() {
			if (assetRevision == null && profileRevision == null) {
				return null;
			}
			return "PlannedProduct[assetRevision=" + assetRevision
				+ ";profileRevision=" + profileRevision + "]";
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

	/**
	 * One step per product, in the order the Studio submitted them. The list
	 * order is the Studio's explicit order and is carried through as given; a
	 * plan is never re-sorted.
	 */
	public static RenderPlan oneProductPerStep(List<PlannedProduct> products) {
		if (products == null) {
			throw new IllegalArgumentException("products is required");
		}
		if (products.isEmpty()) {
			return new RenderPlan(1, List.of(new RenderStep(UUID.randomUUID(), 1, null, List.of())));
		}
		List<RenderStep> planned = new ArrayList<>(products.size());
		int stepIndex = 1;
		for (PlannedProduct product : products) {
			planned.add(new RenderStep(
				UUID.randomUUID(),
				stepIndex++,
				product.snapshot(),
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
