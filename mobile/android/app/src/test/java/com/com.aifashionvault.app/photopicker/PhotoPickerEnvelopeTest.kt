package com.aifashionvault.app.photopicker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The frozen JS-facing result envelope. `photoSource.ts` distinguishes a dismissal
 * (`cancelled`), a denied camera permission (`permissionDenied`) and a real selection
 * (`uri`); the three outcomes must stay mutually exclusive so a bridge bug can never be
 * read as a cancellation or an empty photo.
 */
class PhotoPickerEnvelopeTest {

  @Test
  fun `cancelled carries only the cancelled flag`() {
    val envelope = PhotoPickerEnvelope.cancelled()
    assertEquals(true, envelope["cancelled"])
    assertFalse(envelope.containsKey("uri"))
    assertFalse(envelope.containsKey("permissionDenied"))
  }

  @Test
  fun `picked carries the staged uri and no denial flag`() {
    val envelope = PhotoPickerEnvelope.picked("file:/data/user/0/app/cache/photo-picker/abc")
    assertEquals(false, envelope["cancelled"])
    assertEquals("file:/data/user/0/app/cache/photo-picker/abc", envelope["uri"])
    assertFalse(envelope.containsKey("permissionDenied"))
  }

  @Test
  fun `permission denied is not a cancellation and carries no uri`() {
    val envelope = PhotoPickerEnvelope.permissionDenied()
    assertEquals(false, envelope["cancelled"])
    assertEquals(true, envelope["permissionDenied"])
    assertFalse(envelope.containsKey("uri"))
  }
}
