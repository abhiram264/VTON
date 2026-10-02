package com.aifashionvault.studio.agent;

import java.util.List;

import com.aifashionvault.shared.CollectionId;

/** Result of listing an owned collection. */
public record CollectionValue(CollectionId collectionId, String name, List<VaultItemSummary> items)
		implements ToolValue {
	public CollectionValue {
		items = List.copyOf(items);
	}
}
