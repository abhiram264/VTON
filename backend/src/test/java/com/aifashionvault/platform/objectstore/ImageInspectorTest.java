package com.aifashionvault.platform.objectstore;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageInspectorTest {

	private final ImageInspector inspector = new ImageInspector();

	@Test
	void inspectsFifteenByFifteenJpegWithoutDecodingARaster() throws Exception {
		byte[] jpeg = raster("JPEG", 15, 15);

		ImageInspector.Result result = inspector.inspect(jpeg);

		assertThat(result.mediaType()).isEqualTo(ImageMediaType.JPEG);
		assertThat(result.width()).isEqualTo(15);
		assertThat(result.height()).isEqualTo(15);
		assertThat(jpeg[0]).isEqualTo((byte) 0xFF);
		assertThat(jpeg[1]).isEqualTo((byte) 0xD8);
		assertThat(jpeg[2]).isEqualTo((byte) 0xFF);
	}

	@Test
	void inspectsFifteenByFifteenPngWithoutDecodingARaster() throws Exception {
		byte[] png = raster("PNG", 16, 20);

		ImageInspector.Result result = inspector.inspect(png);

		assertThat(result.mediaType()).isEqualTo(ImageMediaType.PNG);
		assertThat(result.width()).isEqualTo(16);
		assertThat(result.height()).isEqualTo(20);
		assertThat(png).startsWith(
			(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
	}

	@Test
	void rejectsRandomBytes() {
		assertThatThrownBy(() -> inspector.inspect("not-an-image".getBytes(StandardCharsets.US_ASCII)))
			.isInstanceOf(ObjectStoreExceptions.ImageValidationException.class);
		assertThatThrownBy(() -> inspector.inspect(new byte[0]))
			.isInstanceOf(ObjectStoreExceptions.ImageValidationException.class);
		assertThatThrownBy(() -> inspector.inspect(null))
			.isInstanceOf(ObjectStoreExceptions.ImageValidationException.class);
	}

	@Test
	void rejectsOneByOneJpegAndPng() throws Exception {
		assertThatThrownBy(() -> inspector.inspect(raster("JPEG", 1, 1)))
			.isInstanceOf(ObjectStoreExceptions.ImageValidationException.class);
		assertThatThrownBy(() -> inspector.inspect(raster("PNG", 1, 1)))
			.isInstanceOf(ObjectStoreExceptions.ImageValidationException.class);
	}

	@Test
	void rejectsHeaderBombDeclaredDimensionsWithoutFullDecode() throws Exception {
		byte[] bomb = pngDeclaringDimensions(8193, 15);

		assertThatThrownBy(() -> inspector.inspect(bomb))
			.isInstanceOf(ObjectStoreExceptions.ImageValidationException.class);
	}

	@Test
	void productionInspectorDoesNotCallImageIoReadOrAllocateARaster() throws Exception {
		String source = Files.readString(imageInspectorSource(), StandardCharsets.UTF_8);

		assertThat(source).doesNotContain("ImageIO.read");
		assertThat(source).doesNotContain("BufferedImage");
		assertThat(source).contains("getImageReadersByFormatName");
		assertThat(source).contains("getWidth");
		assertThat(source).contains("getHeight");
	}

	private static byte[] raster(String format, int width, int height) throws IOException {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = image.createGraphics();
		graphics.setColor(Color.BLUE);
		graphics.fillRect(0, 0, width, height);
		graphics.dispose();
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
		byte[] png = raster("PNG", 16, 16);
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

	private static Path imageInspectorSource() {
		Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
		Path current = cwd;
		while (current != null) {
			Path direct = current.resolve(Path.of(
				"src", "main", "java", "com", "aifashionvault", "platform", "objectstore", "ImageInspector.java"));
			if (Files.isRegularFile(direct)) {
				return direct;
			}
			Path underBackend = current.resolve("backend").resolve(Path.of(
				"src", "main", "java", "com", "aifashionvault", "platform", "objectstore", "ImageInspector.java"));
			if (Files.isRegularFile(underBackend)) {
				return underBackend;
			}
			current = current.getParent();
		}
		throw new IllegalStateException("Cannot locate ImageInspector.java from " + cwd);
	}
}
