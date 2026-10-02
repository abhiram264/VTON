import { signOut as firebaseSignOut } from '@firebase/auth';
import { productionApiConfig } from '../../config/apiConfig';
import { getAuthSurface } from '../../config/firebase';
import { apiRequest, requireOk } from '../my-model/http';
import type { AvatarApiConfig } from '../my-model/types';

/**
 * Requests permanent deletion of the authenticated account.
 *
 * The mounted route is `DELETE /me` (MeController): the backend establishes
 * the deletion barrier before running any cleanup, so a 204 means the request
 * was accepted — never that the data is already erased. Any other outcome is
 * raised as an ApiProblemError by `requireOk` and must be reported honestly by
 * the caller as "not deleted".
 */
export async function deleteAccount(
  config: AvatarApiConfig = productionApiConfig,
): Promise<void> {
  await requireOk(await apiRequest(config, '/me', { method: 'DELETE' }));
}

/**
 * Ends the local Firebase session. Only called after the server accepted the
 * deletion: the account is gone server-side, so keeping a live local session
 * would leave the app in a state every subsequent request rejects.
 *
 * An unavailable auth surface has no session to end and is a no-op rather than
 * a failure — nothing can have been signed in without a configured provider.
 */
export async function signOutCurrentSession(): Promise<void> {
  const surface = getAuthSurface();
  if (surface.status !== 'available') {
    return;
  }
  await firebaseSignOut(surface.auth);
}
