package com.aifashionvault.studio.agent;

import com.aifashionvault.shared.StudioItemId;
import com.aifashionvault.shared.StudioSlot;
import com.aifashionvault.shared.VaultItemId;

/** One placed garment in a Studio draft. */
public record StudioItemValue(StudioItemId id, StudioSlot slot, VaultItemId vaultItemId, int position) {
}
