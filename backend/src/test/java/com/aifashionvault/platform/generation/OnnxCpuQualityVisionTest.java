package com.aifashionvault.platform.generation;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.security.MessageDigest;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OnnxCpuQualityVisionTest {

	private static final String YOLOX = "object_detection_yolox_2022nov.onnx";
	private static final String YUNET = "face_detection_yunet_2023mar.onnx";

	@Test
	void qualityVisionEvidenceKeepsScoresBoundedAndRejectsMalformedValues() {
		QualityVisionEvidence evidence = new QualityVisionEvidence(
			1,
			true,
			new BigDecimal("0.80"),
			new BigDecimal("1.00"),
			new BigDecimal("0"),
			true,
			false,
			true);

		assertThat(evidence.identityPreservation()).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
		assertThat(evidence.garmentFidelity()).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
		assertThat(evidence.colorFidelity()).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
		assertThatThrownBy(() -> new QualityVisionEvidence(
			1, true, new BigDecimal("1.01"), BigDecimal.ONE, BigDecimal.ONE, true, false, true))
			.isInstanceOf(IllegalArgumentException.class);
	}
	@Test
	void constructionIsLazyAndSuccessfulEngineIsInitializedOnlyOnce() {
		AtomicInteger loads = new AtomicInteger();
		try (OnnxCpuQualityVision vision = new OnnxCpuQualityVision(() -> {
			loads.incrementAndGet();
			return new OnnxCpuQualityVision.Engine() {
				@Override
				public OnnxCpuQualityVision.SemanticEvidence inspectCandidate(BufferedImage image) {
					return new OnnxCpuQualityVision.SemanticEvidence(
						List.of(new OnnxCpuQualityVision.DetectionBox(10, 0, 90, 180, 0.9f)),
						true,
						true);
				}

				@Override
				public List<OnnxCpuQualityVision.FaceBox> detectFaces(BufferedImage image) {
					return List.of(new OnnxCpuQualityVision.FaceBox(40, 20, 60, 60, 0.9f));
				}
			};
		})) {
			assertThat(loads).hasValue(0);
			QualityVisionEvidence first = vision.inspect(
				imageBytes(100, 200), imageBytes(100, 200), imageBytes(100, 200), null);
			QualityVisionEvidence second = vision.inspect(
				imageBytes(100, 200), imageBytes(100, 200), imageBytes(100, 200), null);

			assertThat(loads).hasValue(1);
			assertThat(first).isEqualTo(second);
			assertThat(first.personCount()).isEqualTo(1);
			assertThat(first.identityPreservation()).isEqualByComparingTo(BigDecimal.ONE);
		}
	}


	@Test
	void loadAndInferenceFailuresExposeOnlyCauseFreeUnavailable() {
		OnnxCpuQualityVision vision = new OnnxCpuQualityVision(() -> {
			throw new IllegalStateException("https://private.example/model?signature=secret");
		});

		assertThatThrownBy(() -> vision.inspect(imageBytes(64, 64), imageBytes(64, 64), imageBytes(64, 64), null))
			.isExactlyInstanceOf(OnnxCpuQualityVision.UnavailableException.class)
			.hasMessage("quality evaluator unavailable")
			.hasNoCause();
	}

	@Test
	void realCpuRuntimeLoadsChecksummedModelsAndProducesBoundedEvidence() {
		try (OnnxCpuQualityVision vision = new OnnxCpuQualityVision()) {
			QualityVisionEvidence evidence = vision.inspect(
				imageBytes(64, 64), imageBytes(64, 64), imageBytes(64, 64), null);

			assertThat(evidence.personCount()).isGreaterThanOrEqualTo(0);
			assertThat(evidence.identityPreservation()).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
			assertThat(evidence.garmentFidelity()).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
			assertThat(evidence.colorFidelity()).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
			assertThat(evidence.previousItemsPreserved()).isTrue();
		}
	}

	@Test
	void vendoredModelsAndSidecarsHaveFrozenBytes() throws Exception {
		assertArtifact(YOLOX, 35_858_002,
			"c5c2d13e59ae883e6af3b45daea64af4833a4951c92d116ec270d9ddbe998063");
		assertArtifact(YUNET, 232_589,
			"8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4");
	}

	private static byte[] imageBytes(int width, int height) {
		try {
			BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
			Graphics2D graphics = image.createGraphics();
			try {
				graphics.setColor(Color.BLACK);
				graphics.fillRect(0, 0, width, height);
			}
			finally {
				graphics.dispose();
			}
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			assertThat(ImageIO.write(image, "png", output)).isTrue();
			return output.toByteArray();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
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
		assertThat(java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(model)))
			.isEqualTo(expectedHash);
		assertThat(sidecar).isEqualTo(expectedHash + "  " + fileName);
		assertThat(new String(model, 0, Math.min(model.length, 100), StandardCharsets.US_ASCII))
			.doesNotStartWith("version https://git-lfs.github.com/spec/v1");
	}

	private static InputStream resource(String fileName) {
		InputStream input = OnnxCpuQualityVisionTest.class.getResourceAsStream("/avatar/preflight/" + fileName);
		assertThat(input).as(fileName).isNotNull();
		return input;
	}
}
