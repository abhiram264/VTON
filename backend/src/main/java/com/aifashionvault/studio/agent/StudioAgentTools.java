package com.aifashionvault.studio.agent;

import com.aifashionvault.shared.*;

public interface StudioAgentTools {
    ToolValue searchVault(UserId actorId, StudioSessionId sessionId, String query, String category);
    ToolValue getVaultItem(UserId actorId, StudioSessionId sessionId, VaultItemId vaultItemId);
    ToolValue listCollection(UserId actorId, StudioSessionId sessionId, CollectionId collectionId);
    ToolValue addItem(UserId actorId, StudioSessionId sessionId, VaultItemId vaultItemId, StudioSlot slot, int position);
    ToolValue removeItem(UserId actorId, StudioSessionId sessionId, StudioSlot slot, Integer position);
    ToolValue replaceItem(UserId actorId, StudioSessionId sessionId, VaultItemId vaultItemId, StudioSlot slot, Integer position);
    ToolValue setAvatar(UserId actorId, StudioSessionId sessionId, AvatarProfileId avatarProfileId);
    ToolValue getStudioState(UserId actorId, StudioSessionId sessionId);
    ToolValue generateLook(UserId actorId, StudioSessionId sessionId, String idempotencyKey);
    ToolValue saveLook(UserId actorId, StudioSessionId sessionId, GenerationJobId generationJobId);
}
