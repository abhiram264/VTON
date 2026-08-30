package com.aifashionvault.avatar;

public record SemanticInspection(
	PersonCountClass personCountClass,
	boolean faceVisible,
	boolean bodyVisible,
	String evaluatorVersion) {

	public SemanticInspection {
		if (personCountClass == null) {
			throw new IllegalArgumentException("personCountClass is required");
		}
		if (evaluatorVersion == null || evaluatorVersion.isBlank()) {
			throw new IllegalArgumentException("evaluatorVersion is required");
		}
	}
}
