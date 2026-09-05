export const QUALITY_FAILURE_BODY =
  "We couldn't create an accurate preview for this item.";
export const OUTAGE_TITLE = 'Try-on is temporarily unavailable.';
export const OUTAGE_BODY = 'Your item has been saved to the Vault.';
export const AVATAR_FAILURE_BODY =
  'We need a clearer full-body photo to create an accurate preview.';
export const EXTRACTION_FAILURE_BODY =
  "We found the product link, but couldn't reliably extract the item image.";

export const ACTION_TRY_AGAIN = 'Try Again';
export const ACTION_CHOOSE_ANOTHER_PHOTO = 'Choose Another Photo';
export const ACTION_CHOOSE_SCREENSHOT = 'Choose Screenshot';
export const ACTION_UPLOAD_IMAGE = 'Upload Image';

export type GenerationFailureClass =
  | 'QUALITY'
  | 'OUTAGE'
  | 'MODEL_MISSING'
  | 'RATE_LIMITED'
  | 'GENERIC';

export const OUTAGE_FAILURE_CODES: readonly string[] = [
  'PROVIDER_UNAVAILABLE',
  'PROVIDER_EXECUTION_FAILED',
  'PROVIDER_EXECUTION_DEADLINE',
  'PROVIDER_INDETERMINATE',
  'SUBMISSION_OUTCOME_UNKNOWN',
  'SUBMISSION_RECOVERY_DEADLINE',
  'UNSAFE_REPLAY_BLOCKED',
  'PROVIDER_OUTPUT_INVALID',
  'ORCHESTRATION_DEADLINE_EXCEEDED',
  'ADMISSION_PAUSED',
];

const OUTAGE_SET: ReadonlySet<string> = new Set(OUTAGE_FAILURE_CODES);

const RATE_LIMIT_CODES: ReadonlySet<string> = new Set([
  'RATE_LIMITED',
  'RATE_LIMIT',
  'PROVIDER_RATE_LIMIT_EXHAUSTED',
  'PROVIDER_RATE_LIMIT',
  'TOO_MANY_REQUESTS',
  '429',
]);

/** networkLevel=true marks a thrown error with no problem code (fetch rejection). */
export function classifyGenerationFailure(
  code: string | undefined,
  networkLevel: boolean,
): GenerationFailureClass {
  if (networkLevel) {
    return 'OUTAGE';
  }
  if (code === 'QUALITY_REJECTED') {
    return 'QUALITY';
  }
  if (code !== undefined && OUTAGE_SET.has(code)) {
    return 'OUTAGE';
  }
  if (code === 'MY_MODEL_MISSING') {
    return 'MODEL_MISSING';
  }
  if (code !== undefined && RATE_LIMIT_CODES.has(code)) {
    return 'RATE_LIMITED';
  }
  return 'GENERIC';
}

export const AVATAR_CLEAR_PHOTO_CODES: ReadonlySet<string> = new Set([
  'AVATAR_PERSON_NOT_SINGLE',
  'AVATAR_FACE_NOT_VISIBLE',
  'AVATAR_BODY_NOT_VISIBLE',
  'AVATAR_TOO_BLURRED',
  'AVATAR_TOO_DARK',
  'AVATAR_RESOLUTION_INSUFFICIENT',
  'AVATAR_ASPECT_RATIO',
]);
