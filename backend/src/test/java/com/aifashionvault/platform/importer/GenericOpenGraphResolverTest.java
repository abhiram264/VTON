package com.aifashionvault.platform.importer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifashionvault.importer.ImportResolverLimits;
import com.aifashionvault.importer.ResolutionReason;
import com.aifashionvault.importer.ResolutionStatus;
import com.aifashionvault.importer.SourceReference;
import com.aifashionvault.platform.objectstore.ImageInspector;
import com.aifashionvault.shared.ShareImportId;
import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenericOpenGraphResolverTest {

	private static final UserId OWNER = new UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
	private static final ShareImportId IMPORT_ID =
			new ShareImportId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
	private static final String ATTEMPT = "attempt-1";
	private static final String HOST = "source.example.test";
	private static final URI PAGE = URI.create("https://" + HOST + "/item");
	private static final URI IMAGE = URI.create("https://img.example.test/photo.jpg");

	private final Fakes.FakeTransport transport = new Fakes.FakeTransport();
	private final Fakes.FakeDns dns = new Fakes.FakeDns();
	private final Fakes.FakeMediaAccess media = new Fakes.FakeMediaAccess();
	private final MutableClock clock = new MutableClock(Instant.parse("2026-08-30T10:00:00Z"));

	private static final class MutableClock implements GenericOpenGraphResolver.Clock {

		Instant now;

		MutableClock(Instant start) {
			this.now = start;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}

	private GenericOpenGraphResolver resolver() {
		return new GenericOpenGraphResolver(
				transport, dns, new ImageInspector(), media, OWNER, IMPORT_ID, ATTEMPT, clock);
	}

	private static String page(String ogImage, String title, String siteName) {
		StringBuilder html = new StringBuilder("<!DOCTYPE html><html><head><meta charset=\"utf-8\">");
		if (ogImage != null) {
			html.append("<meta property=\"og:image\" content=\"").append(ogImage).append("\">");
		}
		if (title != null) {
			html.append("<meta property=\"og:title\" content=\"").append(title).append("\">");
		}
		if (siteName != null) {
			html.append("<meta property=\"og:site_name\" content=\"").append(siteName).append("\">");
		}
		html.append("<title>fallback</title></head><body>ok</body></html>");
		return html.toString();
	}

	/** Synthetic 16x16 PNG generated in-memory; no fixture files, valid by construction. */
	static byte[] png16() {
		java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(16, 16,
				java.awt.image.BufferedImage.TYPE_INT_RGB);
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		try {
			javax.imageio.ImageIO.write(image, "png", out);
		}
		catch (java.io.IOException ex) {
			throw new IllegalStateException(ex);
		}
		return out.toByteArray();
	}

	static String htmlWithImageAt(URI imageUri) {
		return "<!DOCTYPE html><html><head>"
				+ "<meta property=\"og:image\" content=\"" + imageUri + "\">"
				+ "<meta property=\"og:title\" content=\"Test Title\">"
				+ "<meta property=\"og:site_name\" content=\"Test Site\">"
				+ "</head><body>ok</body></html>";
	}

	private void serveHappyPath() {
		dns.answerDefault(HOST);
		dns.answerDefault("img.example.test");
		transport.hops.put(PAGE.toString(), Fakes.FakeTransport.serve(200, "text/html", htmlWithImageAt(IMAGE)));
		transport.hops.put(IMAGE.toString(), imageHop());
	}

	private static Fakes.FakeTransport.Hop imageHop() {
		return new ImageHop(200, png16());
	}

	private static final class ImageHop extends Fakes.FakeTransport.Hop {

		ImageHop(int status, byte[] body) {
			super(status, "image/png", null, null, null);
			this.bytes = body;
		}
	}

	private SourceReference urlSource() {
		return new SourceReference(PAGE, null);
	}

	// --- Acceptance 5: happy path ---

	@Test
	void validOpenGraphWithSafeImageResolvesReadyAndStoresPrivately() {
		serveHappyPath();

		var result = resolver().resolve(urlSource());

		assertThat(result.status()).isEqualTo(ResolutionStatus.RESOLVED);
		assertThat(result.storageKey()).isNotNull();
		assertThat(Fakes.keyText(result.storageKey()))
			.contains(OWNER.value().toString())
			.contains(IMPORT_ID.value().toString())
			.contains(ATTEMPT);
		assertThat(media.published).hasSize(1);
		assertThat(media.publishedTypes).containsExactly("image/png"); // inspected type, not the declared one
		assertThat(result.title()).isEqualTo("Test Title");
		assertThat(result.siteName()).isEqualTo("Test Site");
		assertThat(result.canonicalSourceUrl()).isEqualTo(PAGE);
	}

	@Test
	void firstRootAssociatedSecureImageIsFetchedAndPublished() {
		URI firstSecure = URI.create("https://img.example.test/a-secure.jpg");
		dns.answerDefault(HOST);
		dns.answerDefault("img.example.test");
		transport.hops.put(PAGE.toString(), Fakes.FakeTransport.serve(200, "text/html", """
				<meta property="og:image" content="https://img.example.test/a.jpg">
				<meta property="og:image:secure_url" content="https://img.example.test/a-secure.jpg">
				<meta property="og:image" content="https://img.example.test/b.jpg">
				<meta property="og:image:secure_url" content="https://img.example.test/b-secure.jpg">
				"""));
		transport.hops.put(firstSecure.toString(), imageHop());

		var result = resolver().resolve(urlSource());

		assertThat(result.status()).isEqualTo(ResolutionStatus.RESOLVED);
		assertThat(transport.requested).containsExactly(PAGE, firstSecure);
		assertThat(media.published).hasSize(1);
	}

	@Test
	void relativeNameImageAliasResolvesWithoutOtherOpenGraphMetadata() {
		URI relativeImage = PAGE.resolve("/photo.jpg");
		dns.answerDefault(HOST);
		transport.hops.put(PAGE.toString(), Fakes.FakeTransport.serve(200, "text/html",
				"<meta name=\"og:image:url\" content=\"/photo.jpg\">"));
		transport.hops.put(relativeImage.toString(), imageHop());

		var result = resolver().resolve(urlSource());

		assertThat(result.status()).isEqualTo(ResolutionStatus.RESOLVED);
		assertThat(transport.requested).containsExactly(PAGE, relativeImage);
		assertThat(result.title()).isNull();
		assertThat(media.published).hasSize(1);
	}

	// --- Acceptance 6: NEEDS_IMAGE fall-through cases ---

	@Test
	void missingOpenGraphImageFallsThroughUnsupported() {
		dns.answerDefault(HOST);
		transport.hops.put(PAGE.toString(),
				Fakes.FakeTransport.serve(200, "text/html", page(null, "t", "s")));

		assertThat(resolver().resolve(urlSource()).status()).isEqualTo(ResolutionStatus.UNSUPPORTED);
		assertThat(media.published).isEmpty();
	}

	@Test
	void nonHtmlContentTypeFallsThroughUnsupported() {
		dns.answerDefault(HOST);
		transport.hops.put(PAGE.toString(), Fakes.FakeTransport.serve(200, "application/json", "{}"));

		assertThat(resolver().resolve(urlSource()).status()).isEqualTo(ResolutionStatus.UNSUPPORTED);
	}

	@Test
	void unsafeImageHostFallsThroughUnsupportedWithoutFetchingIt() {
		dns.answerDefault(HOST);
		transport.hops.put(PAGE.toString(), Fakes.FakeTransport.serve(200, "text/html",
				htmlWithImageAt(URI.create("https://internal.example.test/pic.jpg"))));
		dns.answer("internal.example.test", Fakes.address("10.1.2.3"));

		assertThat(resolver().resolve(urlSource()).status()).isEqualTo(ResolutionStatus.UNSUPPORTED);
		assertThat(transport.requested).hasSize(1); // only the page, never the image
	}

	@Test
	void nonImageBodyFallsThroughUnsupported() {
		dns.answerDefault(HOST);
		dns.answerDefault("img.example.test");
		transport.hops.put(PAGE.toString(), Fakes.FakeTransport.serve(200, "text/html", htmlWithImageAt(IMAGE)));
		transport.hops.put(IMAGE.toString(), new ImageHop(200, "not an image".getBytes()));

		assertThat(resolver().resolve(urlSource()).status()).isEqualTo(ResolutionStatus.UNSUPPORTED);
		assertThat(media.published).isEmpty();
	}

	@Test
	void pngBytesServedAsNonImageContentTypeDeclineAndFallThroughToGenericUrl() {
		dns.answerDefault(HOST);
		dns.answerDefault("img.example.test");
		transport.hops.put(PAGE.toString(), Fakes.FakeTransport.serve(200, "text/html", htmlWithImageAt(IMAGE)));
		Fakes.FakeTransport.Hop lyingType = new Fakes.FakeTransport.Hop(200, "text/html", null, null, null);
		lyingType.bytes = png16();
		transport.hops.put(IMAGE.toString(), lyingType);

		assertThat(resolver().resolve(urlSource()).status()).isEqualTo(ResolutionStatus.UNSUPPORTED);
		assertThat(media.published).isEmpty();

		var chain = new com.aifashionvault.importer.ImportResolver(java.util.List.of(
				resolver(), new GenericUrlResolver()));
		assertThat(chain.resolve(urlSource()).status()).isEqualTo(ResolutionStatus.NEEDS_IMAGE);
	}

	@Test
	void image404FallsThroughUnsupported() {
		dns.answerDefault(HOST);
		dns.answerDefault("img.example.test");
		transport.hops.put(PAGE.toString(), Fakes.FakeTransport.serve(200, "text/html", htmlWithImageAt(IMAGE)));
		transport.hops.put(IMAGE.toString(), Fakes.FakeTransport.serve(404, "text/plain", "gone"));

		assertThat(resolver().resolve(urlSource()).status()).isEqualTo(ResolutionStatus.UNSUPPORTED);
	}

	@Test
	void oversizedHtmlFallsThroughUnsupported() {
		dns.answerDefault(HOST);
		transport.hops.put(PAGE.toString(), Fakes.FakeTransport.serve(200, "text/html",
				"x".repeat((int) ImportResolverLimits.MAX_HTML_BYTES + 1)));

		assertThat(resolver().resolve(urlSource()).status()).isEqualTo(ResolutionStatus.UNSUPPORTED);
	}

	@Test
	void oversizedImageFallsThroughUnsupported() {
		dns.answerDefault(HOST);
		dns.answerDefault("img.example.test");
		transport.hops.put(PAGE.toString(), Fakes.FakeTransport.serve(200, "text/html", htmlWithImageAt(IMAGE)));
		transport.hops.put(IMAGE.toString(), new OversizedImageHop(ImportResolverLimits.MAX_IMAGE_BYTES + 1));

		assertThat(resolver().resolve(urlSource()).status()).isEqualTo(ResolutionStatus.UNSUPPORTED);
		assertThat(media.published).isEmpty();
	}

	private static final class OversizedImageHop extends Fakes.FakeTransport.Hop {

		OversizedImageHop(long declaredLength) {
			super(200, "image/png", null, null, null);
			this.declaredLength = declaredLength;
		}
	}

	// --- Acceptance 7: retryable classification ---

	@Test
	void dnsFailureIsRetryableDependencyUnavailable() {
		dns.failAll = true;

		assertThatThrownBy(() -> resolver().resolve(urlSource()))
			.isInstanceOf(ImporterRetryableException.class)
			.extracting(Fakes::retryableReason)
			.isEqualTo(ResolutionReason.DEPENDENCY_UNAVAILABLE);
	}

	@Test
	void timeoutIsRetryableResolutionTimeout() {
		dns.answerDefault(HOST);
		transport.hops.put(PAGE.toString(),
				Fakes.FakeTransport.fail(ImportHttpFetcher.TransportFailure.TIMEOUT, "timed out"));

		assertThatThrownBy(() -> resolver().resolve(urlSource()))
			.isInstanceOf(ImporterRetryableException.class)
			.extracting(Fakes::retryableReason)
			.isEqualTo(ResolutionReason.RESOLUTION_TIMEOUT);
	}

	@Test
	void remote5xxIsRetryableDependencyUnavailable() {
		dns.answerDefault(HOST);
		transport.hops.put(PAGE.toString(), Fakes.FakeTransport.serve(503, "text/plain", "down"));

		assertThatThrownBy(() -> resolver().resolve(urlSource()))
			.isInstanceOf(ImporterRetryableException.class)
			.extracting(Fakes::retryableReason)
			.isEqualTo(ResolutionReason.DEPENDENCY_UNAVAILABLE);
	}

	@Test
	void imageRemote5xxIsRetryableDependencyUnavailable() {
		dns.answerDefault(HOST);
		dns.answerDefault("img.example.test");
		transport.hops.put(PAGE.toString(), Fakes.FakeTransport.serve(200, "text/html", htmlWithImageAt(IMAGE)));
		transport.hops.put(IMAGE.toString(), Fakes.FakeTransport.serve(500, "text/plain", "down"));

		assertThatThrownBy(() -> resolver().resolve(urlSource()))
			.isInstanceOf(ImporterRetryableException.class)
			.extracting(Fakes::retryableReason)
			.isEqualTo(ResolutionReason.DEPENDENCY_UNAVAILABLE);
	}

	@Test
	void objectStoreUnavailableWhilePublishingIsRetryableAndCleansUp() {
		serveHappyPath();
		media.unavailable = true;

		assertThatThrownBy(() -> resolver().resolve(urlSource()))
			.isInstanceOf(ImporterRetryableException.class)
			.extracting(Fakes::retryableReason)
			.isEqualTo(ResolutionReason.DEPENDENCY_UNAVAILABLE);
		assertThat(media.deleted).hasSize(1);
	}

	// --- Acceptance 8: SSRF matrix ---

	@Test
	void privateAddressInAnyDnsRecordIsBlockedBeforeFetch() {
		dns.answerDefault(HOST);
		dns.answer(HOST, Fakes.publicV4(), Fakes.address("192.168.1.10"));

		assertThatThrownBy(() -> new BoundedFetch(transport, dns).fetch(PAGE, 1024, null))
			.extracting(ex -> ((ImportNetworkException) ex).kind())
			.isEqualTo(FailureKind.UNSAFE_URL);
		assertThat(transport.requested).isEmpty();
	}

	@Test
	void blockedAddressLiteralsNeverReachTheTransport() {
		String[] ipv4 = {"127.0.0.1", "10.0.0.5", "172.16.0.1", "192.168.0.1", "169.254.169.254", "0.0.0.0",
				"100.64.0.1", "198.18.0.1", "192.0.2.1", "198.51.100.7", "203.0.113.9", "224.0.0.1",
				"255.255.255.255"};
		String[] ipv6 = {"::1", "fd00::1", "fe80::1", "ff02::1", "::ffff:10.0.0.1", "2001:db8::1"};
		for (String literal : ipv4) {
			SafeHttpUri.requireSafe(URI.create("http://" + literal + "/x"));
			assertThatThrownBy(() -> dns.requireRoutable(Fakes.address(literal)))
				.as("literal %s must be blocked", literal)
				.isInstanceOf(ImportNetworkException.class);
		}
		for (String literal : ipv6) {
			SafeHttpUri.requireSafe(URI.create("http://[" + literal + "]/x"));
			assertThatThrownBy(() -> dns.requireRoutable(Fakes.address(literal)))
				.as("literal %s must be blocked", literal)
				.isInstanceOf(ImportNetworkException.class);
		}
	}

	@Test
	void unsafeSchemesPortsAndUserinfoAreRejectedBeforeAnyFetch() {
		for (String raw : new String[] {
				"file:///etc/passwd", "gopher://host/x", "ftp://host/f", "https://user:pw@host/x",
				"https://host:8080/x", "http://host:8443/x", "https://host/x#frag", "relative/path"}) {
			assertThatThrownBy(() -> SafeHttpUri.requireSafe(SafeHttpUri.parse(raw)))
				.as("uri %s must be rejected", raw.getClass())
				.isInstanceOf(ImportNetworkException.class);
		}
		assertThat(transport.requested).isEmpty();
	}

	// --- Acceptance 9: redirect matrix ---

	@Test
	void oneAndThreeRedirectsAreFollowedWithRevalidation() {
		dns.answerDefault(HOST);
		dns.answerDefault("hop.example.test");
		URI hop1 = URI.create("https://hop.example.test/1");
		URI hop2 = URI.create("https://hop.example.test/2");
		URI hop3 = URI.create("https://hop.example.test/3");
		transport.hops.put(PAGE.toString(), Fakes.FakeTransport.redirect("https://hop.example.test/1"));
		transport.hops.put(hop1.toString(), Fakes.FakeTransport.redirect("https://hop.example.test/2"));
		transport.hops.put(hop2.toString(), Fakes.FakeTransport.redirect("/3"));
		transport.hops.put(hop3.toString(), Fakes.FakeTransport.serve(200, "text/html", page(null, "t", null)));

		resolver().resolve(urlSource());

		assertThat(transport.requested).hasSize(4);
		assertThat(dns.lookedUp).contains("hop.example.test");
	}

	@Test
	void fourthRedirectTerminatesSafelyAsUnsupported() {
		dns.answerDefault(HOST);
		dns.answerDefault("hop.example.test");
		URI hop1 = URI.create("https://hop.example.test/1");
		URI hop2 = URI.create("https://hop.example.test/2");
		URI hop3 = URI.create("https://hop.example.test/3");
		URI hop4 = URI.create("https://hop.example.test/4");
		transport.hops.put(PAGE.toString(), Fakes.FakeTransport.redirect("https://hop.example.test/1"));
		transport.hops.put(hop1.toString(), Fakes.FakeTransport.redirect("/2"));
		transport.hops.put(hop2.toString(), Fakes.FakeTransport.redirect("/3"));
		transport.hops.put(hop3.toString(), Fakes.FakeTransport.redirect("/4"));
		transport.hops.put(hop4.toString(), Fakes.FakeTransport.serve(200, "text/html", page(null, "t", null)));

		assertThat(resolver().resolve(urlSource()).status()).isEqualTo(ResolutionStatus.UNSUPPORTED);
		assertThat(transport.requested).hasSize(4); // initial + 3 hops, the 4th never fetched
	}

	@Test
	void malformedRedirectLocationTerminatesSafely() {
		dns.answerDefault(HOST);
		transport.hops.put(PAGE.toString(), new Fakes.FakeTransport.Hop(302, null, "::::", null, null));

		assertThat(resolver().resolve(urlSource()).status()).isEqualTo(ResolutionStatus.UNSUPPORTED);
	}

	@Test
	void redirectWithoutLocationTerminatesSafely() {
		dns.answerDefault(HOST);
		transport.hops.put(PAGE.toString(), new Fakes.FakeTransport.Hop(302, null, null, null, null));

		assertThat(resolver().resolve(urlSource()).status()).isEqualTo(ResolutionStatus.UNSUPPORTED);
	}

	@Test
	void redirectToPrivateDestinationIsBlockedAtDns() {
		dns.answerDefault(HOST);
		dns.answer("hop.example.test", Fakes.address("127.0.0.1"));
		transport.hops.put(PAGE.toString(),
				Fakes.FakeTransport.redirect("https://hop.example.test/private"));

		assertThat(resolver().resolve(urlSource()).status()).isEqualTo(ResolutionStatus.UNSUPPORTED);
		assertThat(transport.requested).hasSize(1);
	}

	// --- Acceptance 10: budget exhaustion ---

	@Test
	void exhaustedTotalBudgetIsRetryableResolutionTimeout() {
		dns.answerDefault(HOST);
		transport.hops.put(PAGE.toString(), Fakes.FakeTransport.serve(200, "text/html", page(null, "t", null)));
		clock.now = java.time.Instant.now().minus(ImportResolverLimits.TOTAL_RESOLUTION_TIMEOUT).minusSeconds(1);

		assertThatThrownBy(() -> resolver().resolve(urlSource()))
			.isInstanceOf(ImporterRetryableException.class)
			.extracting(Fakes::retryableReason)
			.isEqualTo(ResolutionReason.RESOLUTION_TIMEOUT);
	}

	// --- Acceptance 4 adjunct: resolver precedence in chain ---

	@Test
	void openGraphWinsBeforeGenericUrlForUrlOnlySource() {
		serveHappyPath();
		var chain = new com.aifashionvault.importer.ImportResolver(java.util.List.of(
				resolver(), new GenericUrlResolver()));

		var result = chain.resolve(urlSource());

		assertThat(result.status()).isEqualTo(ResolutionStatus.RESOLVED);
	}

	@Test
	void openGraphUnsupportedFallsThroughToGenericUrlNeedsImage() {
		dns.answerDefault(HOST);
		transport.hops.put(PAGE.toString(), Fakes.FakeTransport.serve(200, "text/html", page(null, "t", null)));
		var chain = new com.aifashionvault.importer.ImportResolver(java.util.List.of(
				resolver(), new GenericUrlResolver()));

		var result = chain.resolve(urlSource());

		assertThat(result.status()).isEqualTo(ResolutionStatus.NEEDS_IMAGE);
		assertThat(result.reason()).isEqualTo(ResolutionReason.URL_RESOLUTION_FAILED);
	}
}
