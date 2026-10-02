package com.aifashionvault.app.photopicker

import java.io.File
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Staging rules for picked/captured photos: files live directly under the app-private
 * staging directory with UUID names, the URI handed to JS is a resolvable `file:` URI, and
 * cleanup reclaims only stale direct children (never directories, never fresh files).
 */
class PhotoPickerStagingTest {

  @get:Rule val tmp = TemporaryFolder()

  private val uuidPattern =
      Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

  @Test
  fun `new staged file is a fresh uuid directly under the staging directory`() {
    val first = PhotoPickerStaging.newStagedFile(tmp.root)
    val second = PhotoPickerStaging.newStagedFile(tmp.root)

    assertEquals(PhotoPickerStaging.stagingDir(tmp.root).canonicalFile, first.parentFile!!.canonicalFile)
    assertTrue(first.name.matches(uuidPattern))
    assertNotEquals(first.name, second.name)
  }

  @Test
  fun `uri of a staged file is a resolvable file uri`() {
    val file = PhotoPickerStaging.newStagedFile(tmp.root).apply { writeText("bytes") }

    val uri = URI(PhotoPickerStaging.uriOf(file))

    assertEquals("file", uri.scheme)
    assertEquals(file.canonicalFile, File(uri.path!!).canonicalFile)
  }

  @Test
  fun `cleanup deletes only stale direct children`() {
    val dir = PhotoPickerStaging.stagingDir(tmp.root).apply { mkdirs() }
    val now = 1_700_000_000_000L
    val stale =
        File(dir, "stale").apply {
          writeText("x")
          setLastModified(now - PhotoPickerStaging.STAGED_FILE_TTL_MS - 1)
        }
    val fresh =
        File(dir, "fresh").apply {
          writeText("x")
          setLastModified(now)
        }
    val staleDirectory =
        File(dir, "nested").apply {
          mkdirs()
          setLastModified(now - PhotoPickerStaging.STAGED_FILE_TTL_MS - 1)
        }

    assertEquals(1, PhotoPickerStaging.cleanupStale(tmp.root, now))
    assertFalse(stale.exists())
    assertTrue(fresh.exists())
    assertTrue(staleDirectory.isDirectory)
  }

  @Test
  fun `cleanup is a no-op when nothing has been staged`() {
    assertEquals(0, PhotoPickerStaging.cleanupStale(tmp.root, 1_700_000_000_000L))
  }
}
