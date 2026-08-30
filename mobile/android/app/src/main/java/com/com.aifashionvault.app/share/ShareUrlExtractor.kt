package com.aifashionvault.app.share

import java.net.URI

/**
 * Extracts the first syntactically valid absolute credential-free http/https URL in textual order
 * from arbitrary shared prose. Pure logic; no network access, no canonicalization (resolver owns
 * that downstream).
 */
object ShareUrlExtractor {

  /** Candidate tokens are whitespace-delimited; URLs cannot contain unencoded whitespace. */
  fun extractFirstHttpUrl(text: CharSequence?): String? {
    if (text == null) return null
    val trimmed = text.toString().trim()
    if (trimmed.isEmpty()) return null
    for (token in trimmed.split(Regex("\\s+"))) {
      val url = parseUrl(token) ?: continue
      return url
    }
    return null
  }

  /** Returns the canonical URL string, or null when the token is not an acceptable URL. */
  fun parseUrl(token: String): String? {
    val trimmed = token.trim()
    if (trimmed.isEmpty()) return null
    val uri =
        try {
          URI(trimmed)
        } catch (_: Exception) {
          return null
        }
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    if (uri.host.isNullOrBlank()) return null
    if (uri.userInfo != null) return null // reject credentials in URL
    if (uri.port != -1 && (uri.port < 1 || uri.port > 65535)) return null
    return uri.toString()
  }
}
