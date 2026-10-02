package com.aifashionvault.studio.agent;

/**
 * The values the allowlisted Studio agent tools return.
 *
 * <p>Each implementation lives in its own file and is public, because the port
 * ({@link StudioAgentTools}) is implemented outside this package: the
 * implementation needs the real Vault, Collection and Studio stores, which only
 * the composition layer is allowed to depend on.</p>
 */
public sealed interface ToolValue
		permits VaultItemsValue, VaultItemValue, CollectionValue, StudioStateValue, GenerationValue, SavedLookValue {
}
