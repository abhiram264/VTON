package com.aifashionvault.app.share

import java.io.StringReader
import java.io.StringWriter
import java.util.Properties

/**
 * Encodes/decodes [PendingShare] metadata as a flat [Properties] document. Only pending-handoff
 * metadata is persisted; no media bytes or durable records (ADR-015/ADR-006).
 *
 * Optional fields are omitted rather than serialized as null.
 *
 * Properties (java.util) is used instead of org.json because org.json is stubbed in local JVM
 * unit tests ("not mocked"); Properties behaves identically on device and JVM.
 */
object PendingShareCodec {

  private const val KEY_SHARE_ID = "shareId"
  private const val KEY_CREATED_AT = "createdAtEpochMillis"
  private const val KEY_KIND = "kind"
  private const val KEY_URL = "url"
  private const val KEY_LOCAL_IMAGE_URI = "localImageUri"
  private const val KEY_SOURCE_PACKAGE = "sourcePackage"

  fun encode(pending: PendingShare): String {
    val props = Properties()
    props.setProperty(KEY_SHARE_ID, pending.shareId)
    props.setProperty(KEY_CREATED_AT, pending.createdAtEpochMillis.toString())
    props.setProperty(
        KEY_KIND,
        when (pending.payload) {
          is SharePayload.Url -> "url"
          is SharePayload.Image -> "image"
          is SharePayload.ImageUrl -> "image_url"
        })
    when (val p = pending.payload) {
      is SharePayload.Url -> props.setProperty(KEY_URL, p.url)
      is SharePayload.Image -> props.setProperty(KEY_LOCAL_IMAGE_URI, p.localImageUri)
      is SharePayload.ImageUrl -> {
        props.setProperty(KEY_URL, p.url)
        props.setProperty(KEY_LOCAL_IMAGE_URI, p.localImageUri)
      }
    }
    pending.payload.sourcePackage?.let { props.setProperty(KEY_SOURCE_PACKAGE, it) }
    val writer = StringWriter()
    props.store(writer, null)
    return writer.toString()
  }

  fun decode(raw: String?): PendingShare? {
    if (raw.isNullOrBlank()) return null
    return try {
      val props = Properties()
      StringReader(raw).use { props.load(it) }
      val shareId = props.getProperty(KEY_SHARE_ID) ?: return null
      val createdAt = props.getProperty(KEY_CREATED_AT)?.toLongOrNull() ?: return null
      if (createdAt < 0) return null
      val payload =
          when (props.getProperty(KEY_KIND)) {
            "url" ->
                SharePayload.Url(
                    url = props.getProperty(KEY_URL) ?: return null,
                    sourcePackage = props.getProperty(KEY_SOURCE_PACKAGE))
            "image" ->
                SharePayload.Image(
                    localImageUri = props.getProperty(KEY_LOCAL_IMAGE_URI) ?: return null,
                    sourcePackage = props.getProperty(KEY_SOURCE_PACKAGE))
            "image_url" ->
                SharePayload.ImageUrl(
                    url = props.getProperty(KEY_URL) ?: return null,
                    localImageUri = props.getProperty(KEY_LOCAL_IMAGE_URI) ?: return null,
                    sourcePackage = props.getProperty(KEY_SOURCE_PACKAGE))
            else -> return null
          }
      PendingShare(shareId, payload, createdAt)
    } catch (_: Exception) {
      null
    }
  }
}

/**
 * Validates the kind-specific required/forbidden field contract at the native store boundary.
 */
object SharePayloadValidator {

  fun isValid(payload: SharePayload): Boolean {
    return when (payload) {
      is SharePayload.Url -> payload.url.isNotBlank()
      is SharePayload.Image -> payload.localImageUri.isNotBlank() && isFileUri(payload.localImageUri)
      is SharePayload.ImageUrl ->
          payload.url.isNotBlank() &&
              payload.localImageUri.isNotBlank() &&
              isFileUri(payload.localImageUri)
    }
  }

  private fun isFileUri(uri: String): Boolean = uri.startsWith("file:")
}
