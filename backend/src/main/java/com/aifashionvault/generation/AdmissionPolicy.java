package com.aifashionvault.generation;

public record AdmissionPolicy(int acceptedJobsPer24Hours, int maxActiveJobs, int maxProductsPerJob) {

	public static final AdmissionPolicy DEFAULT = new AdmissionPolicy(5, 2, 6);

	public AdmissionPolicy {
		if (acceptedJobsPer24Hours < 1) {
			throw new IllegalArgumentException("acceptedJobsPer24Hours must be >= 1");
		}
		if (maxActiveJobs < 1) {
			throw new IllegalArgumentException("maxActiveJobs must be >= 1");
		}
		if (maxProductsPerJob < 1) {
			throw new IllegalArgumentException("maxProductsPerJob must be >= 1");
		}
	}

	public boolean allowsProductCount(int productCount) {
		return productCount >= 0 && productCount <= maxProductsPerJob;
	}
}
