package com.aifashionvault.platform.importer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenGraphHtmlTest {

	@Test
	void firstImageUsesOnlyItsAssociatedSecureUrl() {
		var metadata = OpenGraphHtml.parse("""
				<meta property="og:image" content="https://images.example.test/a.jpg">
				<meta property="og:image:secure_url" content="https://images.example.test/a-secure.jpg">
				<meta property="og:image" content="https://images.example.test/b.jpg">
				<meta property="og:image:secure_url" content="https://images.example.test/b-secure.jpg">
				""", "https://source.example.test/item");

		assertThat(metadata.ogImage()).isEqualTo("https://images.example.test/a-secure.jpg");
	}

	@Test
	void laterImageSecureUrlCannotDisplaceFirstImage() {
		var metadata = OpenGraphHtml.parse("""
				<meta property="og:image" content="https://images.example.test/a.jpg">
				<meta property="og:image" content="https://images.example.test/b.jpg">
				<meta property="og:image:secure_url" content="https://images.example.test/b-secure.jpg">
				""", "https://source.example.test/item");

		assertThat(metadata.ogImage()).isEqualTo("https://images.example.test/a.jpg");
	}

	@Test
	void ignoresSecureUrlBeforeRootAndBlankSecureUrlAfterRoot() {
		var metadata = OpenGraphHtml.parse("""
				<meta property="og:image:secure_url" content="https://images.example.test/unbound.jpg">
				<meta property="og:image" content="https://images.example.test/root.jpg">
				<meta property="og:image:secure_url" content="   ">
				""", "https://source.example.test/item");

		assertThat(metadata.ogImage()).isEqualTo("https://images.example.test/root.jpg");
	}

	@Test
	void acceptsImageUrlRootAliasRelativeUrlAndNameCompatibility() {
		var metadata = OpenGraphHtml.parse("""
				<meta name="og:image:url" content="/images/root.jpg">
				<meta name="og:image:secure_url" content="/images/root-secure.jpg">
				""", "https://source.example.test/item");

		assertThat(metadata.ogImage()).isEqualTo("/images/root-secure.jpg");
	}

	@Test
	void imageDoesNotRequireOtherOpenGraphMetadata() {
		var metadata = OpenGraphHtml.parse(
				"<meta property=\"og:image\" content=\"https://images.example.test/root.jpg\">",
				"https://source.example.test/item");

		assertThat(metadata.ogImage()).isEqualTo("https://images.example.test/root.jpg");
		assertThat(metadata.title()).isNull();
		assertThat(metadata.ogUrl()).isNull();
	}

	@Test
	void missingImageMetadataHasNoUsableMedia() {
		var metadata = OpenGraphHtml.parse(
				"<meta property=\"og:title\" content=\"Title only\">",
				"https://source.example.test/item");

		assertThat(metadata.ogImage()).isNull();
	}
}
