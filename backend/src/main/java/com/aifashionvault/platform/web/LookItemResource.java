package com.aifashionvault.platform.web;

import java.util.UUID;

import com.aifashionvault.shared.StudioSlot;

public record LookItemResource(UUID vaultItemId, StudioSlot slot) {}
