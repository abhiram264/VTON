package com.aifashionvault.app.share

/**
 * Pure decision logic for normalizing share-intent primitives into a pending payload request.
 * Android [android.content.Intent] extraction stays in the activity; this object only sees
 * plain strings so it is fully JVM-testable.
 *
 * Rules (spec "Input normalization and validation"):
 * - Only ACTION_SEND is accepted; ACTION_SEND_MULTIPLE and anything else reject.
 * - At most one image URI: EXTRA_STREAM and ClipData must agree; multiple clip items reject.
 * - Image URIs must use the content: scheme.
 * - Text must contain a credential-free absolute http/https URL when there is no image.
 */
object ShareInputResolver {

  const val ACTION_SEND = "android.intent.action.SEND"

  /** Intent primitives already extracted by the thin activity glue. */
  data class Inputs(
      val action: String?,
      val text: CharSequence?,
      val streamUri: String?,
      val clipUris: List<String>,
  )

  sealed interface Resolution {
    /** Exactly one of [url]/[imageUri] is non-null (or both, for image_url). */
    data class Accepted(val url: String?, val imageUri: String?) : Resolution

    data class Rejected(val reason: Reason) : Resolution
  }

  enum class Reason {
    NOT_SEND_ACTION,
    MULTIPLE_OR_AMBIGUOUS,
    NON_CONTENT_URI,
    NO_VALID_CONTENT,
  }

  fun resolve(inputs: Inputs): Resolution {
    if (inputs.action != ACTION_SEND) return Resolution.Rejected(Reason.NOT_SEND_ACTION)
    if (inputs.clipUris.size > 1) return Resolution.Rejected(Reason.MULTIPLE_OR_AMBIGUOUS)
    val clipUri = inputs.clipUris.singleOrNull()
    val imageUri =
        when {
          inputs.streamUri != null && clipUri != null ->
              if (inputs.streamUri == clipUri) {
                inputs.streamUri
              } else {
                return Resolution.Rejected(Reason.MULTIPLE_OR_AMBIGUOUS)
              }
          inputs.streamUri != null -> inputs.streamUri
          clipUri != null -> clipUri
          else -> null
        }
    if (imageUri != null && !imageUri.startsWith("content:")) {
      return Resolution.Rejected(Reason.NON_CONTENT_URI)
    }
    val url = ShareUrlExtractor.extractFirstHttpUrl(inputs.text)
    if (imageUri == null && url == null) {
      return Resolution.Rejected(Reason.NO_VALID_CONTENT)
    }
    return Resolution.Accepted(url = url, imageUri = imageUri)
  }
}
