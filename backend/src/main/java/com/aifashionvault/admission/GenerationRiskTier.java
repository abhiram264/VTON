package com.aifashionvault.admission;

public enum GenerationRiskTier {
    ORDINARY(5), ANONYMOUS(4), NEW_DEVICE(3), SUSPICIOUS_IP(2), FAILED_ATTESTATION(1);
    private final int dailyLimit;
    GenerationRiskTier(int dailyLimit) { this.dailyLimit = dailyLimit; }
    public int dailyLimit() { return dailyLimit; }
}
