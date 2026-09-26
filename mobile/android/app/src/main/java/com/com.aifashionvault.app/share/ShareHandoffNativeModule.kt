package com.aifashionvault.app.share

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableNativeMap
import java.io.File

/**
 * JS-facing bridge: `NativeModules.ShareHandoff`.
 *
 * Exposes two asynchronous methods and no event stream:
 * - `consumeShare(shareId)` resolves the frozen ConsumeShareResult envelope
 *   (`{ ok: true, payload: {...} }` or `{ ok: false, code: ... }`). Typed failures
 *   (invalid_share_id | share_not_found | share_expired | invalid_payload) are values, never
 *   rejected promises. It never lists pending shares and never returns a payload twice (the
 *   store's consume is atomic and one-shot).
 * - `releaseStagedImage(localImageUri)` deletes an abandoned staged image after the route
 *   cancels. It accepts only a canonical file: URI directly under the app-private staging
 *   directory; anything else is a silent no-op. It never throws to JS (ADR-015: no URIs are
 *   logged).
 *
 * Envelope construction is pure ([ShareHandoffEnvelope], JVM-testable); this class only
 * converts plain maps to WritableNativeMap at the JNI edge.
 */
class ShareHandoffNativeModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

  override fun getName(): String = MODULE_NAME

  @ReactMethod
  fun consumeShare(shareId: String?, promise: Promise) {
    val result =
        try {
          store().consume(shareId)
        } catch (_: Exception) {
          PendingShareStore.ConsumeResult.InvalidPayload
        }
    promise.resolve(ShareHandoffEnvelope.build(result).toWritableMap())
  }

  @ReactMethod
  fun releaseStagedImage(localImageUri: String?, promise: Promise) {
    val stagingDir = ShareRuntime.stagingDir(reactApplicationContext)
    try {
      val file = StagedImagePaths.stagedFile(stagingDir, localImageUri)
      if (file != null && !store().isStagedFileReferenced(localImageUri)) file.delete()
    } catch (_: Exception) {
      // Never throw to JS; release is best-effort cleanup.
    }
    promise.resolve(null)
  }

  private fun Map<String, Any>.toWritableMap(): WritableNativeMap {
    val map = WritableNativeMap()
    for ((key, value) in this) {
      when (value) {
        is Boolean -> map.putBoolean(key, value)
        is String -> map.putString(key, value)
        is Map<*, *> -> @Suppress("UNCHECKED_CAST") map.putMap(key, (value as Map<String, Any>).toWritableMap())
      }
    }
    return map
  }

  private fun store(): PendingShareStore =
      ShareRuntime.pendingShareStore(reactApplicationContext.applicationContext)

  companion object {
    const val MODULE_NAME = "ShareHandoff"
  }
}
