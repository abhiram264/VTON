package com.aifashionvault.look;

import com.aifashionvault.shared.LookId;
import com.aifashionvault.shared.StudioSlot;
import com.aifashionvault.shared.VaultItemId;

public record LookItem(LookId lookId, VaultItemId vaultItemId, StudioSlot slot) {

	public LookItem {
		if (lookId == null) {
			throw new IllegalArgumentException("lookId is required");
		}
		if (vaultItemId == null) {
			throw new IllegalArgumentException("vaultItemId is required");
		}
	}
}
