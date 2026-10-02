package com.aifashionvault.studio.agent;

import com.aifashionvault.shared.LookId;

/** A published Look. */
public record SavedLookValue(LookId lookId) implements ToolValue {
}
