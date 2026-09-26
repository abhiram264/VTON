package com.aifashionvault.integrity;

public final class AndroidIntegrityAdapter {
    private final IntegrityEvidenceProvider provider;
    private final String expectedAppId;

    public AndroidIntegrityAdapter(IntegrityEvidenceProvider provider) {
        this(provider, "app");
    }

    AndroidIntegrityAdapter(IntegrityEvidenceProvider provider, String expectedAppId) {
        this.provider = provider;
        this.expectedAppId = expectedAppId;
    }

    ProviderVerification verify(AppIntegrityEvidence evidence) { return provider.verify(evidence); }
    String expectedAppId() { return expectedAppId; }
}
