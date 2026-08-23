package com.aifashionvault.platform.objectstore;

public final class ObjectStoreExceptions {

	private ObjectStoreExceptions() {
	}

	public static final class InvalidUploadDeclarationException extends RuntimeException {

		public InvalidUploadDeclarationException(String message) {
			super(requireMessage(message));
		}
	}

	public static final class UnsupportedMediaTypeException extends RuntimeException {

		public UnsupportedMediaTypeException(String message) {
			super(requireMessage(message));
		}
	}

	public static final class StagedObjectMissingException extends RuntimeException {

		public StagedObjectMissingException(String message) {
			super(requireMessage(message));
		}
	}

	public static final class ImageValidationException extends RuntimeException {

		public ImageValidationException(String message) {
			super(requireMessage(message));
		}
	}

	public static final class ClaimConflictException extends RuntimeException {

		public ClaimConflictException(String message) {
			super(requireMessage(message));
		}
	}

	public static final class ObjectStoreUnavailableException extends RuntimeException {

		public ObjectStoreUnavailableException(String message) {
			super(requireMessage(message));
		}
	}

	private static String requireMessage(String message) {
		if (message == null || message.isBlank()) {
			throw new IllegalArgumentException("message is required");
		}
		return message;
	}
}
