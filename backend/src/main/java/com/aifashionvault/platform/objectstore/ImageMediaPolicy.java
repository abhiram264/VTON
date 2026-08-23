package com.aifashionvault.platform.objectstore;

import java.util.Locale;

public final class ImageMediaPolicy {

	public static final long MAX_BYTES = 30L * 1024 * 1024;
	public static final long MIN_BYTES = 1L;
	public static final int MIN_DIMENSION = 15;
	public static final int MAX_SIDE = 8192;
	public static final long MAX_PIXELS = 64_000_000L;

	private static final String JPEG = "image/jpeg";
	private static final String PNG = "image/png";

	private ImageMediaPolicy() {
	}

	public static String requireDeclaration(String contentType, long contentLength) {
		if (contentType == null || contentType.isBlank()) {
			throw new IllegalArgumentException("content type is required");
		}
		String mediaType = mediaType(contentType);
		String normalized;
		if (JPEG.equals(mediaType)) {
			normalized = JPEG;
		}
		else if (PNG.equals(mediaType)) {
			normalized = PNG;
		}
		else {
			throw new IllegalArgumentException("unsupported content type");
		}
		if (contentLength < MIN_BYTES || contentLength > MAX_BYTES) {
			throw new IllegalArgumentException("content length is out of range");
		}
		return normalized;
	}

	public static void requireDimensions(int width, int height) {
		if (width < MIN_DIMENSION || height < MIN_DIMENSION) {
			throw new IllegalArgumentException("image is smaller than the minimum dimension");
		}
		if (width > MAX_SIDE || height > MAX_SIDE) {
			throw new IllegalArgumentException("image side exceeds the maximum");
		}
		if ((long) width * (long) height > MAX_PIXELS) {
			throw new IllegalArgumentException("image pixel count exceeds the maximum");
		}
	}

	private static String mediaType(String contentType) {
		int semicolon = contentType.indexOf(';');
		String type = semicolon < 0 ? contentType : contentType.substring(0, semicolon);
		return type.strip().toLowerCase(Locale.ROOT);
	}
}
