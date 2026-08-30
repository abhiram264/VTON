package com.aifashionvault.platform.objectstore;

import com.aifashionvault.avatar.AvatarBytesInspector;
import com.aifashionvault.avatar.AvatarException;
import com.aifashionvault.avatar.AvatarMediaType;
import com.aifashionvault.avatar.AvatarPreflight;

public final class ImageInspectorAvatarBytesInspector implements AvatarBytesInspector {

	private final ImageInspector inspector;

	public ImageInspectorAvatarBytesInspector(ImageInspector inspector) {
		if (inspector == null) {
			throw new IllegalArgumentException("inspector is required");
		}
		this.inspector = inspector;
	}

	@Override
	public Result inspect(byte[] bytes) {
		ImageInspector.Result inspected;
		try {
			inspected = inspector.inspect(bytes);
		}
		catch (ObjectStoreExceptions.ImageValidationException ex) {
			throw new AvatarException(AvatarPreflight.map(ex.getMessage()));
		}
		return new Result(toAvatarMediaType(inspected.mediaType()), inspected.width(), inspected.height());
	}

	private static AvatarMediaType toAvatarMediaType(ImageMediaType mediaType) {
		return switch (mediaType) {
			case JPEG -> AvatarMediaType.JPEG;
			case PNG -> AvatarMediaType.PNG;
		};
	}
}
