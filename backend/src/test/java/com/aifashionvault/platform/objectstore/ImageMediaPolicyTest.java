package com.aifashionvault.platform.objectstore;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageMediaPolicyTest {

	private static final long VALID_LENGTH = 1024L;

	@Nested
	class RequireDeclaration {

		@Test
		void jpegAndPngAreAccepted() {
			assertThat(ImageMediaPolicy.requireDeclaration("image/jpeg", VALID_LENGTH)).isEqualTo("image/jpeg");
			assertThat(ImageMediaPolicy.requireDeclaration("image/png", VALID_LENGTH)).isEqualTo("image/png");
		}

		@Test
		void uppercaseJpegIsAccepted() {
			assertThat(ImageMediaPolicy.requireDeclaration("IMAGE/JPEG", VALID_LENGTH)).isEqualTo("image/jpeg");
		}

		@Test
		void jpegWithCharsetParameterIsAcceptedAsJpeg() {
			assertThat(ImageMediaPolicy.requireDeclaration("image/jpeg;charset=UTF-8", VALID_LENGTH))
				.isEqualTo("image/jpeg");
		}

		@Test
		void unsupportedTypesAreRejected() {
			assertThatThrownBy(() -> ImageMediaPolicy.requireDeclaration("image/webp", VALID_LENGTH))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> ImageMediaPolicy.requireDeclaration("image/gif", VALID_LENGTH))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> ImageMediaPolicy.requireDeclaration("image/heic", VALID_LENGTH))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> ImageMediaPolicy.requireDeclaration("application/pdf", VALID_LENGTH))
				.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		void emptyAndNullContentTypeAreRejected() {
			assertThatThrownBy(() -> ImageMediaPolicy.requireDeclaration("", VALID_LENGTH))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> ImageMediaPolicy.requireDeclaration("   ", VALID_LENGTH))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> ImageMediaPolicy.requireDeclaration(null, VALID_LENGTH))
				.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		void zeroNegativeAndOversizeLengthsAreRejected() {
			assertThatThrownBy(() -> ImageMediaPolicy.requireDeclaration("image/jpeg", 0L))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> ImageMediaPolicy.requireDeclaration("image/jpeg", -1L))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> ImageMediaPolicy.requireDeclaration("image/jpeg", ImageMediaPolicy.MAX_BYTES + 1L))
				.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		void oneByteAndThirtyMibAreAccepted() {
			assertThat(ImageMediaPolicy.requireDeclaration("image/jpeg", ImageMediaPolicy.MIN_BYTES))
				.isEqualTo("image/jpeg");
			assertThat(ImageMediaPolicy.requireDeclaration("image/png", ImageMediaPolicy.MAX_BYTES))
				.isEqualTo("image/png");
		}
	}

	@Nested
	class RequireDimensions {

		@Test
		void fifteenByFifteenIsAccepted() {
			ImageMediaPolicy.requireDimensions(15, 15);
		}

		@Test
		void fourteenByFifteenAndFifteenByFourteenAreRejected() {
			assertThatThrownBy(() -> ImageMediaPolicy.requireDimensions(14, 15))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> ImageMediaPolicy.requireDimensions(15, 14))
				.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		void sideOf8193IsRejected() {
			assertThatThrownBy(() -> ImageMediaPolicy.requireDimensions(8193, 15))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> ImageMediaPolicy.requireDimensions(15, 8193))
				.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		void eightyOneNinetyTwoSquareIsRejectedForPixelCount() {
			assertThatThrownBy(() -> ImageMediaPolicy.requireDimensions(8192, 8192))
				.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		void eightThousandSquareIsAcceptedAtExactPixelCeiling() {
			ImageMediaPolicy.requireDimensions(8000, 8000);
		}
	}
}
