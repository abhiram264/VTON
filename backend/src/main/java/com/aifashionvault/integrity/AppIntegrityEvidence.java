package com.aifashionvault.integrity;

public record AppIntegrityEvidence(AppIntegrityOperation operation, AppIntegrityPlatform platform,
        String opaqueEvidence, String requestNonce) {}
