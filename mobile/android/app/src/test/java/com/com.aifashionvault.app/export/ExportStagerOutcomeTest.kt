package com.aifashionvault.app.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The export bridge must never fetch a non-http target, never treat an undeclared or
 * non-image response as an image, and never emit a code that leaks a URL.
 */
class ExportStagerOutcomeTest {

  @Test
  fun onlyAbsoluteHttpAndHttpsTargetsAreFetchable() {
    assertTrue(ExportStagerOutcome.isFetchableUrl("https://cdn.example.com/look.jpg"))
    assertTrue(ExportStagerOutcome.isFetchableUrl("http://127.0.0.1:8080/look.png"))
    assertTrue(ExportStagerOutcome.isFetchableUrl("  https://cdn.example.com/look.jpg  "))
  }

  @Test
  fun localAndNonHttpSchemesAreRefused() {
    assertFalse(ExportStagerOutcome.isFetchableUrl("file:///etc/passwd"))
    assertFalse(ExportStagerOutcome.isFetchableUrl("content://media/external/images/1"))
    assertFalse(ExportStagerOutcome.isFetchableUrl("data:image/png;base64,AAAA"))
    assertFalse(ExportStagerOutcome.isFetchableUrl("ftp://host/x.png"))
    assertFalse(ExportStagerOutcome.isFetchableUrl("javascript:alert(1)"))
    assertFalse(ExportStagerOutcome.isFetchableUrl("//host/x.png"))
    assertFalse(ExportStagerOutcome.isFetchableUrl("https://"))
    assertFalse(ExportStagerOutcome.isFetchableUrl(null))
    assertFalse(ExportStagerOutcome.isFetchableUrl(""))
    assertFalse(ExportStagerOutcome.isFetchableUrl("   "))
  }

  @Test
  fun anUndeclaredContentTypeIsNotEvidenceOfAnImage() {
    assertFalse(ExportStagerOutcome.isImageMediaType(null))
    assertFalse(ExportStagerOutcome.isImageMediaType(""))
    assertFalse(ExportStagerOutcome.isImageMediaType("application/octet-stream"))
    assertFalse(ExportStagerOutcome.isImageMediaType("text/html"))
    assertFalse(ExportStagerOutcome.isImageMediaType("image/"))
  }

  @Test
  fun imageContentTypeIsAcceptedWithParametersStripped() {
    assertTrue(ExportStagerOutcome.isImageMediaType("image/jpeg"))
    assertTrue(ExportStagerOutcome.isImageMediaType("IMAGE/PNG"))
    assertTrue(ExportStagerOutcome.isImageMediaType("image/webp; charset=binary"))
    assertEquals("image/jpeg", ExportStagerOutcome.normalizeMediaType("Image/JPEG; charset=utf-8"))
    assertEquals("image/png", ExportStagerOutcome.normalizeMediaType("image/png"))
  }

  @Test
  fun saveOutcomeVocabularyIsExactlyWhatTheJsSeamSwitchesOn() {
    // mobile/src/features/export/lookExport.ts branches on these three literals.
    assertEquals("saved", ExportStagerOutcome.SAVE_SAVED)
    assertEquals("denied", ExportStagerOutcome.SAVE_DENIED)
    assertEquals("failed", ExportStagerOutcome.SAVE_FAILED)
  }

  @Test
  fun shareActionVocabularyMatchesTheReactNativeShareContract() {
    // lookExport.ts treats exactly 'dismissedAction' as a user cancel.
    assertEquals("sharedAction", ExportStagerOutcome.ACTION_SHARED)
    assertEquals("dismissedAction", ExportStagerOutcome.ACTION_DISMISSED)
  }

  @Test
  fun everyErrorCodeIsDistinctBoundedAndCarriesNoUrlFingerprint() {
    val codes =
        listOf(
            ExportStagerOutcome.ERROR_NOT_HTTP,
            ExportStagerOutcome.ERROR_DOWNLOAD_FAILED,
            ExportStagerOutcome.ERROR_TOO_LARGE,
            ExportStagerOutcome.ERROR_UNSUPPORTED_TYPE,
            ExportStagerOutcome.ERROR_NOT_STAGED,
            ExportStagerOutcome.ERROR_NO_ACTIVITY,
            ExportStagerOutcome.ERROR_NO_APP,
            ExportStagerOutcome.ERROR_SHARE_FAILED,
            ExportStagerOutcome.ERROR_BUSY,
        )
    assertEquals(codes.size, codes.toSet().size)
    codes.forEach { code ->
      assertTrue(code, code.startsWith("E_EXPORT_"))
      assertFalse(code, code.contains("http"))
      assertFalse(code, code.contains("/"))
    }
  }
}
