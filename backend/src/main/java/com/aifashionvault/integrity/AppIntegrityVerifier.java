package com.aifashionvault.integrity;

public interface AppIntegrityVerifier {
    AppIntegrityVerdict verify(AppIntegrityEvidence evidence);
}
