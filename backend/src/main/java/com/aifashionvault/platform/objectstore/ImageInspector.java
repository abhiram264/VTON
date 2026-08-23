package com.aifashionvault.platform.objectstore;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

public final class ImageInspector {

	private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
	private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

	public record Result(ImageMediaType mediaType, int width, int height) {

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

	public Result inspect(byte[] bytes) {
		ImageMediaType mediaType = magicType(bytes);
		String formatName = mediaType == ImageMediaType.JPEG ? "JPEG" : "PNG";
		ImageReader reader = null;
		try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
			if (input == null) {
				throw new ObjectStoreExceptions.ImageValidationException("malformed image");
			}
			Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(formatName);
			if (!readers.hasNext()) {
				throw new ObjectStoreExceptions.ImageValidationException("malformed image");
			}
			reader = readers.next();
			reader.setInput(input, true, true);
			int width = reader.getWidth(0);
			int height = reader.getHeight(0);
			try {
				ImageMediaPolicy.requireDimensions(width, height);
			}
			catch (IllegalArgumentException ex) {
				throw new ObjectStoreExceptions.ImageValidationException(safeMessage(ex, "invalid image dimensions"));
			}
			return new Result(mediaType, width, height);
		}
		catch (ObjectStoreExceptions.ImageValidationException ex) {
			throw ex;
		}
		catch (IOException ex) {
			throw new ObjectStoreExceptions.ImageValidationException("malformed image");
		}
		finally {
			if (reader != null) {
				reader.dispose();
			}
		}
	}

	private static ImageMediaType magicType(byte[] bytes) {
		if (bytes != null && startsWith(bytes, PNG_MAGIC)) {
			return ImageMediaType.PNG;
		}
		if (bytes != null && startsWith(bytes, JPEG_MAGIC)) {
			return ImageMediaType.JPEG;
		}
		throw new ObjectStoreExceptions.ImageValidationException("unknown or malformed image");
	}

	private static boolean startsWith(byte[] bytes, byte[] prefix) {
		if (bytes.length < prefix.length) {
			return false;
		}
		for (int i = 0; i < prefix.length; i++) {
			if (bytes[i] != prefix[i]) {
				return false;
			}
		}
		return true;
	}

	private static String safeMessage(IllegalArgumentException ex, String fallback) {
		String message = ex.getMessage();
		if (message == null || message.isBlank()) {
			return fallback;
		}
		return message;
	}
}
