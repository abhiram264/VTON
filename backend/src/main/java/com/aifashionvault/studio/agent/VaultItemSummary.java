package com.aifashionvault.studio.agent;

import com.aifashionvault.shared.VaultItemId;

/** Owner-scoped projection of a Vault item, safe to hand back to the agent layer. */
public record VaultItemSummary(VaultItemId id, String name, String brand, String category, String subcategory,
		String color, String pattern) {
}
