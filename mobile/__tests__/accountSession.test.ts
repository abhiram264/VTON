import {
  clearAccountBoundState,
  signOutAccount,
  trackAccountIsolation,
} from '../src/features/auth/accountSession';
import {
  applyFeatureFlagPayload,
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
    // TRYON_MAX is a supported flag whose declared default is true, so it can
    // show an override being reset; MULTI_ITEM is now a C22 unsupported
    // capability that is always off and would make the reset untestable.
    setActiveGeneration('generation-from-first-account');
    applyFeatureFlagPayload({ TRYON_MAX: false, NEW_QUALITY_GATE: true });
  });

  test('clearAccountBoundState drops the active generation and restores flag defaults', () => {
    expect(getActiveGeneration()).toBe('generation-from-first-account');
    expect(isFeatureEnabled('TRYON_MAX')).toBe(false);

    clearAccountBoundState();

    expect(getActiveGeneration()).toBeNull();
    expect(isFeatureEnabled('TRYON_MAX')).toBe(true);
    expect(isFeatureEnabled('NEW_QUALITY_GATE')).toBe(false);
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
