package com.aifashionvault.app.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Acceptance criteria 9 (and 2/16 input-shape rules): ambiguity and multiplicity rejection. */
class ShareInputResolverTest {

  private val send = ShareInputResolver.ACTION_SEND

  private fun inputs(
      action: String? = send,
      text: CharSequence? = null,
      streamUri: String? = null,
      clipUris: List<String> = emptyList(),
      clipItemCount: Int = clipUris.size,
  ) = ShareInputResolver.Inputs(action, text, streamUri, clipUris, clipItemCount)

  @Test
  fun `non-send action rejects`() {
    val result = ShareInputResolver.resolve(inputs(action = "android.intent.action.SEND_MULTIPLE"))
    assertEquals(ShareInputResolver.Reason.NOT_SEND_ACTION, (result as ShareInputResolver.Resolution.Rejected).reason)
    assertEquals(
        ShareInputResolver.Reason.NOT_SEND_ACTION,
        (ShareInputResolver.resolve(inputs(action = "android.intent.action.VIEW"))
                as ShareInputResolver.Resolution.Rejected)
            .reason)
  }

  @Test
  fun `text-only with url accepts url payload`() {
    val result = ShareInputResolver.resolve(inputs(text = "look https://example.com/p/1"))
    val accepted = result as ShareInputResolver.Resolution.Accepted
    assertEquals("https://example.com/p/1", accepted.url)
    assertEquals(null, accepted.imageUri)
  }

  @Test
  fun `image-only accepts image payload`() {
    val result =
        ShareInputResolver.resolve(inputs(streamUri = "content://media/external/images/1"))
    val accepted = result as ShareInputResolver.Resolution.Accepted
    assertEquals("content://media/external/images/1", accepted.imageUri)
    assertEquals(null, accepted.url)
  }

  @Test
  fun `image plus url accepts combined payload`() {
    val result =
        ShareInputResolver.resolve(
            inputs(text = "see https://example.com/p/1", streamUri = "content://x/1"))
    val accepted = result as ShareInputResolver.Resolution.Accepted
    assertEquals("https://example.com/p/1", accepted.url)
    assertEquals("content://x/1", accepted.imageUri)
  }

  @Test
  fun `mismatched stream and clip uris reject as ambiguous`() {
    val result =
        ShareInputResolver.resolve(
            inputs(streamUri = "content://a/1", clipUris = listOf("content://b/2")))
    assertEquals(
        ShareInputResolver.Reason.MULTIPLE_OR_AMBIGUOUS,
        (result as ShareInputResolver.Resolution.Rejected).reason)
  }

  @Test
  fun `multiple clip items reject`() {
    val result =
        ShareInputResolver.resolve(
            inputs(clipUris = listOf("content://a/1", "content://a/2")))
    assertEquals(
        ShareInputResolver.Reason.MULTIPLE_OR_AMBIGUOUS,
        (result as ShareInputResolver.Resolution.Rejected).reason)
  }

  @Test
  fun `clip item count mismatch rejects as ambiguous`() {
    val result =
        ShareInputResolver.resolve(
            inputs(clipUris = listOf("content://a/1"), clipItemCount = 2))
    assertEquals(
        ShareInputResolver.Reason.MULTIPLE_OR_AMBIGUOUS,
        (result as ShareInputResolver.Resolution.Rejected).reason)
  }

  @Test
  fun `clip item without uri rejects as no valid content`() {
    val result = ShareInputResolver.resolve(inputs(clipItemCount = 1))
    assertEquals(
        ShareInputResolver.Reason.NO_VALID_CONTENT,
        (result as ShareInputResolver.Resolution.Rejected).reason)
  }

  @Test
  fun `clip uri with non-content scheme rejects`() {
    val result = ShareInputResolver.resolve(inputs(clipUris = listOf("file:///image.jpg")))
    assertEquals(
        ShareInputResolver.Reason.NON_CONTENT_URI,
        (result as ShareInputResolver.Resolution.Rejected).reason)
  }

  @Test
  fun `matching stream and clip uri processes once`() {
    val result =
        ShareInputResolver.resolve(
            inputs(streamUri = "content://a/1", clipUris = listOf("content://a/1")))
    assertEquals("content://a/1", (result as ShareInputResolver.Resolution.Accepted).imageUri)
  }

  @Test
  fun `non-content scheme rejects`() {
    val result = ShareInputResolver.resolve(inputs(streamUri = "file:///sdcard/img.jpg"))
    assertEquals(
        ShareInputResolver.Reason.NON_CONTENT_URI,
        (result as ShareInputResolver.Resolution.Rejected).reason)
  }

  @Test
  fun `no url and no image rejects`() {
    val result = ShareInputResolver.resolve(inputs(text = "plain text no link"))
    assertEquals(
        ShareInputResolver.Reason.NO_VALID_CONTENT,
        (result as ShareInputResolver.Resolution.Rejected).reason)
  }

  @Test
  fun `image with unusable text still accepts image`() {
    val result = ShareInputResolver.resolve(inputs(text = "no link", streamUri = "content://x/1"))
    val accepted = result as ShareInputResolver.Resolution.Accepted
    assertEquals(null, accepted.url)
    assertEquals("content://x/1", accepted.imageUri)
    assertTrue(accepted.imageUri != null)
  }
}
