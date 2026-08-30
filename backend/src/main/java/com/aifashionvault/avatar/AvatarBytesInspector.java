package com.aifashionvault.avatar;

public interface AvatarBytesInspector {

	record Result(AvatarMediaType mediaType, int width, int height) {

		public Result {
			if (mediaType == null) {
				throw new IllegalArgumentException("mediaType is required");
			}
		}
	}

	Result inspect(byte[] bytes);
}
