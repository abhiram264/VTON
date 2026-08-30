package com.aifashionvault.platform.generation;

/**
 * Application-owned vision evidence boundary for the quality-v2 policy.
 *
 * <p>The port receives only private application-owned image bytes. Implementations
 * return bounded, coarse evidence; they do not expose provider media, face
 * templates, or attractiveness judgements.
 */
@FunctionalInterface
public interface QualityVisionPort {

	QualityVisionEvidence inspect(
		byte[] candidate,
		byte[] subject,
		byte[] garment,
		byte[] previousAccepted);
}
