package com.aifashionvault.generation;

import java.util.List;
import java.util.UUID;

import com.aifashionvault.shared.VaultItemId;

public record RenderStep(UUID id, int stepIndex, String capabilitySnapshot, List<Item> items) {

	public record Item(UUID id, int itemOrdinal, VaultItemId vaultItemId, String category, String slot) {

		public Item {
			if (id == null) {
				throw new IllegalArgumentException("id is required");
			}
			if (itemOrdinal < 1) {
				throw new IllegalArgumentException("itemOrdinal must be >= 1");
			}
			if (vaultItemId == null) {
				throw new IllegalArgumentException("vaultItemId is required");
			}
		}
	}

	public RenderStep {
		if (id == null) {
			throw new IllegalArgumentException("id is required");
		}
		if (stepIndex < 1) {
			throw new IllegalArgumentException("stepIndex must be >= 1");
		}
		if (items == null) {
			throw new IllegalArgumentException("items is required");
		}
		items = List.copyOf(items);
	}
}
