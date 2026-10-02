/**
 * Backend capability sync (C22 item 5/6: "backend capability/admission
 * authoritative", "prove the operational kill switch reaches actual admission").
 *
 * `config/featureFlags.ts` already knows how to fetch and apply the backend
 * snapshot, but nothing in the app ever called it, so the client only ever saw
 * its own compiled defaults and an operator disabling a capability server-side
 * never reached the running app. This module is that caller: it watches the
 * auth session and refreshes the capability snapshot once a session exists.
 *
 * Failure is safe by construction. `refreshFeatureFlags` is total (it swallows
 * network and parse failures and keeps the current values), so a signed-out,
 * offline or erroring refresh leaves the declared defaults in place — it can
 * never turn an unsupported capability on.
 */
import { subscribeToAuthSession, type AuthSession } from '../config/firebase';
import {
  refreshFeatureFlags,
  type FeatureFlagRefreshConfig,
} from '../config/featureFlags';
import { productionApiConfig } from '../config/apiConfig';

export type FeatureFlagSyncDeps = {
  /** Auth-session subscription seam; defaults to the Firebase surface. */
  subscribe?: (listener: (session: AuthSession) => void) => () => void;
  /** Snapshot fetch seam; defaults to the real fetch-and-apply. */
  refresh?: (config: FeatureFlagRefreshConfig) => Promise<void>;
  /** Where the snapshot is fetched from; defaults to the app API config. */
  config?: FeatureFlagRefreshConfig;
};

/**
 * Starts the capability sync and returns the unsubscribe function.
 *
 * A refresh is attempted only for a `signed-in` session: there is no token to
 * authenticate with before that, and the request would be a guaranteed 401.
 * `loading`, `signed-out` and `unavailable` are all no-ops, which is what makes
 * an offline or unauthenticated boot keep the safe defaults.
 */
export function startFeatureFlagSync(deps: FeatureFlagSyncDeps = {}): () => void {
  const subscribe = deps.subscribe ?? subscribeToAuthSession;
  const refresh = deps.refresh ?? refreshFeatureFlags;
  const config = deps.config ?? productionApiConfig;
  return subscribe(session => {
    if (session.status !== 'signed-in') {
      return;
    }
    // The real refresh never rejects; the catch is defensive so a future
    // implementation cannot crash the app from an effect.
    refresh(config).catch(() => {});
  });
}
