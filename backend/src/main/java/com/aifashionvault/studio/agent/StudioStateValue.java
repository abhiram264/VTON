package com.aifashionvault.studio.agent;

import java.util.List;

import com.aifashionvault.shared.AvatarProfileId;
import com.aifashionvault.shared.StudioSessionId;

/** The authoritative Studio draft as the agent layer sees it. */
public record StudioStateValue(StudioSessionId sessionId, AvatarProfileId avatarProfileId,
		List<StudioItemValue> items) implements ToolValue {
	public StudioStateValue {
		items = List.copyOf(items);
	}
}
