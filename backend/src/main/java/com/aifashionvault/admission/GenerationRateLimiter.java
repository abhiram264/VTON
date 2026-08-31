package com.aifashionvault.admission;

public interface GenerationRateLimiter {
	GenerationAdmissionDecision admit(GenerationAdmissionRequest request);
}
