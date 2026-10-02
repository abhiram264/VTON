/**
 * Firebase auth session surface.
 *
 * ADR-007 decides the AUTHENTICATOR, not this integration: "Use Firebase
 * Authentication for external authentication", with the backend mapping the
 * external issuer and subject to an application-owned user UUID and Firebase
 * UID explicitly NOT becoming domain identity. That is why nothing here keys
 * domain data on `uid`. ADR-007 lists no SDK options; choosing the JS SDK
 * (@firebase/app + @firebase/auth) is this run's mandate, not an ADR option.
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

type FirebaseEnvKey =
  | 'EXPO_PUBLIC_FIREBASE_API_KEY'
  | 'EXPO_PUBLIC_FIREBASE_AUTH_DOMAIN'
  | 'EXPO_PUBLIC_FIREBASE_PROJECT_ID'
  | 'EXPO_PUBLIC_FIREBASE_APP_ID';

/**
 * React Native defines `__DEV__` on the global object: true under Metro dev
 * and Jest, false in release bundles. Read through globalThis so this module
 * does not depend on an ambient TypeScript global.
 */
export function isReleaseBuild(): boolean {
  return (globalThis as {__DEV__?: boolean}).__DEV__ === false;
}

// Static member reads only. The Babel inliner (mobile/babel.config.js)
// substitutes `process.env.EXPO_PUBLIC_*` member accesses with string literals
// at bundle time; a computed read like `process.env[key]` survives the
// transform untouched and leaves a release bundle with no Firebase config.
function readEnv(key: FirebaseEnvKey): string {
  // EXPO_PUBLIC_* globals are populated by the bundler at runtime; they are
  // not part of the TypeScript lib, hence the named cast.
  const globals = globalThis as Partial<Record<FirebaseEnvKey, string>>;
  switch (key) {
    case 'EXPO_PUBLIC_FIREBASE_API_KEY':
      return (
        process.env.EXPO_PUBLIC_FIREBASE_API_KEY ??
        globals.EXPO_PUBLIC_FIREBASE_API_KEY ??
        ''
      ).trim();
    case 'EXPO_PUBLIC_FIREBASE_AUTH_DOMAIN':
      return (
        process.env.EXPO_PUBLIC_FIREBASE_AUTH_DOMAIN ??
        globals.EXPO_PUBLIC_FIREBASE_AUTH_DOMAIN ??
        ''
      ).trim();
    case 'EXPO_PUBLIC_FIREBASE_PROJECT_ID':
      return (
        process.env.EXPO_PUBLIC_FIREBASE_PROJECT_ID ??
        globals.EXPO_PUBLIC_FIREBASE_PROJECT_ID ??
        ''
      ).trim();
    case 'EXPO_PUBLIC_FIREBASE_APP_ID':
      return (
        process.env.EXPO_PUBLIC_FIREBASE_APP_ID ??
        globals.EXPO_PUBLIC_FIREBASE_APP_ID ??
        ''
      ).trim();
  }
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
 * Idempotent for a given resolved config, and total in development: a bad or
 * absent config yields the unavailable surface instead of throwing, so the app
 * always boots. In a release build an absent or placeholder config fails
 * closed by throwing — a shipped app must never run with template values.
 * The app is looked up by name before initialization so a changed config
 * reuses the existing instance instead of throwing "already exists".
 */
export function initializeFirebase(
  config: FirebaseConfig = readFirebaseConfig(),
): AuthSurface {
  const key = [config.apiKey, config.authDomain, config.projectId, config.appId]
    .map(value => (value ?? '').trim())
    .join('|');
  const configured = isFirebaseConfigured(config);
  // Checked before the cache so a cached unavailable surface from a
  // development call cannot mask the release failure.
  if (!configured && isReleaseBuild()) {
    throw new Error(
      'Firebase is not configured for a release build: set EXPO_PUBLIC_FIREBASE_*',
    );
  }
  if (surface !== null && surfaceKey === key) {
    return surface;
  }
  if (!configured) {
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
 *
 * PERSISTENCE: the session is IN-MEMORY on React Native, so a process or
 * device restart signs the user out and there is no restored session to
 * recover. `getAuth(app)` is called with no deps, and the SDK then builds an
 * empty persistence hierarchy (`hierarchy = deps?.persistence || []`) whose
 * user manager falls back to in-memory storage. The React Native entry DOES
 * export `getReactNativePersistence`, but it is never constructed unless a
 * caller passes a storage adapter, and that adapter would be
 * @react-native-async-storage/async-storage - an OPTIONAL peer of
 * @firebase/auth that this app deliberately does not install. In-memory
 * persistence is therefore a chosen consequence of taking no storage
 * dependency, not an oversight; add that package and pass the persistence
 * explicitly if a durable session is ever required.
 *
 * The leading `loading` still earns its place: even in-memory, the SDK
 * resolves its initial auth state asynchronously, so without it a
 * already-signed-in user would flash the sign-in screen for one frame.
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
