package com.aifashionvault.app.share

sealed interface SharePayload {
  val sourcePackage: String?

  data class Url(
    val url: String,
    override val sourcePackage: String? = null,
  ) : SharePayload

  data class Image(
    val localImageUri: String,
    override val sourcePackage: String? = null,
  ) : SharePayload

  data class ImageUrl(
    val url: String,
    val localImageUri: String,
    override val sourcePackage: String? = null,
  ) : SharePayload
}

data class PendingShare(
  val shareId: String,
  val payload: SharePayload,
  val createdAtEpochMillis: Long,
)
