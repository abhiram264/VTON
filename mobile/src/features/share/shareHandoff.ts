import { NativeModules } from 'react-native';
import { SHARE_HANDOFF_MODULE_NAME } from './types';
import type { ShareHandoffNativeModule } from './types';

// Reads the native module lazily at call time so test doubles injected on
// NativeModules are observed. Returns null where the module was not
// registered on the running platform — callers treat that as "no share
// handoff available".
export function getShareHandoff(): ShareHandoffNativeModule | null {
  const candidate = (NativeModules as unknown as Record<string, unknown>)[
    SHARE_HANDOFF_MODULE_NAME
  ];
  if (
    typeof candidate === 'object' &&
    candidate !== null &&
    typeof (candidate as { consumeShare?: unknown }).consumeShare === 'function'
  ) {
    return candidate as ShareHandoffNativeModule;
  }
  return null;
}

// Releases the staged image file behind localImageUri. Safe no-op when the
// native module or method is absent (tests/CI, platforms without the
// module). Errors are swallowed silently; the URI is never logged
// (ADR-015). Native side rejects anything outside its share-staging
// directory, so this stays safe.
export async function releaseStagedImage(localImageUri: string): Promise<void> {
  const candidate = (NativeModules as unknown as Record<string, unknown>)[
    SHARE_HANDOFF_MODULE_NAME
  ];
  if (
    typeof candidate === 'object' &&
    candidate !== null &&
    typeof (candidate as { releaseStagedImage?: unknown })
      .releaseStagedImage === 'function'
  ) {
    try {
      await (candidate as ShareHandoffNativeModule).releaseStagedImage(
        localImageUri,
      );
    } catch {
      // Silent per ADR-015: no URI or error detail may be logged.
    }
  }
}
