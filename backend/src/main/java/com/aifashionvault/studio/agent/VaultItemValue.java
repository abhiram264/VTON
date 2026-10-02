package com.aifashionvault.studio.agent;

/** Result of a single owned Vault item lookup. */
public record VaultItemValue(VaultItemSummary item) implements ToolValue {
}
