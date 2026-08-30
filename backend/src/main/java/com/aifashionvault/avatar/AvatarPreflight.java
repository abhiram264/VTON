package com.aifashionvault.avatar;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

public class AvatarPreflight {

	private static final double MAX_ASPECT = 4.0;
	private static final int HEURISTIC_MAX_SIDE = 64;
	private static final double HARD_DARK_LUMA = 8.0;
	private static final double WARN_DARK_LUMA = 40.0;
	private static final double HARD_BLUR_VARIANCE = 5.0;
	private static final double WARN_BLUR_VARIANCE = 30.0;

	private final AvatarBytesInspector inspector;
	private final SemanticInspector semanticInspector;

	public AvatarPreflight(AvatarBytesInspector inspector, SemanticInspector semanticInspector) {
		if (inspector == null || semanticInspector == null) {
			throw new IllegalArgumentException("inspector and semanticInspector are required");
		}
		this.inspector = inspector;
		this.semanticInspector = semanticInspector;
	}

	public AvatarPreflightResult inspect(byte[] bytes) {
		AvatarBytesInspector.Result inspected = inspector.inspect(bytes);
		BufferedImage raster = readRaster(bytes);
		int width = inspected.width();
		int height = inspected.height();
		int min = Math.min(width, height);
		int max = Math.max(width, height);
		if (min == 0) {
			throw new AvatarException(AvatarProblemCode.AVATAR_UNREADABLE);
		}
		if ((double) max / (double) min > MAX_ASPECT) {
			throw new AvatarException(AvatarProblemCode.AVATAR_ASPECT_RATIO);
		}
		if (min < AvatarPreflightPolicy.MIN_SHORT_SIDE) {
			throw new AvatarException(AvatarProblemCode.AVATAR_RESOLUTION_INSUFFICIENT);
		}
		double[][] luma = lumaGrid(downscale(raster));
		double meanLuma = mean(luma);
		if (meanLuma < HARD_DARK_LUMA) {
			throw new AvatarException(AvatarProblemCode.AVATAR_TOO_DARK);
		}
		double variance = laplacianVariance(luma);
		if (variance < HARD_BLUR_VARIANCE) {
			throw new AvatarException(AvatarProblemCode.AVATAR_TOO_BLURRED);
		}
		SemanticInspection semantic;
		try {
			semantic = semanticInspector.inspect(bytes);
		}
		catch (SemanticInspectionUnavailableException ex) {
			throw new AvatarException(AvatarProblemCode.AVATAR_PREFLIGHT_UNAVAILABLE);
		}
		if (semantic.personCountClass() != PersonCountClass.ONE) {
			throw new AvatarException(AvatarProblemCode.AVATAR_PERSON_NOT_SINGLE);
		}
		if (!semantic.faceVisible()) {
			throw new AvatarException(AvatarProblemCode.AVATAR_FACE_NOT_VISIBLE);
		}
		if (!semantic.bodyVisible()) {
			throw new AvatarException(AvatarProblemCode.AVATAR_BODY_NOT_VISIBLE);
		}
		List<String> warnings = new ArrayList<>();
		if (meanLuma < WARN_DARK_LUMA) {
			warnings.add(AvatarProblemCode.AVATAR_TOO_DARK.name());
		}
		if (variance < WARN_BLUR_VARIANCE) {
			warnings.add(AvatarProblemCode.AVATAR_TOO_BLURRED.name());
		}
		return new AvatarPreflightResult(
			inspected.mediaType(),
			width,
			height,
			warnings,
			AvatarPreflightPolicy.VERSION);
	}

	public static AvatarProblemCode map(String message) {
		if (message == null) {
			return AvatarProblemCode.AVATAR_UNREADABLE;
		}
		if (message.contains("unknown or malformed") || message.contains("malformed")) {
			return AvatarProblemCode.AVATAR_UNREADABLE;
		}
		if (message.contains("unsupported")) {
			return AvatarProblemCode.AVATAR_UNSUPPORTED_FORMAT;
		}
		if (message.contains("smaller than the minimum")) {
			return AvatarProblemCode.AVATAR_RESOLUTION_INSUFFICIENT;
		}
		if (message.contains("side exceeds")) {
			return AvatarProblemCode.AVATAR_DIMENSIONS_EXCEEDED;
		}
		if (message.contains("pixel count")) {
			return AvatarProblemCode.AVATAR_PIXELS_EXCEEDED;
		}
		return AvatarProblemCode.AVATAR_UNREADABLE;
	}

	private static BufferedImage readRaster(byte[] bytes) {
		try {
			BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
			if (image == null) {
				throw new AvatarException(AvatarProblemCode.AVATAR_UNREADABLE);
			}
			return image;
		}
		catch (IOException ex) {
			throw new AvatarException(AvatarProblemCode.AVATAR_UNREADABLE);
		}
	}

	private static BufferedImage downscale(BufferedImage source) {
		int width = source.getWidth();
		int height = source.getHeight();
		int longest = Math.max(width, height);
		if (longest <= HEURISTIC_MAX_SIDE) {
			return source;
		}
		double scale = (double) HEURISTIC_MAX_SIDE / (double) longest;
		int newWidth = Math.max(1, (int) Math.round(width * scale));
		int newHeight = Math.max(1, (int) Math.round(height * scale));
		BufferedImage scaled = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = scaled.createGraphics();
		try {
			graphics.setRenderingHint(
				RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
			graphics.drawImage(source, 0, 0, newWidth, newHeight, null);
		}
		finally {
			graphics.dispose();
		}
		return scaled;
	}

	private static double[][] lumaGrid(BufferedImage image) {
		int width = image.getWidth();
		int height = image.getHeight();
		double[][] luma = new double[height][width];
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int rgb = image.getRGB(x, y);
				int red = (rgb >> 16) & 0xFF;
				int green = (rgb >> 8) & 0xFF;
				int blue = rgb & 0xFF;
				luma[y][x] = 0.299 * red + 0.587 * green + 0.114 * blue;
			}
		}
		return luma;
	}

	private static double mean(double[][] grid) {
		double sum = 0.0;
		int count = 0;
		for (double[] row : grid) {
			for (double value : row) {
				sum += value;
				count++;
			}
		}
		return count == 0 ? 0.0 : sum / count;
	}

	private static double laplacianVariance(double[][] luma) {
		int height = luma.length;
		int width = height == 0 ? 0 : luma[0].length;
		if (height < 3 || width < 3) {
			return 0.0;
		}
		int count = (height - 2) * (width - 2);
		double sum = 0.0;
		double[] responses = new double[count];
		int index = 0;
		for (int y = 1; y < height - 1; y++) {
			for (int x = 1; x < width - 1; x++) {
				double response = 4.0 * luma[y][x]
					- luma[y][x - 1]
					- luma[y][x + 1]
					- luma[y - 1][x]
					- luma[y + 1][x];
				responses[index++] = response;
				sum += response;
			}
		}
		double mean = sum / count;
		double varianceSum = 0.0;
		for (double response : responses) {
			double delta = response - mean;
			varianceSum += delta * delta;
		}
		return varianceSum / count;
	}
}
