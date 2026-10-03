import {
  FIREBASE_APP_NAME,
  PLACEHOLDER_PREFIX,
  getAuthSurface,
  initializeFirebase,
  isFirebaseConfigured,
  readFirebaseConfig,
  subscribeToAuthSession,
  type AuthSession,
  type AuthSurface,
  type FirebaseConfig,
} from '../src/config/firebase';

const ENV_KEYS = [
  'EXPO_PUBLIC_FIREBASE_API_KEY',
  'EXPO_PUBLIC_FIREBASE_AUTH_DOMAIN',
  'EXPO_PUBLIC_FIREBASE_PROJECT_ID',
  'EXPO_PUBLIC_FIREBASE_APP_ID',
] as const;

// Obviously fake values: the real Firebase client config ships in the bundle
// and belongs to the owner, so no real project is referenced from a test.
const REAL_CONFIG: FirebaseConfig = {
  apiKey: 'test-only-api-key',
  authDomain: 'test-only.firebaseapp.invalid',
  projectId: 'test-only-project',
  appId: '1:000000000000:web:testonly',
};

type FirebaseGlobalEnv = {
  EXPO_PUBLIC_FIREBASE_API_KEY?: string;
  EXPO_PUBLIC_FIREBASE_AUTH_DOMAIN?: string;
  EXPO_PUBLIC_FIREBASE_PROJECT_ID?: string;
  EXPO_PUBLIC_FIREBASE_APP_ID?: string;
};

/** Run `body` with every EXPO_PUBLIC_FIREBASE_* var removed from both seams. */
function withNoFirebaseEnv<T>(body: () => T): T {
  const globalEnv = globalThis as FirebaseGlobalEnv;
  const saved = ENV_KEYS.map(key => [
    key,
    process.env[key],
    globalEnv[key],
  ] as const);
  for (const key of ENV_KEYS) {
    delete process.env[key];
    delete globalEnv[key];
  }
  try {
    return body();
  } finally {
    for (const [key, fromProcess, fromGlobal] of saved) {
      if (fromProcess === undefined) {
        delete process.env[key];
      } else {
        process.env[key] = fromProcess;
      }
      if (fromGlobal === undefined) {
        delete globalEnv[key];
      } else {
        globalEnv[key] = fromGlobal;
      }
    }
  }
}

describe('firebase auth surface', () => {
  test('boots unavailable instead of throwing when no config is present', () => {
    withNoFirebaseEnv(() => {
      let surface: AuthSurface | null = null;
      const config = readFirebaseConfig();
      expect(config.apiKey).toBe(`${PLACEHOLDER_PREFIX}API_KEY`);
      expect(config.projectId).toBe(`${PLACEHOLDER_PREFIX}PROJECT_ID`);

      expect(() => {
        surface = getAuthSurface();
      }).not.toThrow();
      expect(surface).toEqual({
        status: 'unavailable',
        reason: 'FIREBASE_NOT_CONFIGURED',
      });
    });
  });

  test('treats a partially filled config as unconfigured', () => {
    withNoFirebaseEnv(() => {
      expect(isFirebaseConfigured()).toBe(false);
      // Every field real is the only configured case.
      expect(isFirebaseConfigured(REAL_CONFIG)).toBe(true);
      // One placeholder is enough to fail the whole config.
      expect(
        isFirebaseConfigured({ ...REAL_CONFIG, projectId: `${PLACEHOLDER_PREFIX}PROJECT_ID` }),
      ).toBe(false);
      expect(
        isFirebaseConfigured({ ...REAL_CONFIG, appId: `${PLACEHOLDER_PREFIX}APP_ID` }),
      ).toBe(false);
      // Whitespace-only is empty, not configured.
      expect(isFirebaseConfigured({ ...REAL_CONFIG, apiKey: '   ' })).toBe(false);
    });
  });

  test('emits exactly one unavailable session on the unavailable surface', () => {
    withNoFirebaseEnv(() => {
      initializeFirebase();
      const seen: AuthSession[] = [];
      const unsubscribe = subscribeToAuthSession(session => {
        seen.push(session);
      });
      expect(seen).toEqual([{ status: 'unavailable' }]);
      expect(() => unsubscribe()).not.toThrow();
    });
  });

  test('an available surface yields a usable auth and leads with loading', () => {
    const surface = initializeFirebase(REAL_CONFIG);
    expect(surface.status).toBe('available');
    if (surface.status !== 'available') {
      throw new Error('expected an available surface');
    }
    expect(surface.app.name).toBe(FIREBASE_APP_NAME);
    expect(typeof surface.auth).toBe('object');
    expect(surface.config).toEqual(REAL_CONFIG);

    const seen: AuthSession[] = [];
    const unsubscribe = subscribeToAuthSession(session => {
      seen.push(session);
    });
    // The leading `loading` is what stops a restored session from flashing
    // the sign-in screen.
    expect(seen[0]).toEqual({ status: 'loading' });
    expect(typeof unsubscribe).toBe('function');
    expect(() => unsubscribe()).not.toThrow();
  });

  test('is idempotent for the same resolved config', () => {
    const first = initializeFirebase(REAL_CONFIG);
    const second = initializeFirebase(REAL_CONFIG);
    expect(second).toBe(first);
    expect(initializeFirebase({ ...REAL_CONFIG })).toBe(first);
  });
});
