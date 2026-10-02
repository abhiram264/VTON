/**
 * Account-session isolation (C03: "signout that clears account-bound
 * draft/cache/history pointers" and "second account sees no first-account
 * state").
 *
 * The auth SDK owns the credential; this module owns the *local* pointers the
 * app keeps outside it. Both an explicit sign-out and a session that ends
 * without the user asking (expiry, revocation, a token the backend rejects)
 * must leave the next account with none of the previous account's state.
 *
 * Scope honesty: it clears exactly the account-bound pointers that exist in
 * the client today — the active-generation pointer
 * (features/generation/activeGeneration) and the feature-flag overrides
 * applied at runtime (config/featureFlags). Screen-local state (composer
 * drafts, look selections) lives in component state and is discarded when the
 * session gate swaps the navigation tree, so it needs no entry here.
 */
import { signOut as firebaseSignOut, type Auth } from '@firebase/auth';
import {
  getAuthSurface,
  type AuthSession,
  type AuthSurface,
} from '../../config/firebase';
import {
  DEFAULT_FEATURE_FLAGS,
  applyFeatureFlagPayload,
} from '../../config/featureFlags';
import { clearActiveGeneration } from '../generation/activeGeneration';

export type SignOutOutcome = 'signed-out' | 'unavailable';

/**
 * Drops every account-bound local pointer back to its signed-out default.
 * Idempotent: safe to call when nothing was set.
 */
export function clearAccountBoundState(): void {
  clearActiveGeneration();
  // Re-applying the declared defaults is the reset for this seam: the module
  // keeps one mutable map, so writing defaults back is exactly "no overrides".
  applyFeatureFlagPayload(DEFAULT_FEATURE_FLAGS);
}

/**
 * Signs the current account out through the SDK and then clears the
 * account-bound pointers.
 *
 * An unavailable surface (no Firebase configuration) has no credential to
 * drop, so this reports `unavailable` and touches nothing — it never pretends
 * a sign-out happened, and it never clears state for a session that was never
 * established.
 */
export async function signOutAccount(
  deps: {
    surface?: AuthSurface;
    signOut?: (auth: Auth) => Promise<void>;
    clear?: () => void;
  } = {},
): Promise<SignOutOutcome> {
  const surface = deps.surface ?? getAuthSurface();
  if (surface.status !== 'available') {
    return 'unavailable';
  }
  await (deps.signOut ?? firebaseSignOut)(surface.auth);
  (deps.clear ?? clearAccountBoundState)();
  return 'signed-out';
}

/**
 * Watches the auth session and clears account-bound state every time the
 * session becomes `signed-out`.
 *
 * This is what makes an *involuntary* end of session (expiry, revocation, a
 * backend that rejects the token) behave like an explicit sign-out: the
 * transition is observed rather than requested. `signed-in` never clears, so
 * a live session keeps its pointers.
 *
 * Returns the unsubscribe function of the underlying subscription.
 */
export function trackAccountIsolation(
  subscribe: (listener: (session: AuthSession) => void) => () => void,
  onSignedOut: () => void = clearAccountBoundState,
): () => void {
  let previous: AuthSession['status'] | null = null;
  return subscribe(session => {
    const enteringSignedOut =
      session.status === 'signed-out' && previous !== 'signed-out';
    previous = session.status;
    if (enteringSignedOut) {
      onSignedOut();
    }
  });
}
