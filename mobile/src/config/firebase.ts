/**
 * Firebase auth session surface (ADR-007 option A).
 *
 * (a) Firebase *client* configuration is public by design. The apiKey and
 *     authDomain below ship inside the app bundle and are not a secret; the
 *     trust boundary is the ID token, which the backend verifies. No provider
 *     key, private key or service-account credential is ever read here, and
 *     none may be added to this file.
 * (b) A real Firebase project owned by the project owner is required for the
 *     auth surface to become `available`. There is no emulator fallback.
 * (c) Missing or placeholder configuration yields an EXPLICITLY unavailable
 *     surface. That is a documented local-dev mode, never a silent auth
 *     bypass: no request is ever sent without a token, and the caller can
 *     tell "no auth surface" apart from "signed out".
 * (d) `projectId` must equal the backend's FIREBASE_PROJECT_ID, because the
 *     backend pins the JWT issuer and audience to that project. A mismatched
 *     project id boots healthy and then 401s every request, so a placeholder
 *     is treated as unconfigured rather than as a usable value.
 */
import { getApps, initializeApp, type FirebaseApp } from '@firebase/app';
import { getAuth, onAuthStateChanged, type Auth } from '@firebase/auth';

export type FirebaseConfig = {
  apiKey: string;
  authDomain: string;
  projectId: string;
  appId: string;
};

export const PLACEHOLDER_PREFIX = 'REPLACE_WITH_';

export const FIREBASE_APP_NAME = 'vton';

const CONFIG_KEYS = {
  apiKey: 'EXPO_PUBLIC_FIREBASE_API_KEY',
  authDomain: 'EXPO_PUBLIC_FIREBASE_AUTH_DOMAIN',
  projectId: 'EXPO_PUBLIC_FIREBASE_PROJECT_ID',
  appId: 'EXPO_PUBLIC_FIREBASE_APP_ID',
} as const;

// The Expo public env seam as a checked object, mirroring the apiConfig.ts
// `globalThis as {...}` convention. process.env wins; globalThis is the
// fallback for runtimes that do not populate it.
const firebaseGlobals = () =>
  globalThis as {
    EXPO_PUBLIC_FIREBASE_API_KEY?: string;
    EXPO_PUBLIC_FIREBASE_AUTH_DOMAIN?: string;
    EXPO_PUBLIC_FIREBASE_PROJECT_ID?: string;
    EXPO_PUBLIC_FIREBASE_APP_ID?: string;
  };

function readEnv(
  key: keyof ReturnType<typeof firebaseGlobals>,
): string {
  const globals = firebaseGlobals();
  return (process.env[key] ?? globals[key] ?? '').trim();
}

const UNAVAILABLE: AuthSurface = {
  status: 'unavailable',
  reason: 'FIREBASE_NOT_CONFIGURED',
};

let surface: AuthSurface | null = null;
let surfaceKey = '';

export function readFirebaseConfig(): FirebaseConfig {
  return {
    apiKey: readEnv(CONFIG_KEYS.apiKey) || `${PLACEHOLDER_PREFIX}API_KEY`,
    authDomain: readEnv(CONFIG_KEYS.authDomain) || `${PLACEHOLDER_PREFIX}AUTH_DOMAIN`,
    projectId: readEnv(CONFIG_KEYS.projectId) || `${PLACEHOLDER_PREFIX}PROJECT_ID`,
    appId: readEnv(CONFIG_KEYS.appId) || `${PLACEHOLDER_PREFIX}APP_ID`,
  };
}

export function isFirebaseConfigured(
  config: FirebaseConfig = readFirebaseConfig(),
): boolean {
  const values = [
    config.apiKey,
    config.authDomain,
    config.projectId,
    config.appId,
  ];
  return values.every(value => {
    const trimmed = (value ?? '').trim();
    return trimmed.length > 0 && !trimmed.startsWith(PLACEHOLDER_PREFIX);
  });
}

export type AuthSurface =
  | {
      status: 'available';
      app: FirebaseApp;
      auth: Auth;
      config: FirebaseConfig;
    }
  | { status: 'unavailable'; reason: 'FIREBASE_NOT_CONFIGURED' };

export type AuthSession =
  | { status: 'loading' }
  | { status: 'unavailable' }
  | { status: 'signed-out' }
  | { status: 'signed-in'; uid: string; getIdToken: () => Promise<string> };

/**
 * Idempotent for a given resolved config, and total: a bad or absent config
 * yields the unavailable surface instead of throwing, so the app always boots.
 * The app is looked up by name before initialization so a changed config
 * reuses the existing instance instead of throwing "already exists".
 */
export function initializeFirebase(
  config: FirebaseConfig = readFirebaseConfig(),
): AuthSurface {
  const key = [config.apiKey, config.authDomain, config.projectId, config.appId]
    .map(value => (value ?? '').trim())
    .join('|');
  if (surface !== null && surfaceKey === key) {
    return surface;
  }
  if (!isFirebaseConfigured(config)) {
    surface = UNAVAILABLE;
    surfaceKey = key;
    return surface;
  }
  try {
    const existing = getApps().find(app => app.name === FIREBASE_APP_NAME);
    const app = existing ?? initializeApp(config, FIREBASE_APP_NAME);
    surface = { status: 'available', app, auth: getAuth(app), config };
  } catch {
    // A failed init must not take the app down; fall back to the same
    // explicit unavailable surface used for missing configuration.
    surface = UNAVAILABLE;
  }
  surfaceKey = key;
  return surface;
}

export function getAuthSurface(): AuthSurface {
  return surface ?? initializeFirebase();
}

/**
 * On an unavailable surface this emits exactly one `unavailable` session.
 * On an available surface the leading `loading` is what stops a restored
 * session from flashing the sign-in screen before Firebase reports it.
 */
export function subscribeToAuthSession(
  listener: (session: AuthSession) => void,
): () => void {
  const current = getAuthSurface();
  if (current.status === 'unavailable') {
    listener({ status: 'unavailable' });
    return () => {};
  }
  listener({ status: 'loading' });
  return onAuthStateChanged(current.auth, user => {
    if (user === null) {
      listener({ status: 'signed-out' });
      return;
    }
    listener({
      status: 'signed-in',
      uid: user.uid,
      getIdToken: () => user.getIdToken(),
    });
  });
}
