package com.aifashionvault.integrity;

public final class IosIntegrityAdapter {
    private final IntegrityEvidenceProvider provider;
    private final String expectedAppId;

    public IosIntegrityAdapter(IntegrityEvidenceProvider provider) {
        this(provider, "app");
    }

    IosIntegrityAdapter(IntegrityEvidenceProvider provider, String expectedAppId) {
        this.provider = provider;
        this.expectedAppId = expectedAppId;
    }

    ProviderVerification verify(AppIntegrityEvidence evidence) { return provider.verify(evidence); }
    String expectedAppId() { return expectedAppId; }
}
