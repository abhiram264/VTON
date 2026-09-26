package com.aifashionvault.avatar;

public interface AvatarBytesInspector {

	record Result(AvatarMediaType mediaType, int width, int height) {

		public Result {
			if (mediaType == null) {
				throw new IllegalArgumentException("mediaType is required");
			}
			if (width <= 0) {
				throw new IllegalArgumentException("width must be positive");
			}
			if (height <= 0) {
				throw new IllegalArgumentException("height must be positive");
			}
		}
	}

	Result inspect(byte[] bytes);
}
