package com.aifashionvault.admission;

public interface GenerationRateLimiter {
	Reservation reserve(GenerationAdmissionRequest request);

	void commit(Reservation reservation);

	void release(Reservation reservation);

	record Reservation(GenerationAdmissionDecision decision, String token) {
		public Reservation {
			if (decision == null) throw new IllegalArgumentException("decision is required");
			if (decision.allowed() && (token == null || token.isBlank()))
				throw new IllegalArgumentException("accepted reservation token is required");
			if (!decision.allowed() && token != null)
				throw new IllegalArgumentException("rejected reservation must not have a token");
		}
	}
}
