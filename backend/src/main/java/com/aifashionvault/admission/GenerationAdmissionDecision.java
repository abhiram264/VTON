package com.aifashionvault.admission;

import java.time.LocalDate;

public record GenerationAdmissionDecision(boolean allowed, String reason, int limit, int used, LocalDate utcDay) {
    public static GenerationAdmissionDecision rejected(String reason, int used, int limit, LocalDate day) {
        return new GenerationAdmissionDecision(false, reason, limit, used, day);
    }
    public static GenerationAdmissionDecision accepted(int used, int limit, LocalDate day) {
        return new GenerationAdmissionDecision(true, "ADMITTED", limit, used, day);
    }
}
