package com.aifashionvault.integrity;

public final class IosIntegrityAdapter {
    private static final String EXPECTED_APP_ID = "app";
    private final IntegrityEvidenceProvider provider;
    public IosIntegrityAdapter(IntegrityEvidenceProvider provider) { this.provider = provider; }
    ProviderVerification verify(AppIntegrityEvidence evidence) { return provider.verify(evidence); }
    String expectedAppId() { return EXPECTED_APP_ID; }
}
