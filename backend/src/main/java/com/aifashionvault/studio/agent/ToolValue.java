package com.aifashionvault.studio.agent;

import com.aifashionvault.shared.*;
import java.util.List;

public sealed interface ToolValue permits VaultItemsValue, VaultItemValue, CollectionValue, StudioStateValue, GenerationValue, SavedLookValue {}
record VaultItemSummary(VaultItemId id, String name, String brand, String category, String subcategory, String color, String pattern) {}
record VaultItemsValue(List<VaultItemSummary> items) implements ToolValue { public VaultItemsValue { items = List.copyOf(items); } }
record VaultItemValue(VaultItemSummary item) implements ToolValue {}
record CollectionValue(CollectionId collectionId, String name, List<VaultItemSummary> items) implements ToolValue { public CollectionValue { items = List.copyOf(items); } }
record StudioItemValue(StudioItemId id, StudioSlot slot, VaultItemId vaultItemId, int position) {}
record StudioStateValue(StudioSessionId sessionId, AvatarProfileId avatarProfileId, List<StudioItemValue> items) implements ToolValue { public StudioStateValue { items = List.copyOf(items); } }
record GenerationValue(GenerationJobId generationJobId, String status) implements ToolValue {}
record SavedLookValue(LookId lookId) implements ToolValue {}
