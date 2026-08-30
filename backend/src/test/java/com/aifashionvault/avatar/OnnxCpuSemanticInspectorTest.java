package com.aifashionvault.avatar;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OnnxCpuSemanticInspectorTest {

	private static final String YOLOX = "object_detection_yolox_2022nov.onnx";
	private static final String YUNET = "face_detection_yunet_2023mar.onnx";

	@Test
	void constructionIsLazyAndFirstInspectionInitializesOnlyOnce() throws Exception {
		AtomicInteger loads = new AtomicInteger();
		OnnxCpuSemanticInspector inspector = new OnnxCpuSemanticInspector(() -> {
			loads.incrementAndGet();
			return image -> evidence(
				List.of(box(10, 0, 90, 180, 0.9f)),
				List.of(face(50, 30, 0.8f)));
		});

		assertThat(loads).hasValue(0);
		assertThat(inspector.inspect(imageBytes(100, 200)).personCountClass()).isEqualTo(PersonCountClass.ONE);
		assertThat(inspector.inspect(imageBytes(100, 200)).personCountClass()).isEqualTo(PersonCountClass.ONE);
		assertThat(loads).hasValue(1);
	}

	@Test
	void acceptsOneSufficientlyVisiblePersonWithFaceInsideThePersonBox() throws Exception {
		SemanticInspection inspection = inspect(evidence(
			List.of(box(10, 0, 90, 180, 0.9f)),
			List.of(face(50, 30, 0.8f))));

		assertThat(inspection.personCountClass()).isEqualTo(PersonCountClass.ONE);
		assertThat(inspection.faceVisible()).isTrue();
		assertThat(inspection.bodyVisible()).isTrue();
		assertThat(inspection.evaluatorVersion()).isEqualTo(OnnxCpuSemanticInspector.EVALUATOR_VERSION);
	}

	@Test
	void classifiesZeroPeopleWithoutFaceOrBodyEvidence() throws Exception {
		SemanticInspection inspection = inspect(evidence(List.of(), List.of()));

		assertThat(inspection.personCountClass()).isEqualTo(PersonCountClass.ZERO);
		assertThat(inspection.faceVisible()).isFalse();
		assertThat(inspection.bodyVisible()).isFalse();
	}

	@Test
	void classifiesMultiplePeopleWithoutSelectingAPrimarySubject() throws Exception {
		SemanticInspection inspection = inspect(evidence(
			List.of(box(0, 0, 40, 180, 0.9f), box(60, 0, 100, 180, 0.8f)),
			List.of(face(20, 30, 0.8f), face(80, 30, 0.8f))));

		assertThat(inspection.personCountClass()).isEqualTo(PersonCountClass.MULTIPLE);
		assertThat(inspection.faceVisible()).isFalse();
		assertThat(inspection.bodyVisible()).isFalse();
	}

	@Test
	void requiresFaceCenterToBeInsideTheSinglePersonBox() throws Exception {
		SemanticInspection inspection = inspect(evidence(
			List.of(box(10, 0, 90, 180, 0.9f)),
			List.of(face(95, 30, 0.8f))));

		assertThat(inspection.personCountClass()).isEqualTo(PersonCountClass.ONE);
		assertThat(inspection.faceVisible()).isFalse();
		assertThat(inspection.bodyVisible()).isTrue();
	}

	@Test
	void rejectsGeometricallyCroppedBodyAtEitherBoundary() throws Exception {
		SemanticInspection tooShort = inspect(evidence(
			List.of(box(10, 0, 90, 99, 0.9f)),
			List.of(face(50, 30, 0.8f))));
		SemanticInspection bottomTooHigh = inspect(evidence(
			List.of(box(10, 0, 90, 119, 0.9f)),
			List.of(face(50, 30, 0.8f))));
		SemanticInspection exactBoundary = inspect(evidence(
			List.of(box(10, 20, 90, 120, 0.9f)),
			List.of(face(50, 30, 0.8f))));

		assertThat(tooShort.bodyVisible()).isFalse();
		assertThat(bottomTooHigh.bodyVisible()).isFalse();
		assertThat(exactBoundary.bodyVisible()).isTrue();
	}

	@Test
	void appliesConfidenceOrderedNmsAtTheFrozenIouThreshold() {
		List<OnnxCpuSemanticInspector.Box> surviving = OnnxCpuSemanticInspector.nonMaximumSuppression(
			List.of(
				box(0, 0, 100, 100, 0.9f),
				box(10, 10, 110, 110, 0.8f),
				box(200, 200, 300, 300, 0.7f)),
			0.5f);

		assertThat(surviving).hasSize(2);
		assertThat(surviving.get(0).score).isEqualTo(0.9f);
		assertThat(surviving.get(1).score).isEqualTo(0.7f);
	}

	@Test
	void loadAndInferenceFailuresExposeOnlyCauseFreeUnavailable() throws Exception {
		OnnxCpuSemanticInspector loadFailure = new OnnxCpuSemanticInspector(() -> {
			throw new IllegalStateException("https://private.example/model?signature=secret");
		});
		OnnxCpuSemanticInspector inferenceFailure = new OnnxCpuSemanticInspector(() -> image -> {
			throw new IllegalStateException("tensor=[landmark=12.34]");
		});

		assertUnavailable(loadFailure);
		assertUnavailable(inferenceFailure);
	}

	@Test
	void resultAndInternalEvidenceStringsDoNotExposeDetectorOutputs() throws Exception {
		OnnxCpuSemanticInspector.Evidence evidence = evidence(
			List.of(box(10, 0, 90, 180, 0.912345f)),
			List.of(face(50, 30, 0.823456f)));
		SemanticInspection inspection = inspect(evidence);

		assertThat(inspection.toString())
			.doesNotContain("0.912345", "0.823456", "bbox", "landmark", "tensor", "http://", "https://");
		assertThat(evidence.toString())
			.doesNotContain("0.912345", "0.823456", "bbox", "landmark", "tensor");
	}

	@Test
	void vendoredModelsAndSidecarsHaveFrozenBytes() throws Exception {
		assertArtifact(YOLOX, 35_858_002, "c5c2d13e59ae883e6af3b45daea64af4833a4951c92d116ec270d9ddbe998063");
		assertArtifact(YUNET, 232_589, "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4");
	}

	@Test
	void realCpuRuntimeLoadsChecksummedModelsAndRunsInference() throws Exception {
		try (OnnxCpuSemanticInspector inspector = new OnnxCpuSemanticInspector()) {
			SemanticInspection inspection = inspector.inspect(imageBytes(64, 64));

			assertThat(inspection.personCountClass()).isEqualTo(PersonCountClass.ZERO);
			assertThat(inspection.faceVisible()).isFalse();
			assertThat(inspection.bodyVisible()).isFalse();
			assertThat(inspection.evaluatorVersion()).isEqualTo(OnnxCpuSemanticInspector.EVALUATOR_VERSION);
		}
	}

	@Test
	void publicDomainRealImageRemainsAOnePersonNoFaceRegression() throws Exception {
		try (OnnxCpuSemanticInspector inspector = new OnnxCpuSemanticInspector()) {
			SemanticInspection inspection = inspector.inspect(corpusImageBytes());

			assertThat(inspection.personCountClass()).isEqualTo(PersonCountClass.ONE);
			assertThat(inspection.faceVisible()).isFalse();
			assertThat(inspection.bodyVisible()).isTrue();
		}
	}

	private static SemanticInspection inspect(OnnxCpuSemanticInspector.Evidence evidence) throws Exception {
		try (OnnxCpuSemanticInspector inspector = new OnnxCpuSemanticInspector(() -> image -> evidence)) {
			return inspector.inspect(imageBytes(100, 200));
		}
	}

	private static void assertUnavailable(OnnxCpuSemanticInspector inspector) throws Exception {
		assertThatThrownBy(() -> inspector.inspect(imageBytes(100, 200)))
			.isExactlyInstanceOf(SemanticInspectionUnavailableException.class)
			.hasMessage("unavailable")
			.hasNoCause();
	}

	private static OnnxCpuSemanticInspector.Evidence evidence(
		List<OnnxCpuSemanticInspector.Box> people,
		List<OnnxCpuSemanticInspector.FaceCenter> faces) {
		return new OnnxCpuSemanticInspector.Evidence(people, faces);
	}

	private static OnnxCpuSemanticInspector.Box box(
		float x1, float y1, float x2, float y2, float score) {
		return new OnnxCpuSemanticInspector.Box(x1, y1, x2, y2, score);
	}

	private static OnnxCpuSemanticInspector.FaceCenter face(float x, float y, float score) {
		return new OnnxCpuSemanticInspector.FaceCenter(x, y, score);
	}

	private static byte[] imageBytes(int width, int height) throws Exception {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = image.createGraphics();
		try {
			graphics.setColor(Color.BLACK);
			graphics.fillRect(0, 0, width, height);
		} finally {
			graphics.dispose();
		}
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		assertThat(ImageIO.write(image, "png", output)).isTrue();
		return output.toByteArray();
	}

	private static byte[] corpusImageBytes() throws Exception {
		BufferedImage source;
		try (InputStream input = OnnxCpuSemanticInspectorTest.class.getResourceAsStream(
			"/avatar/preflight/corpus/fog_surrounding_standing_man.jpg")) {
			assertThat(input).isNotNull();
			source = ImageIO.read(input);
		}
		BufferedImage resized = new BufferedImage(768, 512, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = resized.createGraphics();
		try {
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			graphics.drawImage(source, 0, 0, resized.getWidth(), resized.getHeight(), null);
		} finally {
			graphics.dispose();
		}
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		assertThat(ImageIO.write(resized, "jpg", output)).isTrue();
		return output.toByteArray();
	}

	private static void assertArtifact(String fileName, int expectedSize, String expectedHash) throws Exception {
		byte[] model;
		try (InputStream input = resource(fileName)) {
			model = input.readAllBytes();
		}
		String sidecar;
		try (InputStream input = resource(fileName + ".sha256")) {
			sidecar = new String(input.readAllBytes(), StandardCharsets.US_ASCII).trim();
		}

		assertThat(model).hasSize(expectedSize);
		assertThat(hex(MessageDigest.getInstance("SHA-256").digest(model))).isEqualTo(expectedHash);
		assertThat(sidecar).isEqualTo(expectedHash + "  " + fileName);
		assertThat(new String(model, 0, Math.min(model.length, 100), StandardCharsets.US_ASCII))
			.doesNotStartWith("version https://git-lfs.github.com/spec/v1");
	}

	private static InputStream resource(String fileName) {
		InputStream input = OnnxCpuSemanticInspectorTest.class.getResourceAsStream("/avatar/preflight/" + fileName);
		assertThat(input).as(fileName).isNotNull();
		return input;
	}

	private static String hex(byte[] bytes) {
		return java.util.HexFormat.of().formatHex(bytes);
	}
}
