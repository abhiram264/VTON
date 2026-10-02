package com.aifashionvault.studio.agent;

import java.util.List;

/** Result of a Vault search. */
public record VaultItemsValue(List<VaultItemSummary> items) implements ToolValue {
	public VaultItemsValue {
		items = List.copyOf(items);
	}
}
