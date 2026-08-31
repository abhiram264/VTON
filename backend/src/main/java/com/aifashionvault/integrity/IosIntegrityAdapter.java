package com.aifashionvault.integrity;

public final class IosIntegrityAdapter {
    private final IntegrityEvidenceProvider provider;
    public IosIntegrityAdapter(IntegrityEvidenceProvider provider) { this.provider = provider; }
    ProviderVerification verify(AppIntegrityEvidence evidence) { return provider.verify(evidence); }
}
