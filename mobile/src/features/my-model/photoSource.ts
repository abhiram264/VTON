import { NativeModules } from 'react-native';
import type { LocalPhoto } from './types';

export type { LocalPhoto };

export class CameraPermissionDeniedError extends Error {
  readonly code = 'CAMERA_PERMISSION_DENIED' as const;

  constructor() {
    super('Camera permission denied');
    this.name = 'CameraPermissionDeniedError';
  }
}

export function isCameraPermissionDenied(error: unknown): boolean {
  return (
    error instanceof CameraPermissionDeniedError ||
    (typeof error === 'object' &&
      error !== null &&
      'name' in error &&
      (error as { name: string }).name === 'CameraPermissionDeniedError')
  );
}

// Result envelope resolved by the native photo-picker bridge. `cancelled` is
// the only cancellation signal: a dismissed picker resolves `{cancelled:true}`
// and never a uri, so null below means "user cancelled" and nothing else.
type PickerResult = {
  cancelled: boolean;
  uri?: string;
  permissionDenied?: boolean;
};

type PhotoPickerNativeModule = {
  takePhoto(): Promise<PickerResult>;
  choosePhoto(): Promise<PickerResult>;
};

export const PHOTO_PICKER_MODULE_NAME = 'PhotoPicker';

// Read lazily at call time (same seam as getShareHandoff) so a test double on
// NativeModules is observed. An unregistered bridge is a real failure, not a
// cancellation: it used to resolve null for every caller, which is what made
// F01 invisible to the screens.
function getPhotoPicker(): PhotoPickerNativeModule {
  const candidate = (NativeModules as unknown as Record<string, unknown>)[
    PHOTO_PICKER_MODULE_NAME
  ];
  if (
    typeof candidate === 'object' &&
    candidate !== null &&
    typeof (candidate as PhotoPickerNativeModule).choosePhoto === 'function' &&
    typeof (candidate as PhotoPickerNativeModule).takePhoto === 'function'
  ) {
    return candidate as PhotoPickerNativeModule;
  }
  throw new Error('Photo picker is not available on this platform');
}

// Cancellation collapses to null; a malformed result is thrown rather than
// turned into a bogus selection, so a bridge bug cannot start an upload with an
// empty uri.
function toLocalPhoto(result: PickerResult): LocalPhoto | null {
  if (result?.cancelled === true) {
    return null;
  }
  if (result?.permissionDenied === true) {
    throw new CameraPermissionDeniedError();
  }
  if (typeof result?.uri !== 'string' || result.uri.trim() === '') {
    throw new Error('Photo picker returned no photo');
  }
  return { uri: result.uri };
}

export async function takePhoto(): Promise<LocalPhoto | null> {
  return toLocalPhoto(await getPhotoPicker().takePhoto());
}

export async function choosePhoto(): Promise<LocalPhoto | null> {
  return toLocalPhoto(await getPhotoPicker().choosePhoto());
}
