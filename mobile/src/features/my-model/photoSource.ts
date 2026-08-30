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

export async function takePhoto(): Promise<LocalPhoto | null> {
  return null;
}

export async function choosePhoto(): Promise<LocalPhoto | null> {
  return null;
}
