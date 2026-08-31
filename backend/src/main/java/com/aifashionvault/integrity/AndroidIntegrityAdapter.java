package com.aifashionvault.integrity;

public final class AndroidIntegrityAdapter {
    private final IntegrityEvidenceProvider provider;
    public AndroidIntegrityAdapter(IntegrityEvidenceProvider provider) { this.provider = provider; }
    ProviderVerification verify(AppIntegrityEvidence evidence) { return provider.verify(evidence); }
}
