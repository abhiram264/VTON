package com.aifashionvault.app

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.Intent.ACTION_SEND
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.aifashionvault.app.share.PendingShareStore
import com.aifashionvault.app.share.ShareImageStager
import com.aifashionvault.app.share.ShareInputResolver
import com.aifashionvault.app.share.SharePayload
import com.aifashionvault.app.share.ShareRuntime
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Non-RN share receiver. Advertised for ACTION_SEND text/plain and image MIME (manifest). It
 * validates hostile input, stages an app-private copy of an accepted image, stores a
 * normalized pending share, and hands off to [MainActivity] via an opaque deep link.
 *
 * Launch mode is the default "standard": independent receiver instances prepare independent
 * pending shares; a new share never overwrites an in-flight intent.
 *
 * Lifecycle (finish-before-grant-use fix): the activity stays alive while an image staging
 * job is in flight, because the sender's temporary read grant lives only as long as this
 * activity instance. openInputStream/copy run on the staging executor while the activity is
 * alive; every terminal step (launch, error toast, finish) runs on the UI thread, and each
 * instance finishes exactly once on every path. The URL-only path remains synchronous
 * (store + launch + finish). A second intent arriving while staging is in flight is guarded
 * against rather than double-launching.
 */
class ShareImportActivity : Activity() {

  @Volatile private var staging = false
  private var finished = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (!ShareReceiverLifecycle.shouldProcessIntent(savedInstanceState != null)) {
      // Recreation (config change or process restore) of an already-handled intent:
      // re-processing would double-store and double-launch the handoff. Any in-flight
      // staging job belonged to the destroyed instance; its terminal steps are
      // suppressed there (see ShareReceiverLifecycle.terminalStepSuppressed).
      finishOnce()
      return
    }
    handleIntent(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    if (staging) {
      // A staging job owns this instance's grant; a second intent must not double-launch.
      fail(ShareInputResolver.Reason.NOT_SEND_ACTION)
      return
    }
    handleIntent(intent)
  }

  private fun handleIntent(intent: Intent?) {
    if (intent == null) return
    if (intent.action != ACTION_SEND) {
      failAndFinish(ShareInputResolver.Reason.NOT_SEND_ACTION)
      return
    }
    val streamUri = extractStreamUri(intent)
    val clipUris = extractClipUris(intent)
    val resolution =
        ShareInputResolver.resolve(
            ShareInputResolver.Inputs(
                action = intent.action,
                text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT),
                streamUri = streamUri,
                clipUris = clipUris,
            ))
    when (resolution) {
      is ShareInputResolver.Resolution.Rejected -> failAndFinish(resolution.reason)
      is ShareInputResolver.Resolution.Accepted -> {
        val url = resolution.url
        val imageUri = resolution.imageUri
        when {
          imageUri != null && url != null -> stageAndStore(imageUri, url)
          imageUri != null -> stageAndStore(imageUri, null)
          url != null -> store(SharePayload.Url(url, senderPackage(intent)))
        }
      }
    }
  }

  /** Resolves the single stream URI (typed helper with ClipData grant-aware fallback). */
  private fun extractStreamUri(intent: Intent): String? =
      @Suppress("DEPRECATION")
      (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.toString()

  private fun extractClipUris(intent: Intent): List<String> {
    val clip = intent.clipData ?: return emptyList()
    if (clip.description != null &&
        clip.description.getMimeTypeCount() == 0 &&
        clip.itemCount > 0) {
      // A ClipData with no MIME types cannot carry a grant-aware share; ignore it.
      return emptyList()
    }
    return (0 until clip.itemCount).mapNotNull { i -> clip.getItemAt(i)?.uri?.toString() }
  }

  /**
   * Best-effort sender attribution from Android-provided metadata only. Never inferred from
   * shared text, URI authority, display names, or user-controlled extras.
   */
  private fun senderPackage(intent: Intent): String? {
    val referrer = referrer?.host
    if (!referrer.isNullOrBlank() && referrer.contains('.')) return referrer
    return null
  }

  private fun stageAndStore(contentUri: String, url: String?) {
    val store = ShareRuntime.pendingShareStore(applicationContext)
    val sender = senderPackage(intent)
    staging = true
    // Background dispatcher: header inspection and copying never run on the UI thread. The
    // activity must stay alive (no finish) while this runs so the read grant is still valid.
    ShareRuntime.stagingExecutor.execute {
      val stagedFile = File(cacheDir, "share-staging/${UUID.randomUUID()}")
      try {
        stagedFile.parentFile?.mkdirs()
        val source = resolveSourceStream(contentUri)
        if (source == null) {
          finishOnUiThread(ShareInputResolver.Reason.NO_VALID_CONTENT)
          return@execute
        }
        val result =
            ShareImageStager.copyWithLimits(
                source = source, sinkFactory = { FileOutputStream(stagedFile) })
        when {
          result.isSuccess -> {
            val stagedUri = stagedFile.toURI().toString()
            val payload =
                when (url) {
                  null -> SharePayload.Image(stagedUri, sender)
                  else -> SharePayload.ImageUrl(url, stagedUri, sender)
                }
            when (val put = store.put(payload)) {
              is PendingShareStore.PutResult.Stored -> {
                // Terminal steps on the UI thread.
                runOnUiThread {
                  if (terminalStepSuppressed()) {
                    // Orphaned instance (destroyed mid-staging): never launch a handoff
                    // from it; delete the staged file. The pending entry is reclaimed by
                    // TTL cleanup.
                    stagedFile.delete()
                    return@runOnUiThread
                  }
                  try {
                    launchHandoff(put.pending.shareId)
                  } catch (_: Exception) {
                    // Launch failure still finishes the receiver; the staged file must
                    // not leak.
                    stagedFile.delete()
                  }
                  finishOnce()
                }
              }
              else -> {
                stagedFile.delete()
                finishOnUiThread(ShareInputResolver.Reason.NO_VALID_CONTENT)
              }
            }
          }
          else -> {
            stagedFile.delete()
            finishOnUiThread(ShareInputResolver.Reason.NO_VALID_CONTENT)
          }
        }
      } catch (_: Exception) {
        // An unexpected failure must not strand the receiver or leak the staged file.
        stagedFile.delete()
        finishOnUiThread(ShareInputResolver.Reason.NO_VALID_CONTENT)
      }
    }
  }

  private fun store(payload: SharePayload) {
    val store = ShareRuntime.pendingShareStore(applicationContext)
    val put =
        try {
          store.put(payload)
        } catch (_: Exception) {
          // UI-thread path: an unexpected store failure must not strand the receiver.
          failAndFinish(ShareInputResolver.Reason.NO_VALID_CONTENT)
          return
        }
    when (put) {
      is PendingShareStore.PutResult.Stored -> {
        try {
          launchHandoff(put.pending.shareId)
        } catch (_: Exception) {
          // Launch failure still finishes the receiver.
        }
        finishOnce()
      }
      else -> failAndFinish(ShareInputResolver.Reason.NO_VALID_CONTENT)
    }
  }

  private fun resolveSourceStream(contentUri: String): java.io.InputStream? {
    return try {
      val uri = Uri.parse(contentUri)
      if (uri.scheme != "content") return null
      // Grant probe: an inability to open is a normal validation failure.
      contentResolver.openInputStream(uri)
    } catch (_: Exception) {
      null
    }
  }

  /**
   * Hands off to the RN host. The URI carries only the opaque share id — never URL, foreign
   * URI, file path, bytes, or payload JSON (ADR-015).
   */
  private fun launchHandoff(shareId: String) {
    val intent =
        Intent(ACTION_VIEW_HANDOFF, Uri.parse("aifashionvault://share-import/$shareId")).apply {
          setPackage(packageName)
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    startActivity(intent)
  }

  private fun fail(reason: ShareInputResolver.Reason) {
    // Bounded user-safe error: no URL, URI, display name, provider details, or stack trace.
    Toast.makeText(this, R.string.share_import_error, Toast.LENGTH_SHORT).show()
  }

  /** Terminal error path from the staging thread: toast + exactly-one finish, on the UI thread. */
  private fun finishOnUiThread(reason: ShareInputResolver.Reason) {
    runOnUiThread {
      if (terminalStepSuppressed()) return@runOnUiThread
      fail(reason)
      finishOnce()
    }
  }

  /** True when terminal UI steps must not run: this instance is finishing or destroyed. */
  private fun terminalStepSuppressed(): Boolean =
      ShareReceiverLifecycle.terminalStepSuppressed(isFinishing, isDestroyed)

  private fun failAndFinish(reason: ShareInputResolver.Reason) {
    fail(reason)
    finishOnce()
  }

  /** UI-thread-only; guarantees exactly one finish() per instance on every path. */
  private fun finishOnce() {
    if (finished) return
    finished = true
    finish()
  }

  companion object {
    const val ACTION_VIEW_HANDOFF = "android.intent.action.VIEW"
  }
}

/**
 * Pure lifecycle decisions for the share receiver, extracted for JVM testing.
 *
 * [shouldProcessIntent] — recreation of an already-handled intent (non-null saved state)
 * must not re-process it; only a genuinely fresh delivery does.
 *
 * [terminalStepSuppressed] — terminal UI steps (toast, finish, handoff launch) must not run
 * on a finishing/destroyed instance: they would crash or launch a handoff from an orphaned
 * activity whose grant-bearing instance was already destroyed.
 */
object ShareReceiverLifecycle {

  fun shouldProcessIntent(hasSavedInstanceState: Boolean): Boolean = !hasSavedInstanceState

  fun terminalStepSuppressed(isFinishing: Boolean, isDestroyed: Boolean): Boolean =
      isFinishing || isDestroyed
}
