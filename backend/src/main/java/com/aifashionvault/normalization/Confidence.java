package com.aifashionvault.normalization;

final class Confidence {

	private Confidence() {
	}

	static void requireValid(double value) {
		if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
			throw new IllegalArgumentException("confidence must be finite and between 0.0 and 1.0");
		}
	}
}
