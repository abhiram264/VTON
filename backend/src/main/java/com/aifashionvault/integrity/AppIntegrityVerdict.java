package com.aifashionvault.integrity;

public record AppIntegrityVerdict(AppIntegrityVerdictStatus status, AppIntegrityFailureReason reason) {
    public AppIntegrityVerdict {
        if (status == null || reason == null || (status == AppIntegrityVerdictStatus.VERIFIED) != (reason == AppIntegrityFailureReason.NONE))
            throw new IllegalArgumentException("Invalid integrity verdict");
    }
}
