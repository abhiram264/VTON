import {
  clearAccountBoundState,
  signOutAccount,
  trackAccountIsolation,
} from '../src/features/auth/accountSession';
import {
  applyFeatureFlagPayload,
  DEFAULT_FEATURE_FLAGS,
  isFeatureEnabled,
} from '../src/config/featureFlags';
import {
  getActiveGeneration,
  setActiveGeneration,
} from '../src/features/generation/activeGeneration';
import type { AuthSession, AuthSurface } from '../src/config/firebase';

const AVAILABLE: AuthSurface = {
  status: 'available',
  auth: { name: 'fake-auth' } as never,
  app: { name: 'vton' } as never,
  config: {
    apiKey: 'k',
    authDomain: 'd',
    projectId: 'p',
    appId: 'a',
  },
};

const UNAVAILABLE: AuthSurface = {
  status: 'unavailable',
  reason: 'FIREBASE_NOT_CONFIGURED',
};

describe('account session isolation (C03 signout / expired session)', () => {
  beforeEach(() => {
    // A previous account's state, as it would look right before sign-out.
    // The overrides are the OPPOSITE of each flag's declared default, so the
    // test can first prove the override took effect and then prove clearing
    // restores the default. Overriding a flag to its own default value would
    // make both halves vacuous.
    setActiveGeneration('generation-from-first-account');
    applyFeatureFlagPayload({
      TRYON_MAX: !DEFAULT_FEATURE_FLAGS.TRYON_MAX,
      NEW_QUALITY_GATE: !DEFAULT_FEATURE_FLAGS.NEW_QUALITY_GATE,
    });
  });

  test('clearAccountBoundState drops the active generation and restores flag defaults', () => {
    expect(getActiveGeneration()).toBe('generation-from-first-account');
    // Only flags outside UNSUPPORTED_CAPABILITIES are read: isFeatureEnabled
    // returns false for a gated capability regardless of the override map, so
    // asserting on one of those could never fail and would prove nothing.
    expect(isFeatureEnabled('TRYON_MAX')).toBe(
      !DEFAULT_FEATURE_FLAGS.TRYON_MAX,
    );
    expect(isFeatureEnabled('NEW_QUALITY_GATE')).toBe(
      !DEFAULT_FEATURE_FLAGS.NEW_QUALITY_GATE,
    );

    clearAccountBoundState();

    expect(getActiveGeneration()).toBeNull();
    // "Restores the declared defaults" is the actual contract. The
    // expectation is derived from the declaration rather than restated as a
    // literal, so a future default change (as C22 made to MULTI_ITEM) cannot
    // invalidate this test.
    expect(isFeatureEnabled('TRYON_MAX')).toBe(DEFAULT_FEATURE_FLAGS.TRYON_MAX);
    expect(isFeatureEnabled('SHARE_IMPORT_ANDROID')).toBe(
      DEFAULT_FEATURE_FLAGS.SHARE_IMPORT_ANDROID,
    );
    expect(isFeatureEnabled('NEW_QUALITY_GATE')).toBe(
      DEFAULT_FEATURE_FLAGS.NEW_QUALITY_GATE,
    );
  });

  test('signOutAccount on an unavailable surface reports unavailable and touches nothing', async () => {
    const signOut = jest.fn();
    const clear = jest.fn();

    await expect(
      signOutAccount({ surface: UNAVAILABLE, signOut, clear }),
    ).resolves.toBe('unavailable');

    expect(signOut).not.toHaveBeenCalled();
    expect(clear).not.toHaveBeenCalled();
    expect(getActiveGeneration()).toBe('generation-from-first-account');
  });

  test('signOutAccount drops the SDK credential then clears account-bound state', async () => {
    const signOut = jest.fn().mockResolvedValue(undefined);
    const clear = jest.fn();

    await expect(
      signOutAccount({ surface: AVAILABLE, signOut, clear }),
    ).resolves.toBe('signed-out');

    expect(signOut).toHaveBeenCalledWith(AVAILABLE.auth);
    expect(clear).toHaveBeenCalledTimes(1);
  });

  test('a session that ends without the user asking clears state on the transition', () => {
    const listeners: ((session: AuthSession) => void)[] = [];
    const cleared = jest.fn();
    const unsubscribe = jest.fn();
    trackAccountIsolation(listener => {
      listeners.push(listener);
      return unsubscribe;
    }, cleared);

    // A live session keeps its pointers.
    listeners.forEach(listener => listener({ status: 'loading' }));
    listeners.forEach(listener =>
      listener({
        status: 'signed-in',
        uid: 'uid-1',
        getIdToken: () => Promise.resolve('token'),
      }),
    );
    expect(cleared).not.toHaveBeenCalled();

    // Expiry/revocation arrives as signed-out: it must clear exactly once.
    listeners.forEach(listener => listener({ status: 'signed-out' }));
    expect(cleared).toHaveBeenCalledTimes(1);
    listeners.forEach(listener => listener({ status: 'signed-out' }));
    expect(cleared).toHaveBeenCalledTimes(1);

    // A second account signing in and out clears again, not the first account's cache.
    listeners.forEach(listener =>
      listener({
        status: 'signed-in',
        uid: 'uid-2',
        getIdToken: () => Promise.resolve('token-2'),
      }),
    );
    listeners.forEach(listener => listener({ status: 'signed-out' }));
    expect(cleared).toHaveBeenCalledTimes(2);
  });

  test('trackAccountIsolation returns the subscription unsubscribe', () => {
    const unsubscribe = jest.fn();
    const stop = trackAccountIsolation(() => unsubscribe);

    stop();

    expect(unsubscribe).toHaveBeenCalledTimes(1);
  });
});
