package com.aifashionvault.app.photopicker

/**
 * The frozen JS-facing result envelope for `NativeModules.PhotoPicker`.
 *
 * The wrapper in `mobile/src/features/my-model/photoSource.ts` reads exactly three signals:
 * - `cancelled: true`      -> the user dismissed the picker (resolves to no selection)
 * - `permissionDenied: true` -> the camera permission was denied (recoverable error)
 * - `uri: <file uri>`      -> a staged, bounded, app-private copy of the chosen photo
 *
 * Anything else is a promise rejection (a real failure), never a silent null. This object is
 * pure (no React Native types) so the shape contract is JVM-testable.
 */
object PhotoPickerEnvelope {

  fun cancelled(): Map<String, Any> = mapOf("cancelled" to true)

  fun picked(stagedUri: String): Map<String, Any> =
      mapOf("cancelled" to false, "uri" to stagedUri)

  fun permissionDenied(): Map<String, Any> =
      mapOf("cancelled" to false, "permissionDenied" to true)
}
