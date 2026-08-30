package com.aifashionvault.avatar;

public final class AvatarException extends RuntimeException {

	private final AvatarProblemCode code;

	public AvatarException(AvatarProblemCode code) {
		super(messageOf(code));
		this.code = code;
	}

	public AvatarProblemCode code() {
		return code;
	}

	private static String messageOf(AvatarProblemCode code) {
		if (code == null) {
			throw new IllegalArgumentException("code is required");
		}
		return code.name();
	}
}
