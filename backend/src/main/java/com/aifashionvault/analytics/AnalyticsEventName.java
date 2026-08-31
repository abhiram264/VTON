package com.aifashionvault.analytics;

public enum AnalyticsEventName {
    ACCOUNT_CREATED("account_created"),
    AVATAR_CREATION_STARTED("avatar_creation_started"),
    AVATAR_CREATED("avatar_created"),
    AVATAR_REJECTED("avatar_rejected"),
    SHARE_RECEIVED("share_received"),
    SHARE_RESOLVED("share_resolved"),
    SHARE_RESOLUTION_FAILED("share_resolution_failed"),
    VAULT_ITEM_CREATED("vault_item_created"),
    VAULT_ITEM_REUSED("vault_item_reused"),
    COLLECTION_CREATED("collection_created"),
    STUDIO_STARTED("studio_started"),
    STUDIO_ITEM_ADDED("studio_item_added"),
    STUDIO_ITEM_REMOVED("studio_item_removed"),
    GENERATION_REQUESTED("generation_requested"),
    GENERATION_COMPLETED("generation_completed"),
    GENERATION_FAILED("generation_failed"),
    GENERATION_QUALITY_REJECTED("generation_quality_rejected"),
    LOOK_VIEWED("look_viewed"),
    LOOK_SAVED("look_saved"),
    LOOK_SHARED("look_shared"),
    SOURCE_LINK_OPENED("source_link_opened");

    private final String wireName;

    AnalyticsEventName(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
