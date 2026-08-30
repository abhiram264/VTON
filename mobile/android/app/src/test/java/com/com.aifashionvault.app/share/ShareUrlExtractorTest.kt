package com.aifashionvault.app.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Acceptance criteria 2: URL extraction determinism and rejection rules. */
class ShareUrlExtractorTest {

  @Test
  fun `first valid textual URL is selected deterministically`() {
    val text =
        "Check this out https://example.com/products/1 and also http://other.example.org/x before ftp://nope.example.com"
    assertEquals("https://example.com/products/1", ShareUrlExtractor.extractFirstHttpUrl(text))
  }

  @Test
  fun `prose around url is ignored`() {
    assertEquals(
        "https://example.com/a",
        ShareUrlExtractor.extractFirstHttpUrl("  hey look at this https://example.com/a !!  "))
  }

  @Test
  fun `no http url rejects`() {
    assertNull(ShareUrlExtractor.extractFirstHttpUrl("just some words no link here"))
  }

  @Test
  fun `non-http scheme rejects`() {
    assertNull(ShareUrlExtractor.extractFirstHttpUrl("ftp://example.com/file"))
    assertNull(ShareUrlExtractor.extractFirstHttpUrl("javascript:alert(1)"))
    assertNull(ShareUrlExtractor.extractFirstHttpUrl("content://media/external/1"))
    assertNull(ShareUrlExtractor.extractFirstHttpUrl("file:///data/x.jpg"))
  }

  @Test
  fun `url with user-info credentials rejects`() {
    assertNull(ShareUrlExtractor.extractFirstHttpUrl("https://user:pass@example.com/p"))
    assertNull(ShareUrlExtractor.extractFirstHttpUrl("http://alice@example.com/"))
  }

  @Test
  fun `credentials in later url do not block earlier valid url`() {
    assertEquals(
        "https://good.example.com/1",
        ShareUrlExtractor.extractFirstHttpUrl("https://good.example.com/1 https://u:p@bad.com/2"))
  }

  @Test
  fun `relative or hostless tokens reject`() {
    assertNull(ShareUrlExtractor.extractFirstHttpUrl("http://"))
    assertNull(ShareUrlExtractor.extractFirstHttpUrl("/relative/path"))
    assertNull(ShareUrlExtractor.extractFirstHttpUrl("example.com/no-scheme"))
  }

  @Test
  fun `null and blank reject`() {
    assertNull(ShareUrlExtractor.extractFirstHttpUrl(null))
    assertNull(ShareUrlExtractor.extractFirstHttpUrl("   "))
  }

  @Test
  fun `http url with port and path accepted`() {
    assertEquals(
        "http://example.com:8080/a/b?c=d#e",
        ShareUrlExtractor.extractFirstHttpUrl("see http://example.com:8080/a/b?c=d#e thanks"))
  }
}
