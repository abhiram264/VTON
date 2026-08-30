package com.aifashionvault.platform.importer;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Bounded HTML fetch + Open Graph head-metadata extraction. Only
 * {@code text/html} or {@code application/xhtml+xml} is accepted, parsing is
 * non-executing (jsoup never runs scripts or loads subresources), and the byte
 * budget is enforced while streaming. Metadata extraction is limited to
 * og:image / og:image:secure_url, og:title, og:site_name, canonical og:url,
 * and the ordinary title element as fallback.
 */
final class OpenGraphHtml {

	private OpenGraphHtml() {
	}

	static boolean isHtmlContentType(String contentType) {
		if (contentType == null || contentType.isBlank()) {
			return false;
		}
		String base = contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
		return base.equals("text/html") || base.equals("application/xhtml+xml");
	}

	static Charset charsetOf(String contentType) {
		if (contentType != null) {
			int idx = contentType.indexOf("charset=");
			if (idx >= 0) {
				String name = contentType.substring(idx + 8).split(";|\\s|\"", 2)[0].strip();
				if (!name.isEmpty()) {
					try {
						return Charset.forName(name);
					}
					catch (Exception ignored) {
						// fall through to UTF-8 default
					}
				}
			}
		}
		return StandardCharsets.UTF_8;
	}

	/** Parses bounded HTML text; never executes anything. */
	static Metadata parse(String html, String baseUri) {
		Document doc = Jsoup.parse(html == null ? "" : html, baseUri == null ? "" : baseUri);
		String ogImage = firstImage(doc);
		String title = metaContent(doc, "og:title");
		if (title == null || title.isBlank()) {
			Element el = doc.getElementsByTag("title").first();
			title = el == null ? null : el.text();
		}
		return new Metadata(
				blankToNull(ogImage),
				blankToNull(title),
				blankToNull(metaContent(doc, "og:site_name")),
				blankToNull(metaContent(doc, "og:url")));
	}

	private static String firstImage(Document doc) {
		String root = null;
		boolean foundRoot = false;
		for (Element meta : doc.select("meta[property], meta[name]")) {
			String property = meta.hasAttr("property") ? meta.attr("property") : meta.attr("name");
			if (property.equals("og:image") || property.equals("og:image:url")) {
				if (foundRoot) {
					break;
				}
				foundRoot = true;
				root = meta.attr("content");
			}
			else if (foundRoot && property.equals("og:image:secure_url")) {
				String secureUrl = meta.attr("content");
				if (!secureUrl.isBlank()) {
					return secureUrl;
				}
			}
		}
		return root;
	}

	private static String metaContent(Document doc, String property) {
		Element el = doc.selectFirst("meta[property=" + property + "]");
		if (el == null) {
			el = doc.selectFirst("meta[name=" + property + "]");
		}
		return el == null ? null : el.attr("content");
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	record Metadata(String ogImage, String title, String siteName, String ogUrl) {
	}
}
