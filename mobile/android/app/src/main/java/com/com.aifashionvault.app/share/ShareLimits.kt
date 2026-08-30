package com.aifashionvault.app.share

/** Project share-safety policy constants (frozen by the android-share-target spec). */
object ShareLimits {
  const val MAX_SHARE_BYTES = 20971520L
  const val MAX_SHARE_PIXELS = 40000000L
  const val MAX_SHARE_EDGE = 12000
  const val PENDING_SHARE_TTL_MS = 86400000L
  const val MAX_PENDING_SHARES = 8
}
