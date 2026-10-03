package com.aifashionvault.platform.generation;

import java.math.BigDecimal;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

/**
 * Deterministic, local CPU implementation of {@link QualityVisionPort}.
 *
 * <p>The two bundled ONNX models are loaded lazily once, from checksummed
 * classpath resources, with default {@link OrtSession.SessionOptions}; no GPU
 * execution provider is selected. YOLOX supplies coarse person boxes and
 * YuNet supplies face boxes. No face template is retained and no gallery
 * lookup is performed.
 *
 * <p>The numeric axes are conservative acceptability proxies rather than
 * attractiveness measurements. Garment fidelity combines quantized RGB
 * histogram intersection with a luminance structure proxy. Color fidelity is
 * a per-channel RGB histogram intersection. Previous-item preservation uses
 * the same RGB histogram similarity and applies the frozen {@code 0.80}
 * threshold. Identity is pairwise 1:1 evidence: presence agreement and box
 * count agreement between this request's subject and candidate, never 1:N.
 * Anatomy requires one plausible person box with sufficient body visibility.
 * A decoded near-blank raster is marked as an artifact; semantic inference
 * failures are surfaced as evaluator unavailability rather than product
 * retries.
 */
public final class OnnxCpuQualityVision implements QualityVisionPort, AutoCloseable {

	static final String EVALUATOR_VERSION = "quality-v2-cpu-onnx";

	private static final String RESOURCE_ROOT = "/avatar/preflight/";
	private static final Artifact YOLOX_ARTIFACT = new Artifact(
		"object_detection_yolox_2022nov.onnx",
		35_858_002,
		"c5c2d13e59ae883e6af3b45daea64af4833a4951c92d116ec270d9ddbe998063");
	private static final Artifact YUNET_ARTIFACT = new Artifact(
		"face_detection_yunet_2023mar.onnx",
		232_589,
		"8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4");

	private static final int YOLOX_SIZE = 640;
	private static final int[] STRIDES = {8, 16, 32};
	private static final float YOLOX_CONFIDENCE = 0.5f;
	private static final float YOLOX_NMS_IOU = 0.5f;
	private static final float YUNET_CONFIDENCE = 0.6f;
	private static final float YUNET_NMS_IOU = 0.3f;
	private static final float MIN_BODY_HEIGHT_RATIO = 0.5f;
	private static final float MIN_BODY_BOTTOM_RATIO = 0.6f;
	private static final double PREVIOUS_ITEMS_THRESHOLD = 0.80d;
	private static final int HISTOGRAM_BINS = 8;
	private static final int CHANNEL_HISTOGRAM_BINS = 16;

	private final EngineFactory engineFactory;
	private final Object initializationLock = new Object();
	private volatile Engine engine;
	private volatile boolean closed;

	public OnnxCpuQualityVision() {
		this(ModelEngine::load);
	}

	OnnxCpuQualityVision(EngineFactory engineFactory) {
		if (engineFactory == null) {
			throw new IllegalArgumentException("engineFactory is required");
		}
		this.engineFactory = engineFactory;
	}

	@Override
	public QualityVisionEvidence inspect(
		byte[] candidate,
		byte[] subject,
		byte[] garment,
		byte[] previousAccepted) {
		try {
			BufferedImage candidateImage = decode(candidate);
			BufferedImage subjectImage = decode(subject);
			BufferedImage garmentImage = decode(garment);
			BufferedImage previousImage = previousAccepted == null || previousAccepted.length == 0
				? null
				: decode(previousAccepted);

			Engine initialized = engine();
			SemanticEvidence candidateEvidence = initialized.inspectCandidate(candidateImage);
			List<FaceBox> candidateFaces = initialized.detectFaces(candidateImage);
			List<FaceBox> subjectFaces = initialized.detectFaces(subjectImage);
			int personCount = candidateEvidence.people().size();
			boolean bodyVisible = candidateEvidence.bodyVisible();
			boolean anatomyValid = candidateEvidence.anatomyValid();
			boolean artifactDetected = nearBlank(candidateImage) || candidateEvidence.people().isEmpty();
			BigDecimal garmentFidelity = score(appearanceSimilarity(candidateImage, garmentImage));
			BigDecimal colorFidelity = score(colorSimilarity(candidateImage, garmentImage));
			BigDecimal identityPreservation = score(identitySimilarity(candidateFaces, subjectFaces));
			boolean previousItemsPreserved = previousImage == null
				|| histogramSimilarity(candidateImage, previousImage) >= PREVIOUS_ITEMS_THRESHOLD;
			return new QualityVisionEvidence(
				personCount,
				bodyVisible,
				identityPreservation,
				garmentFidelity,
				colorFidelity,
				anatomyValid,
				artifactDetected,
				previousItemsPreserved);
		}
		catch (Exception | LinkageError exception) {
			throw new UnavailableException();
		}
	}

	@Override
	public void close() {
		Engine initialized;
		synchronized (initializationLock) {
			closed = true;
			initialized = engine;
			engine = null;
		}
		if (initialized != null) {
			try {
				initialized.close();
			}
			catch (Exception ignored) {
				// Shutdown cannot expose model or native-library details.
			}
		}
	}

	private Engine engine() throws Exception {
		Engine initialized = engine;
		if (initialized != null) {
			return initialized;
		}
		synchronized (initializationLock) {
			if (closed) {
				throw new IllegalStateException("quality evaluator unavailable");
			}
			if (engine == null) {
				engine = engineFactory.create();
			}
			return engine;
		}
	}

	private static BufferedImage decode(byte[] bytes) throws IOException {
		if (bytes == null || bytes.length == 0) {
			throw new IOException("quality evaluator unavailable");
		}
		BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
		if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
			throw new IOException("quality evaluator unavailable");
		}
		return image;
	}

	private static BigDecimal score(double value) {
		if (!Double.isFinite(value) || value < 0d || value > 1d) {
			throw new IllegalStateException("quality evaluator unavailable");
		}
		return BigDecimal.valueOf(value);
	}

	private static double identitySimilarity(List<FaceBox> candidateFaces, List<FaceBox> subjectFaces) {
		/*
		 * A pairwise consistency proxy deliberately refuses 1:N matching. The
		 * two half-points are presence agreement and box-count agreement.
		 */
		boolean presenceAgreement = (candidateFaces.size() > 0) == (subjectFaces.size() > 0);
		boolean boxCountAgreement = candidateFaces.size() == 1
			&& subjectFaces.size() == 1;
		return (presenceAgreement ? 0.5d : 0d) + (boxCountAgreement ? 0.5d : 0d);
	}

	private static double appearanceSimilarity(BufferedImage candidate, BufferedImage garment) {
		double histogram = histogramSimilarity(candidate, garment);
		double structure = structureSimilarity(candidate, garment);
		return 0.75d * histogram + 0.25d * structure;
	}

	private static double structureSimilarity(BufferedImage left, BufferedImage right) {
		double leftMean = luminanceMean(left);
		double rightMean = luminanceMean(right);
		double leftVariance = luminanceVariance(left, leftMean);
		double rightVariance = luminanceVariance(right, rightMean);
		double meanSimilarity = 1d - Math.min(1d, Math.abs(leftMean - rightMean) / 255d);
		double varianceSimilarity = 1d - Math.min(1d,
			Math.abs(Math.sqrt(leftVariance) - Math.sqrt(rightVariance)) / 255d);
		return clamp01((meanSimilarity + varianceSimilarity) / 2d);
	}

	private static double luminanceMean(BufferedImage image) {
		double sum = 0d;
		long pixels = (long) image.getWidth() * image.getHeight();
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				sum += luminance(image.getRGB(x, y));
			}
		}
		return sum / pixels;
	}

	private static double luminanceVariance(BufferedImage image, double mean) {
		double sum = 0d;
		long pixels = (long) image.getWidth() * image.getHeight();
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				double delta = luminance(image.getRGB(x, y)) - mean;
				sum += delta * delta;
			}
		}
		return sum / pixels;
	}

	private static double luminance(int rgb) {
		return 0.2126d * ((rgb >>> 16) & 0xff)
			+ 0.7152d * ((rgb >>> 8) & 0xff)
			+ 0.0722d * (rgb & 0xff);
	}

	private static double histogramSimilarity(BufferedImage left, BufferedImage right) {
		int[] leftHistogram = rgbHistogram(left, HISTOGRAM_BINS);
		int[] rightHistogram = rgbHistogram(right, HISTOGRAM_BINS);
		long leftPixels = (long) left.getWidth() * left.getHeight();
		long rightPixels = (long) right.getWidth() * right.getHeight();
		double similarity = 0d;
		for (int i = 0; i < leftHistogram.length; i++) {
			double leftFrequency = leftHistogram[i] / (double) leftPixels;
			double rightFrequency = rightHistogram[i] / (double) rightPixels;
			similarity += Math.min(leftFrequency, rightFrequency);
		}
		return clamp01(similarity);
	}

	private static int[] rgbHistogram(BufferedImage image, int bins) {
		int[] histogram = new int[bins * bins * bins];
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				int rgb = image.getRGB(x, y);
				int red = ((rgb >>> 16) & 0xff) * bins / 256;
				int green = ((rgb >>> 8) & 0xff) * bins / 256;
				int blue = (rgb & 0xff) * bins / 256;
				int index = (red * bins + green) * bins + blue;
				histogram[index]++;
			}
		}
		return histogram;
	}

	private static double colorSimilarity(BufferedImage left, BufferedImage right) {
		int[][] leftHistogram = channelHistograms(left);
		int[][] rightHistogram = channelHistograms(right);
		long leftPixels = (long) left.getWidth() * left.getHeight();
		long rightPixels = (long) right.getWidth() * right.getHeight();
		double total = 0d;
		for (int channel = 0; channel < 3; channel++) {
			double channelSimilarity = 0d;
			for (int bin = 0; bin < CHANNEL_HISTOGRAM_BINS; bin++) {
				double leftFrequency = leftHistogram[channel][bin] / (double) leftPixels;
				double rightFrequency = rightHistogram[channel][bin] / (double) rightPixels;
				channelSimilarity += Math.min(leftFrequency, rightFrequency);
			}
			total += channelSimilarity;
		}
		return clamp01(total / 3d);
	}

	private static int[][] channelHistograms(BufferedImage image) {
		int[][] histogram = new int[3][CHANNEL_HISTOGRAM_BINS];
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				int rgb = image.getRGB(x, y);
				histogram[0][((rgb >>> 16) & 0xff) * CHANNEL_HISTOGRAM_BINS / 256]++;
				histogram[1][((rgb >>> 8) & 0xff) * CHANNEL_HISTOGRAM_BINS / 256]++;
				histogram[2][(rgb & 0xff) * CHANNEL_HISTOGRAM_BINS / 256]++;
			}
		}
		return histogram;
	}

	private static boolean nearBlank(BufferedImage image) {
		double mean = luminanceMean(image);
		return luminanceVariance(image, mean) < 2.0d;
	}

	private static double clamp01(double value) {
		return Math.max(0d, Math.min(1d, value));
	}

	static List<DetectionBox> nonMaximumSuppression(List<DetectionBox> candidates, float iouThreshold) {
		List<DetectionBox> ordered = new ArrayList<>(candidates);
		ordered.sort(Comparator.comparingDouble(DetectionBox::score).reversed());
		List<DetectionBox> surviving = new ArrayList<>(ordered.size());
		for (DetectionBox candidate : ordered) {
			boolean suppressed = false;
			for (DetectionBox selected : surviving) {
				if (intersectionOverUnion(candidate, selected) > iouThreshold) {
					suppressed = true;
					break;
				}
			}
			if (!suppressed) {
				surviving.add(candidate);
			}
		}
		return surviving;
	}

	private static float intersectionOverUnion(DetectionBox left, DetectionBox right) {
		float intersectionWidth = Math.max(0, Math.min(left.x2(), right.x2()) - Math.max(left.x1(), right.x1()));
		float intersectionHeight = Math.max(0, Math.min(left.y2(), right.y2()) - Math.max(left.y1(), right.y1()));
		float intersection = intersectionWidth * intersectionHeight;
		float union = left.area() + right.area() - intersection;
		return union <= 0 ? 0 : intersection / union;
	}

	@FunctionalInterface
	interface EngineFactory {
		Engine create() throws Exception;
	}

	interface Engine extends AutoCloseable {
		SemanticEvidence inspectCandidate(BufferedImage image) throws Exception;

		List<FaceBox> detectFaces(BufferedImage image) throws Exception;

		@Override
		default void close() throws Exception {
		}
	}

	static record SemanticEvidence(
		List<DetectionBox> people,
		boolean bodyVisible,
		boolean anatomyValid) {

		@Override
		public String toString() {
			return "SemanticEvidence[personCount=" + people.size()
				+ ", bodyVisible=" + bodyVisible + ", anatomyValid=" + anatomyValid + "]";
		}
	}

	private record Artifact(String fileName, int size, String sha256) {
	}

	static record DetectionBox(float x1, float y1, float x2, float y2, float score) {

		float width() {
			return Math.max(0, x2 - x1);
		}

		float height() {
			return Math.max(0, y2 - y1);
		}

		float area() {
			return width() * height();
		}

		@Override
		public String toString() {
			return "DetectionBox[geometry]";
		}
	}

	static record FaceBox(float x1, float y1, float x2, float y2, float score) {

		@Override
		public String toString() {
			return "FaceBox[geometry]";
		}
	}

	private static final class ModelEngine implements Engine {

		private final OrtEnvironment environment;
		private final OrtSession yolox;
		private final OrtSession yunet;

		private ModelEngine(OrtEnvironment environment, OrtSession yolox, OrtSession yunet) {
			this.environment = environment;
			this.yolox = yolox;
			this.yunet = yunet;
		}

		static ModelEngine load() throws Exception {
			byte[] yoloxBytes = readVerifiedArtifact(YOLOX_ARTIFACT);
			byte[] yunetBytes = readVerifiedArtifact(YUNET_ARTIFACT);
			OrtEnvironment environment = OrtEnvironment.getEnvironment();
			OrtSession yolox = null;
			try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
				yolox = environment.createSession(yoloxBytes, options);
				OrtSession yunet = environment.createSession(yunetBytes, options);
				return new ModelEngine(environment, yolox, yunet);
			}
			catch (Exception | LinkageError exception) {
				if (yolox != null) {
					yolox.close();
				}
				throw exception;
			}
		}

		@Override
		public SemanticEvidence inspectCandidate(BufferedImage image) throws OrtException {
			List<DetectionBox> people = detectPeople(image);
			if (people.size() != 1) {
				return new SemanticEvidence(people, false, false);
			}
			DetectionBox person = people.get(0);
			boolean bodyVisible = person.height() / image.getHeight() >= MIN_BODY_HEIGHT_RATIO
				&& person.y2() / image.getHeight() >= MIN_BODY_BOTTOM_RATIO;
			boolean plausible = person.width() > 0 && person.height() > 0
				&& person.area() / ((float) image.getWidth() * image.getHeight()) > 0.01f;
			return new SemanticEvidence(people, bodyVisible, bodyVisible && plausible);
		}

		@Override
		public List<FaceBox> detectFaces(BufferedImage image) throws OrtException {
			YunetInput input = yunetInput(image);
			try (OnnxTensor tensor = OnnxTensor.createTensor(
				environment, input.data(), new long[] {1, 3, input.height(), input.width()});
				OrtSession.Result result = yunet.run(Map.of("input", tensor))) {
				Map<Integer, TensorRows> classifications = new HashMap<>();
				Map<Integer, TensorRows> objectness = new HashMap<>();
				Map<Integer, TensorRows> boxes = new HashMap<>();
				Map<Integer, TensorRows> landmarks = new HashMap<>();
				for (int stride : STRIDES) {
					classifications.put(stride, output(result, "cls_" + stride, 1));
					objectness.put(stride, output(result, "obj_" + stride, 1));
					boxes.put(stride, output(result, "bbox_" + stride, 4));
					landmarks.put(stride, output(result, "kps_" + stride, 10));
				}

				List<DetectionBox> candidates = new ArrayList<>();
				for (int stride : STRIDES) {
					int columns = input.width() / stride;
					int rows = input.height() / stride;
					TensorRows cls = classifications.get(stride);
					TensorRows obj = objectness.get(stride);
					TensorRows bbox = boxes.get(stride);
					TensorRows kps = landmarks.get(stride);
					if (cls.rows != columns * rows || obj.rows != cls.rows
						|| bbox.rows != cls.rows || kps.rows != cls.rows) {
						throw new IllegalStateException("unexpected model output");
					}
					for (int gridY = 0; gridY < rows; gridY++) {
						for (int gridX = 0; gridX < columns; gridX++) {
							int index = gridY * columns + gridX;
							float score = (float) Math.sqrt(
								clamp01(cls.get(index, 0)) * clamp01(obj.get(index, 0)));
							if (score < YUNET_CONFIDENCE || !Float.isFinite(score)) {
								continue;
							}
							float centerX = (gridX + bbox.get(index, 0)) * stride;
							float centerY = (gridY + bbox.get(index, 1)) * stride;
							float width = (float) Math.exp(bbox.get(index, 2)) * stride;
							float height = (float) Math.exp(bbox.get(index, 3)) * stride;
							if (!landmarksAreFinite(kps, index, gridX, gridY, stride)) {
								continue;
							}
							DetectionBox candidate = unletterbox(centerX, centerY, width, height, score,
								input.ratio(), image.getWidth(), image.getHeight());
							if (candidate.area() > 0) {
								candidates.add(candidate);
							}
						}
					}
				}

				List<DetectionBox> surviving = nonMaximumSuppression(candidates, YUNET_NMS_IOU);
				List<FaceBox> faces = new ArrayList<>(surviving.size());
				for (DetectionBox face : surviving) {
					faces.add(new FaceBox(face.x1(), face.y1(), face.x2(), face.y2(), face.score()));
				}
				return faces;
			}
		}

		private List<DetectionBox> detectPeople(BufferedImage image) throws OrtException {
			YoloxInput input = yoloxInput(image);
			try (OnnxTensor tensor = OnnxTensor.createTensor(
				environment, input.data(), new long[] {1, 3, YOLOX_SIZE, YOLOX_SIZE});
				OrtSession.Result result = yolox.run(Map.of("images", tensor))) {
				TensorRows output = output(result, "output", 85);
				if (output.rows != 8_400) {
					throw new IllegalStateException("unexpected model output");
				}
				List<DetectionBox> candidates = new ArrayList<>();
				int row = 0;
				for (int stride : STRIDES) {
					int gridSize = YOLOX_SIZE / stride;
					for (int gridY = 0; gridY < gridSize; gridY++) {
						for (int gridX = 0; gridX < gridSize; gridX++, row++) {
							float score = output.get(row, 4) * output.get(row, 5);
							if (score < YOLOX_CONFIDENCE || !Float.isFinite(score)) {
								continue;
							}
							float centerX = (output.get(row, 0) + gridX) * stride;
							float centerY = (output.get(row, 1) + gridY) * stride;
							float width = (float) Math.exp(output.get(row, 2)) * stride;
							float height = (float) Math.exp(output.get(row, 3)) * stride;
							DetectionBox box = unletterbox(centerX, centerY, width, height, score,
								input.ratio(), image.getWidth(), image.getHeight());
							if (box.area() > 0) {
								candidates.add(box);
							}
						}
					}
				}
				return nonMaximumSuppression(candidates, YOLOX_NMS_IOU);
			}
		}

		@Override
		public void close() throws OrtException {
			try {
				yunet.close();
			}
			finally {
				yolox.close();
			}
		}
	}

	private static byte[] readVerifiedArtifact(Artifact artifact) throws Exception {
		byte[] bytes;
		try (InputStream input = OnnxCpuQualityVision.class.getResourceAsStream(
			RESOURCE_ROOT + artifact.fileName())) {
			if (input == null) {
				throw new IOException("quality evaluator unavailable");
			}
			bytes = input.readAllBytes();
		}
		String sidecar;
		try (InputStream input = OnnxCpuQualityVision.class.getResourceAsStream(
			RESOURCE_ROOT + artifact.fileName() + ".sha256")) {
			if (input == null) {
				throw new IOException("quality evaluator unavailable");
			}
			sidecar = new String(input.readAllBytes(), StandardCharsets.US_ASCII).trim();
		}
		String expectedSidecar = artifact.sha256() + "  " + artifact.fileName();
		String actualHash = java.util.HexFormat.of().formatHex(
			MessageDigest.getInstance("SHA-256").digest(bytes));
		if (bytes.length != artifact.size()
			|| !artifact.sha256().equals(actualHash)
			|| !expectedSidecar.equals(sidecar)) {
			throw new IOException("quality evaluator unavailable");
		}
		return bytes;
	}

	private static YoloxInput yoloxInput(BufferedImage image) {
		float ratio = Math.min((float) YOLOX_SIZE / image.getWidth(), (float) YOLOX_SIZE / image.getHeight());
		int resizedWidth = Math.max(1, (int) (image.getWidth() * ratio));
		int resizedHeight = Math.max(1, (int) (image.getHeight() * ratio));
		BufferedImage letterboxed = new BufferedImage(YOLOX_SIZE, YOLOX_SIZE, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = letterboxed.createGraphics();
		try {
			graphics.setColor(new Color(114, 114, 114));
			graphics.fillRect(0, 0, YOLOX_SIZE, YOLOX_SIZE);
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			graphics.drawImage(image, 0, 0, resizedWidth, resizedHeight, null);
		}
		finally {
			graphics.dispose();
		}

		FloatBuffer data = directFloats(3 * YOLOX_SIZE * YOLOX_SIZE);
		int plane = YOLOX_SIZE * YOLOX_SIZE;
		for (int y = 0; y < YOLOX_SIZE; y++) {
			for (int x = 0; x < YOLOX_SIZE; x++) {
				int rgb = letterboxed.getRGB(x, y);
				int index = y * YOLOX_SIZE + x;
				data.put(index, (rgb >>> 16) & 0xff);
				data.put(plane + index, (rgb >>> 8) & 0xff);
				data.put(2 * plane + index, rgb & 0xff);
			}
		}
		return new YoloxInput(data, ratio);
	}

	private static YunetInput yunetInput(BufferedImage image) {
		float ratio = Math.min((float) YOLOX_SIZE / image.getWidth(), (float) YOLOX_SIZE / image.getHeight());
		int resizedWidth = Math.max(1, (int) (image.getWidth() * ratio));
		int resizedHeight = Math.max(1, (int) (image.getHeight() * ratio));
		BufferedImage padded = new BufferedImage(YOLOX_SIZE, YOLOX_SIZE, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = padded.createGraphics();
		try {
			graphics.setColor(Color.BLACK);
			graphics.fillRect(0, 0, YOLOX_SIZE, YOLOX_SIZE);
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			graphics.drawImage(image, 0, 0, resizedWidth, resizedHeight, null);
		}
		finally {
			graphics.dispose();
		}

		int plane = YOLOX_SIZE * YOLOX_SIZE;
		FloatBuffer data = directFloats(3 * plane);
		for (int y = 0; y < YOLOX_SIZE; y++) {
			for (int x = 0; x < YOLOX_SIZE; x++) {
				int rgb = padded.getRGB(x, y);
				int index = y * YOLOX_SIZE + x;
				data.put(index, rgb & 0xff);
				data.put(plane + index, (rgb >>> 8) & 0xff);
				data.put(2 * plane + index, (rgb >>> 16) & 0xff);
			}
		}
		return new YunetInput(data, YOLOX_SIZE, YOLOX_SIZE, ratio);
	}

	private static FloatBuffer directFloats(int count) {
		return ByteBuffer.allocateDirect(Math.multiplyExact(count, Float.BYTES))
			.order(ByteOrder.nativeOrder())
			.asFloatBuffer();
	}

	private static TensorRows output(OrtSession.Result result, String name, int columns) throws OrtException {
		OnnxValue value = result.get(name).orElseThrow(() -> new IllegalStateException("unexpected model output"));
		if (!(value instanceof OnnxTensor tensor)) {
			throw new IllegalStateException("unexpected model output");
		}
		TensorInfo info = tensor.getInfo();
		long[] shape = info.getShape();
		if (shape.length != 3 || shape[0] != 1 || shape[2] != columns
			|| shape[1] <= 0 || shape[1] > Integer.MAX_VALUE) {
			throw new IllegalStateException("unexpected model output");
		}
		FloatBuffer data = tensor.getFloatBuffer();
		if (data == null || data.remaining() != shape[1] * columns) {
			throw new IllegalStateException("unexpected model output");
		}
		return new TensorRows(data, (int) shape[1], columns);
	}

	private static DetectionBox unletterbox(
		float centerX,
		float centerY,
		float width,
		float height,
		float score,
		float ratio,
		int imageWidth,
		int imageHeight) {
		return clippedBox(centerX / ratio, centerY / ratio, width / ratio, height / ratio,
			score, imageWidth, imageHeight);
	}

	private static DetectionBox clippedBox(
		float centerX,
		float centerY,
		float width,
		float height,
		float score,
		int imageWidth,
		int imageHeight) {
		if (!Float.isFinite(centerX) || !Float.isFinite(centerY)
			|| !Float.isFinite(width) || !Float.isFinite(height) || width <= 0 || height <= 0) {
			return new DetectionBox(0, 0, 0, 0, score);
		}
		float x1 = clamp(centerX - width / 2, 0, imageWidth);
		float y1 = clamp(centerY - height / 2, 0, imageHeight);
		float x2 = clamp(centerX + width / 2, 0, imageWidth);
		float y2 = clamp(centerY + height / 2, 0, imageHeight);
		return new DetectionBox(x1, y1, x2, y2, score);
	}

	private static boolean landmarksAreFinite(
		TensorRows landmarks, int index, int gridX, int gridY, int stride) {
		for (int landmark = 0; landmark < 5; landmark++) {
			float x = (landmarks.get(index, landmark * 2) + gridX) * stride;
			float y = (landmarks.get(index, landmark * 2 + 1) + gridY) * stride;
			if (!Float.isFinite(x) || !Float.isFinite(y)) {
				return false;
			}
		}
		return true;
	}

	private static float clamp01(float value) {
		return clamp(value, 0, 1);
	}

	private static float clamp(float value, float minimum, float maximum) {
		return Math.max(minimum, Math.min(maximum, value));
	}

	private record YoloxInput(FloatBuffer data, float ratio) {
	}

	private record YunetInput(FloatBuffer data, int width, int height, float ratio) {
	}

	private static final class TensorRows {
		private final FloatBuffer data;
		private final int rows;
		private final int columns;

		private TensorRows(FloatBuffer data, int rows, int columns) {
			this.data = data;
			this.rows = rows;
			this.columns = columns;
		}

		private float get(int row, int column) {
			if (row < 0 || row >= rows || column < 0 || column >= columns) {
				throw new IllegalStateException("unexpected model output");
			}
			return data.get(row * columns + column);
		}
	}

	/** Cause-free local signal mapped to {@code Unavailable} by the gate. */
	public static final class UnavailableException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		public UnavailableException() {
			super("quality evaluator unavailable");
		}
	}
}
