import {productionApiConfig} from '../src/config/apiConfig';
import {
  initializeFirebase,
  type AuthSurface,
  type FirebaseConfig,
} from '../src/config/firebase';

// Obviously fake values: the real Firebase client config ships in the bundle
// and belongs to the owner, so no real project is referenced from a test.
const REAL_CONFIG: FirebaseConfig = {
  apiKey: 'test-only-api-key',
  authDomain: 'test-only.firebaseapp.invalid',
  projectId: 'test-only-project',
  appId: '1:000000000000:web:testonly',
};

const ID_TOKEN = 'test-only-firebase-id-token';

const ENV_KEYS = [
  'EXPO_PUBLIC_FIREBASE_API_KEY',
  'EXPO_PUBLIC_FIREBASE_AUTH_DOMAIN',
  'EXPO_PUBLIC_FIREBASE_PROJECT_ID',
  'EXPO_PUBLIC_FIREBASE_APP_ID',
] as const;

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
    globalEnv[key],
    process.env[key],
  ] as const);
  for (const key of ENV_KEYS) {
    delete globalEnv[key];
    delete process.env[key];
  }
  try {
    return body();
  } finally {
    for (const [key, globalValue, envValue] of saved) {
      if (globalValue !== undefined) {
        globalEnv[key] = globalValue;
      }
      if (envValue !== undefined) {
        process.env[key] = envValue;
      }
    }
  }
}

/**
 * The auth surface exposes `currentUser` as a read-only property on the real
 * Auth object, so a user is installed the way the SDK itself would: as an own
 * data property shadowing the prototype getter. Everything else stays the
 * real surface produced by initializeFirebase. Pass `null` for signed out.
 */
function setCurrentUser(surface: AuthSurface, currentUser: unknown) {
  if (surface.status !== 'available') {
    throw new Error('expected an available surface');
  }
  Object.defineProperty(surface.auth, 'currentUser', {
    configurable: true,
    value: currentUser,
  });
}

describe('productionApiConfig.getAccessToken (Firebase ID token seam)', () => {
  test('refuses a token when the auth surface is unavailable', async () => {
    await withNoFirebaseEnv(async () => {
      // Re-initializing with an unconfigured config pins the surface to
      // `unavailable` regardless of the order the module was reached in.
      initializeFirebase();
      await expect(
        Promise.resolve().then(() => productionApiConfig.getAccessToken()),
      ).rejects.toThrow(/not configured/i);
    });
  });

  test('resolves the signed-in user ID token', async () => {
    setCurrentUser(initializeFirebase(REAL_CONFIG), {
      uid: 'test-uid',
      getIdToken: () => Promise.resolve(ID_TOKEN),
    });
    await expect(productionApiConfig.getAccessToken()).resolves.toBe(ID_TOKEN);
  });

  test('refuses a token when the surface is available but nobody is signed in', async () => {
    setCurrentUser(initializeFirebase(REAL_CONFIG), null);
    await expect(productionApiConfig.getAccessToken()).rejects.toThrow(
      /not configured/i,
    );
  });

  test('never writes the token to console output', async () => {
    setCurrentUser(initializeFirebase(REAL_CONFIG), {
      uid: 'test-uid',
      getIdToken: () => Promise.resolve(ID_TOKEN),
    });
    const spies = (['log', 'info', 'warn', 'error', 'debug'] as const).map(
      method => jest.spyOn(console, method).mockImplementation(() => {}),
    );
    try {
      await expect(productionApiConfig.getAccessToken()).resolves.toBe(
        ID_TOKEN,
      );
      for (const spy of spies) {
        for (const call of spy.mock.calls) {
          expect(JSON.stringify(call)).not.toContain(ID_TOKEN);
        }
      }
    } finally {
      for (const spy of spies) {
        spy.mockRestore();
      }
    }
  });
});
