package com.aifashionvault.app.share

import android.content.Context
import java.io.File
import java.util.concurrent.Executors

/**
 * Process-wide holders for the share subsystem.
 *
 * A single dedicated single-thread executor runs all staging work off the UI thread; a single
 * [PendingShareStore] instance backs both the receiver activity and the RN bridge so consume
 * atomicity holds across entry points.
 */
object ShareRuntime {

  @Volatile private var store: PendingShareStore? = null

  fun pendingShareStore(context: Context): PendingShareStore =
      store
          ?: synchronized(this) {
            store ?: PendingShareStore(File(context.cacheDir, "share-pending")).also { store = it }
          }

  val stagingExecutor by lazy { Executors.newSingleThreadExecutor() }
}
