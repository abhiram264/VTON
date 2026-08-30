package com.aifashionvault.avatar;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import org.junit.jupiter.api.Test;

import com.aifashionvault.platform.objectstore.ImageInspector;
import com.aifashionvault.platform.objectstore.ImageInspectorAvatarBytesInspector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class AvatarPreflightTest {

	private static final SemanticInspection ACCEPTED =
		new SemanticInspection(PersonCountClass.ONE, true, true, "test-evaluator");

	private final RecordingSemanticInspector semanticInspector = new RecordingSemanticInspector();
	private final AvatarPreflight preflight = new AvatarPreflight(
		new ImageInspectorAvatarBytesInspector(new ImageInspector()),
		semanticInspector);

	@Test
	void exposesOnlyTheFrozenTwoPortConstructor() {
		assertThat(AvatarPreflight.class.getDeclaredConstructors())
			.singleElement()
			.satisfies(constructor -> assertThat(constructor.getParameterTypes())
				.containsExactly(AvatarBytesInspector.class, SemanticInspector.class));
	}

	@Test
	void inspectsReadableJpegAndPersistsOnlyOpaquePolicyVersion() throws Exception {
		AvatarPreflightResult result = preflight.inspect(textured("JPEG", 512, 768));

		assertThat(result.mediaType()).isEqualTo(AvatarMediaType.JPEG);
		assertThat(result.width()).isEqualTo(512);
		assertThat(result.height()).isEqualTo(768);
		assertThat(result.policyVersion()).isEqualTo("avatar-preflight-v1");
		assertThat(result.policyVersion()).isNotEqualTo(ACCEPTED.evaluatorVersion());
		assertThat(result.warnings()).isNotNull();
		assertThat(result.toString())
			.doesNotContain("test-evaluator")
			.doesNotContain("http")
			.doesNotContain("https")
			.doesNotContain("0x")
			.doesNotContain("byte[")
			.doesNotContain("FF D8");
	}

	@Test
	void inspectsReadableLandscapePngAtTheShortSideFloor() throws Exception {
		AvatarPreflightResult result = preflight.inspect(textured("PNG", 768, 512));

		assertThat(result.mediaType()).isEqualTo(AvatarMediaType.PNG);
		assertThat(result.width()).isEqualTo(768);
		assertThat(result.height()).isEqualTo(512);
		assertThat(semanticInspector.calls).isEqualTo(1);
	}

	@Test
	void requiresNonblankPolicyVersionInResult() {
		assertThatThrownBy(() -> new AvatarPreflightResult(AvatarMediaType.JPEG, 512, 512, List.of(), null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("policyVersion is required");
		assertThatThrownBy(() -> new AvatarPreflightResult(AvatarMediaType.JPEG, 512, 512, List.of(), " "))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("policyVersion is required");
	}

	@Test
	void rejectsFifteenPixelsAndBoth511ShortSideOrientationsBeforeSemanticInspection() throws Exception {
		assertProblem(textured("JPEG", 15, 15), AvatarProblemCode.AVATAR_RESOLUTION_INSUFFICIENT);
		assertProblem(textured("JPEG", 511, 768), AvatarProblemCode.AVATAR_RESOLUTION_INSUFFICIENT);
		assertProblem(textured("JPEG", 768, 511), AvatarProblemCode.AVATAR_RESOLUTION_INSUFFICIENT);

		assertThat(semanticInspector.calls).isZero();
	}

	@Test
	void rejectsRandomBytesAsUnreadableBeforeSemanticInspection() {
		assertUnreadable("not-an-image".getBytes(StandardCharsets.US_ASCII));
		assertUnreadable(new byte[0]);
		assertUnreadable(null);

		assertThat(semanticInspector.calls).isZero();
	}

	@Test
	void preservesAspectRatioPrecedenceOverAvatarResolution() throws Exception {
		assertProblem(textured("JPEG", 100, 15), AvatarProblemCode.AVATAR_ASPECT_RATIO);
		assertThat(semanticInspector.calls).isZero();
	}

	@Test
	void rejectsHardDarkAndHardBlurBeforeSemanticInspection() throws Exception {
		assertProblem(raster("PNG", 512, 512, Color.BLACK), AvatarProblemCode.AVATAR_TOO_DARK);
		assertProblem(raster("PNG", 512, 512, new Color(128, 128, 128)), AvatarProblemCode.AVATAR_TOO_BLURRED);

		assertThat(semanticInspector.calls).isZero();
	}

	@Test
	void rejectsZeroAndMultiplePeopleWithTheSinglePersonCode() throws Exception {
		semanticInspector.inspection = new SemanticInspection(PersonCountClass.ZERO, false, false, "zero");
		assertProblem(textured("JPEG", 512, 512), AvatarProblemCode.AVATAR_PERSON_NOT_SINGLE);

		semanticInspector.inspection = new SemanticInspection(PersonCountClass.MULTIPLE, true, true, "multiple");
		assertProblem(textured("JPEG", 512, 512), AvatarProblemCode.AVATAR_PERSON_NOT_SINGLE);
	}

	@Test
	void rejectsInvisibleFaceBeforeInvisibleBody() throws Exception {
		semanticInspector.inspection = new SemanticInspection(PersonCountClass.ONE, false, false, "face");

		assertProblem(textured("JPEG", 512, 512), AvatarProblemCode.AVATAR_FACE_NOT_VISIBLE);
	}

	@Test
	void rejectsInvisibleBody() throws Exception {
		semanticInspector.inspection = new SemanticInspection(PersonCountClass.ONE, true, false, "body");

		assertProblem(textured("JPEG", 512, 512), AvatarProblemCode.AVATAR_BODY_NOT_VISIBLE);
	}

	@Test
	void mapsOnlySemanticInfrastructureFailureToPreflightUnavailable() throws Exception {
		semanticInspector.unavailable = true;

		assertProblem(textured("JPEG", 512, 512), AvatarProblemCode.AVATAR_PREFLIGHT_UNAVAILABLE);
	}

	@Test
	void addsSoftWarningsAfterSemanticAcceptanceInFrozenOrder() throws Exception {
		AvatarPreflightResult result = preflight.inspect(verticalStep("PNG", 512, 512, 20, 40));

		assertThat(result.warnings()).containsExactly(
			AvatarProblemCode.AVATAR_TOO_DARK.name(),
			AvatarProblemCode.AVATAR_TOO_BLURRED.name());
		assertThat(semanticInspector.calls).isEqualTo(1);
		assertThatThrownBy(() -> result.warnings().add("extra"))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void exceptionMessageIsOnlyCodeName() {
		AvatarException exception = catchThrowableOfType(
			AvatarException.class,
			() -> preflight.inspect("not-an-image".getBytes(StandardCharsets.US_ASCII)));

		assertThat(exception.code()).isEqualTo(AvatarProblemCode.AVATAR_UNREADABLE);
		assertThat(exception.getMessage()).isEqualTo(AvatarProblemCode.AVATAR_UNREADABLE.name());
		assertThat(exception.getMessage()).doesNotContain("unknown", "malformed", "http", "0x");
		assertThat(exception.toString()).doesNotContain("unknown or malformed");
	}

	@Test
	void mapsOversizedSideAndPixelCountWithoutSemanticInspection() throws Exception {
		assertProblem(pngDeclaringDimensions(8193, 512), AvatarProblemCode.AVATAR_DIMENSIONS_EXCEEDED);
		assertProblem(pngDeclaringDimensions(8001, 8001), AvatarProblemCode.AVATAR_PIXELS_EXCEEDED);
		assertThat(semanticInspector.calls).isZero();
	}

	private void assertUnreadable(byte[] bytes) {
		assertProblem(bytes, AvatarProblemCode.AVATAR_UNREADABLE);
	}

	private void assertProblem(byte[] bytes, AvatarProblemCode code) {
		AvatarException exception = catchThrowableOfType(AvatarException.class, () -> preflight.inspect(bytes));
		assertThat(exception.code()).isEqualTo(code);
		assertThat(exception.getMessage()).isEqualTo(code.name());
	}

	private static byte[] textured(String format, int width, int height) throws IOException {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int light = ((x / 32) + (y / 32)) % 2 == 0 ? 220 : 40;
				image.setRGB(x, y, rgb(light, light, light));
			}
		}
		return encode(image, format);
	}

	private static byte[] verticalStep(
		String format,
		int width,
		int height,
		int leftTone,
		int rightTone
	) throws IOException {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int tone = x < width / 2 ? leftTone : rightTone;
				image.setRGB(x, y, rgb(tone, tone, tone));
			}
		}
		return encode(image, format);
	}

	private static byte[] raster(String format, int width, int height, Color fill) throws IOException {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = image.createGraphics();
		graphics.setColor(fill);
		graphics.fillRect(0, 0, width, height);
		graphics.dispose();
		return encode(image, format);
	}

	private static byte[] encode(BufferedImage image, String format) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(format);
		if (!writers.hasNext()) {
			throw new IllegalStateException("no ImageWriter for " + format);
		}
		ImageWriter writer = writers.next();
		try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
			writer.setOutput(output);
			writer.write(image);
		}
		finally {
			writer.dispose();
		}
		return bytes.toByteArray();
	}

	private static byte[] pngDeclaringDimensions(int width, int height) throws IOException {
		byte[] png = raster("PNG", 16, 16, Color.BLUE);
		int ihdrTypeOffset = 12;
		int widthOffset = 16;
		png[widthOffset] = (byte) (width >>> 24);
		png[widthOffset + 1] = (byte) (width >>> 16);
		png[widthOffset + 2] = (byte) (width >>> 8);
		png[widthOffset + 3] = (byte) width;
		png[widthOffset + 4] = (byte) (height >>> 24);
		png[widthOffset + 5] = (byte) (height >>> 16);
		png[widthOffset + 6] = (byte) (height >>> 8);
		png[widthOffset + 7] = (byte) height;
		CRC32 crc = new CRC32();
		crc.update(png, ihdrTypeOffset, 17);
		int crcValue = (int) crc.getValue();
		int crcOffset = 29;
		png[crcOffset] = (byte) (crcValue >>> 24);
		png[crcOffset + 1] = (byte) (crcValue >>> 16);
		png[crcOffset + 2] = (byte) (crcValue >>> 8);
		png[crcOffset + 3] = (byte) crcValue;
		return png;
	}

	private static int rgb(int r, int g, int b) {
		return (r << 16) | (g << 8) | b;
	}

	private static final class RecordingSemanticInspector implements SemanticInspector {

		private SemanticInspection inspection = ACCEPTED;
		private boolean unavailable;
		private int calls;

		@Override
		public SemanticInspection inspect(byte[] imageBytes) throws SemanticInspectionUnavailableException {
			calls++;
			if (unavailable) {
				throw new SemanticInspectionUnavailableException();
			}
			return inspection;
		}
	}
}
