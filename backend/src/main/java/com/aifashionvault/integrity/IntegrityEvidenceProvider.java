package com.aifashionvault.integrity;

@FunctionalInterface
public interface IntegrityEvidenceProvider {
    ProviderVerification verify(AppIntegrityEvidence evidence);
}
