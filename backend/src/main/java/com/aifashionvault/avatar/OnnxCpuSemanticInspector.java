package com.aifashionvault.avatar;

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
 * In-process CPU semantic inspection backed by the frozen Avatar Preflight model bundle.
 * Model sessions are initialized on the first inspection, never by the constructor.
 */
public final class OnnxCpuSemanticInspector implements SemanticInspector, AutoCloseable {

	static final String EVALUATOR_VERSION = "avsem-4aa8fe43a5a63f96";

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

	private final EngineFactory engineFactory;
	private final Object initializationLock = new Object();
	private volatile Engine engine;
	private volatile boolean closed;

	public OnnxCpuSemanticInspector() {
		this(ModelEngine::load);
	}

	OnnxCpuSemanticInspector(EngineFactory engineFactory) {
		if (engineFactory == null) {
			throw new IllegalArgumentException("engineFactory is required");
		}
		this.engineFactory = engineFactory;
	}

	@Override
	public SemanticInspection inspect(byte[] imageBytes) throws SemanticInspectionUnavailableException {
		try {
			BufferedImage image = decodeImage(imageBytes);
			Evidence evidence = engine().inspect(image);
			return decide(image.getWidth(), image.getHeight(), evidence);
		} catch (SemanticInspectionUnavailableException exception) {
			throw exception;
		} catch (Exception | LinkageError exception) {
			throw new SemanticInspectionUnavailableException();
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
			} catch (Exception ignored) {
				// Shutdown cannot turn a completed inspection into an application failure.
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
				throw new IllegalStateException("closed");
			}
			if (engine == null) {
				engine = engineFactory.create();
			}
			return engine;
		}
	}

	private static BufferedImage decodeImage(byte[] imageBytes) throws IOException {
		if (imageBytes == null || imageBytes.length == 0) {
			throw new IOException("image unavailable");
		}
		BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
		if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
			throw new IOException("image unavailable");
		}
		return image;
	}

	private static SemanticInspection decide(int imageWidth, int imageHeight, Evidence evidence) {
		if (evidence == null || evidence.people == null || evidence.faces == null) {
			throw new IllegalStateException("inference unavailable");
		}
		int people = evidence.people.size();
		PersonCountClass count = people == 0
			? PersonCountClass.ZERO
			: people == 1 ? PersonCountClass.ONE : PersonCountClass.MULTIPLE;
		if (count != PersonCountClass.ONE) {
			return new SemanticInspection(count, false, false, EVALUATOR_VERSION);
		}

		Box person = evidence.people.get(0);
		boolean faceVisible = evidence.faces.stream().anyMatch(face -> person.contains(face.x, face.y));
		boolean bodyVisible = person.height() / imageHeight >= MIN_BODY_HEIGHT_RATIO
			&& person.y2 / imageHeight >= MIN_BODY_BOTTOM_RATIO;
		return new SemanticInspection(count, faceVisible, bodyVisible, EVALUATOR_VERSION);
	}

	static List<Box> nonMaximumSuppression(List<Box> candidates, float iouThreshold) {
		List<Box> ordered = new ArrayList<>(candidates);
		ordered.sort(Comparator.comparingDouble((Box box) -> box.score).reversed());
		List<Box> surviving = new ArrayList<>(ordered.size());
		for (Box candidate : ordered) {
			boolean suppressed = false;
			for (Box selected : surviving) {
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

	private static float intersectionOverUnion(Box left, Box right) {
		float intersectionWidth = Math.max(0, Math.min(left.x2, right.x2) - Math.max(left.x1, right.x1));
		float intersectionHeight = Math.max(0, Math.min(left.y2, right.y2) - Math.max(left.y1, right.y1));
		float intersection = intersectionWidth * intersectionHeight;
		float union = left.area() + right.area() - intersection;
		return union <= 0 ? 0 : intersection / union;
	}

	@FunctionalInterface
	interface EngineFactory {
		Engine create() throws Exception;
	}

	@FunctionalInterface
	interface Engine extends AutoCloseable {
		Evidence inspect(BufferedImage image) throws Exception;

		@Override
		default void close() throws Exception {
		}
	}

	static final class Evidence {
		final List<Box> people;
		final List<FaceCenter> faces;

		Evidence(List<Box> people, List<FaceCenter> faces) {
			this.people = people;
			this.faces = faces;
		}
	}

	static final class Box {
		final float x1;
		final float y1;
		final float x2;
		final float y2;
		final float score;

		Box(float x1, float y1, float x2, float y2, float score) {
			this.x1 = x1;
			this.y1 = y1;
			this.x2 = x2;
			this.y2 = y2;
			this.score = score;
		}

		float width() {
			return Math.max(0, x2 - x1);
		}

		float height() {
			return Math.max(0, y2 - y1);
		}

		float area() {
			return width() * height();
		}

		boolean contains(float x, float y) {
			return x >= x1 && x <= x2 && y >= y1 && y <= y2;
		}
	}

	static final class FaceCenter {
		final float x;
		final float y;
		final float score;

		FaceCenter(float x, float y, float score) {
			this.x = x;
			this.y = y;
			this.score = score;
		}
	}

	private record Artifact(String fileName, int size, String sha256) {
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
			} catch (Exception | LinkageError exception) {
				if (yolox != null) {
					yolox.close();
				}
				throw exception;
			}
		}

		@Override
		public Evidence inspect(BufferedImage image) throws OrtException {
			List<Box> people = detectPeople(image);
			if (people.size() != 1) {
				return new Evidence(people, List.of());
			}
			return new Evidence(people, detectFaces(image));
		}

		private List<Box> detectPeople(BufferedImage image) throws OrtException {
			YoloxInput input = yoloxInput(image);
			try (OnnxTensor tensor = OnnxTensor.createTensor(
				environment, input.data, new long[] {1, 3, YOLOX_SIZE, YOLOX_SIZE});
				OrtSession.Result result = yolox.run(Map.of("images", tensor))) {
				TensorRows output = output(result, "output", 85);
				if (output.rows != 8_400) {
					throw new IllegalStateException("unexpected model output");
				}
				List<Box> candidates = new ArrayList<>();
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
							Box box = unletterbox(centerX, centerY, width, height, score,
								input.ratio, image.getWidth(), image.getHeight());
							if (box.area() > 0) {
								candidates.add(box);
							}
						}
					}
				}
				return nonMaximumSuppression(candidates, YOLOX_NMS_IOU);
			}
		}

		private List<FaceCenter> detectFaces(BufferedImage image) throws OrtException {
			YunetInput input = yunetInput(image);
			try (OnnxTensor tensor = OnnxTensor.createTensor(
				environment, input.data, new long[] {1, 3, input.height, input.width});
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

				List<Box> candidates = new ArrayList<>();
				for (int stride : STRIDES) {
					int columns = input.width / stride;
					int rows = input.height / stride;
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
							Box candidate = unletterbox(centerX, centerY, width, height, score,
								input.ratio, image.getWidth(), image.getHeight());
							if (candidate.area() > 0) {
								candidates.add(candidate);
							}
						}
					}
				}

				List<Box> surviving = nonMaximumSuppression(candidates, YUNET_NMS_IOU);
				List<FaceCenter> centers = new ArrayList<>(surviving.size());
				for (Box face : surviving) {
					centers.add(new FaceCenter((face.x1 + face.x2) / 2, (face.y1 + face.y2) / 2, face.score));
				}
				return centers;
			}
		}

		@Override
		public void close() throws OrtException {
			try {
				yunet.close();
			} finally {
				yolox.close();
			}
		}
	}

	private static byte[] readVerifiedArtifact(Artifact artifact) throws Exception {
		byte[] bytes;
		try (InputStream input = OnnxCpuSemanticInspector.class.getResourceAsStream(
			RESOURCE_ROOT + artifact.fileName())) {
			if (input == null) {
				throw new IOException("model unavailable");
			}
			bytes = input.readAllBytes();
		}
		String sidecar;
		try (InputStream input = OnnxCpuSemanticInspector.class.getResourceAsStream(
			RESOURCE_ROOT + artifact.fileName() + ".sha256")) {
			if (input == null) {
				throw new IOException("model unavailable");
			}
			sidecar = new String(input.readAllBytes(), StandardCharsets.US_ASCII).trim();
		}
		String expectedSidecar = artifact.sha256() + "  " + artifact.fileName();
		String actualHash = java.util.HexFormat.of().formatHex(
			MessageDigest.getInstance("SHA-256").digest(bytes));
		if (bytes.length != artifact.size()
			|| !artifact.sha256().equals(actualHash)
			|| !expectedSidecar.equals(sidecar)) {
			throw new IOException("model unavailable");
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
		} finally {
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
		} finally {
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

	private static Box unletterbox(
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

	private static Box clippedBox(
		float centerX,
		float centerY,
		float width,
		float height,
		float score,
		int imageWidth,
		int imageHeight) {
		if (!Float.isFinite(centerX) || !Float.isFinite(centerY)
			|| !Float.isFinite(width) || !Float.isFinite(height) || width <= 0 || height <= 0) {
			return new Box(0, 0, 0, 0, score);
		}
		float x1 = clamp(centerX - width / 2, 0, imageWidth);
		float y1 = clamp(centerY - height / 2, 0, imageHeight);
		float x2 = clamp(centerX + width / 2, 0, imageWidth);
		float y2 = clamp(centerY + height / 2, 0, imageHeight);
		return new Box(x1, y1, x2, y2, score);
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
}
