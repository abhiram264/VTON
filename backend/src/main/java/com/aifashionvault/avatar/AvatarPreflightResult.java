package com.aifashionvault.avatar;

import java.util.List;

public record AvatarPreflightResult(
	AvatarMediaType mediaType,
	int width,
	int height,
	List<String> warnings,
	String policyVersion) {

	public AvatarPreflightResult {
		if (mediaType == null) {
			throw new IllegalArgumentException("mediaType is required");
		}
		if (warnings == null) {
			throw new IllegalArgumentException("warnings is required");
		}
		if (policyVersion == null || policyVersion.isBlank()) {
			throw new IllegalArgumentException("policyVersion is required");
		}
		for (String warning : warnings) {
			if (warning == null) {
				throw new IllegalArgumentException("warning is required");
			}
		}
		warnings = List.copyOf(warnings);
	}

	@Override
	public String toString() {
		return "AvatarPreflightResult[mediaType=" + mediaType
			+ ", width=" + width
			+ ", height=" + height
			+ ", warnings=" + warnings
			+ ", policyVersion=" + policyVersion
			+ "]";
	}
}
