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
import java.io.FileOutputStream

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
  @Volatile private var workState = ShareReceiverLifecycle.WorkState.UNFINISHED
  private var finished = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val restoredWork =
        savedInstanceState?.getString(STATE_WORK)?.let { value ->
          runCatching { ShareReceiverLifecycle.WorkState.valueOf(value) }.getOrNull()
        }
    if (savedInstanceState != null && restoredWork == null) {
      failAndFinish(ShareInputResolver.Reason.NO_VALID_CONTENT)
      return
    }
    if (!ShareReceiverLifecycle.shouldProcessIntent(restoredWork)) {
      if (restoredWork == ShareReceiverLifecycle.WorkState.UNFINISHED) {
        fail(ShareInputResolver.Reason.NO_VALID_CONTENT)
      }
      finishOnce()
      return
    }
    handleIntent(intent)
  }

  override fun onSaveInstanceState(outState: Bundle) {
    outState.putString(STATE_WORK, workState.name)
    super.onSaveInstanceState(outState)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    if (!ShareReceiverLifecycle.shouldAcceptNewIntent(isStaging = staging)) {
      // Preserve A: do not replace the grant-bearing first intent. B has no durable queue or
      // acknowledgement seam, so reject it explicitly until the cross-language protocol lands.
      fail(ShareInputResolver.Reason.NOT_SEND_ACTION)
      return
    }
    setIntent(intent)
    handleIntent(intent)
  }

  private fun handleIntent(intent: Intent?) {
    if (intent == null) return
    if (intent.action != ACTION_SEND) {
      failAndFinish(ShareInputResolver.Reason.NOT_SEND_ACTION)
      return
    }
    try {
      val streamUri = extractStreamUri(intent)
      val clipUris = extractClipUris(intent)
      val resolution =
          ShareInputResolver.resolve(
              ShareInputResolver.Inputs(
                  action = intent.action,
                  text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT),
                  streamUri = streamUri,
                  clipUris = clipUris,
                  clipItemCount = intent.clipData?.itemCount ?: 0,
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
    } catch (_: Exception) {
      // Exported activities receive cross-process extras; malformed parcelables must not crash.
      failAndFinish(ShareInputResolver.Reason.NO_VALID_CONTENT)
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
    val uris = ArrayList<String>(2)
    for (i in 0 until clip.itemCount) {
      val uri = clip.getItemAt(i)?.uri ?: continue
      uris.add(uri.toString())
      if (uris.size == 2) break
    }
    return uris
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
      val stagedFile = ShareRuntime.newStagedFile(applicationContext)
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
                    // Keep the stored metadata and its staged bytes paired. This receiver has no
                    // durable claim/ack seam from which a destroyed instance could safely relaunch.
                    return@runOnUiThread
                  }
                  try {
                    launchHandoff(put.pending.shareId)
                  } catch (_: Exception) {
                    // Preserve the durable metadata/file pair for a future recovery policy.
                  }
                  workState = ShareReceiverLifecycle.WorkState.COMPLETED
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
        workState = ShareReceiverLifecycle.WorkState.COMPLETED
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
      workState = ShareReceiverLifecycle.WorkState.COMPLETED
      fail(reason)
      finishOnce()
    }
  }

  /** True when terminal UI steps must not run: this instance is finishing or destroyed. */
  private fun terminalStepSuppressed(): Boolean =
      ShareReceiverLifecycle.terminalStepSuppressed(isFinishing, isDestroyed)

  private fun failAndFinish(reason: ShareInputResolver.Reason) {
    workState = ShareReceiverLifecycle.WorkState.COMPLETED
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
    private const val STATE_WORK = "share_work_state"
    const val ACTION_VIEW_HANDOFF = "android.intent.action.VIEW"
  }
}

/**
 * Pure lifecycle decisions for the share receiver, extracted for JVM testing.
 *
 * [shouldProcessIntent] — only a genuinely fresh delivery may be processed. Restored unfinished
 * work fails closed; restored completed work finishes without a duplicate handoff.
 *
 * [terminalStepSuppressed] — terminal UI steps (toast, finish, handoff launch) must not run
 * on a finishing/destroyed instance: they would crash or launch a handoff from an orphaned
 * activity whose grant-bearing instance was already destroyed.
 */
object ShareReceiverLifecycle {

  enum class WorkState { UNFINISHED, COMPLETED }

  fun shouldProcessIntent(restoredWork: WorkState?): Boolean = restoredWork == null

  fun shouldAcceptNewIntent(isStaging: Boolean): Boolean = !isStaging

  fun shouldFailRestoredWork(restoredWork: WorkState): Boolean =
      restoredWork == WorkState.UNFINISHED

  fun terminalStepSuppressed(isFinishing: Boolean, isDestroyed: Boolean): Boolean =
      isFinishing || isDestroyed
}
