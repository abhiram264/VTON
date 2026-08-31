package com.aifashionvault.integrity;

public final class AndroidIntegrityAdapter {
    private static final String EXPECTED_APP_ID = "app";
    private final IntegrityEvidenceProvider provider;
    public AndroidIntegrityAdapter(IntegrityEvidenceProvider provider) { this.provider = provider; }
    ProviderVerification verify(AppIntegrityEvidence evidence) { return provider.verify(evidence); }
    String expectedAppId() { return EXPECTED_APP_ID; }
}
