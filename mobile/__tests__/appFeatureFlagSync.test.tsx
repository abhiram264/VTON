/**
 * C22 item 5/6 (mobile integration, app level): rendering the app entry must
 * start the backend capability sync, so an operator's server-side flag change
 * reaches the running client. This drives the real AppShell effect with a
 * signed-in auth surface and asserts the capability request is issued and
 * applied. RootNavigator is stubbed so the test isolates the app-entry wiring.
 *
 * Pre-fix (before AppShell started the sync) no request is made and this test
 * fails; that is the regression it defends.
 */
import { render } from '@testing-library/react-native';
import type { AuthSession } from '../src/config/firebase';

jest.mock('../src/config/firebase', () => {
  const actual = jest.requireActual('../src/config/firebase');
  const session: AuthSession = {
    status: 'signed-in',
    uid: 'user-1',
    getIdToken: () => Promise.resolve('token-1'),
  };
  return {
    ...actual,
    initializeFirebase: () => ({ status: 'available' }),
    getAuthSurface: () => ({
      status: 'available',
      app: {},
      auth: { currentUser: { getIdToken: () => Promise.resolve('token-1') } },
      config: {},
    }),
    subscribeToAuthSession: (listener: (session: AuthSession) => void) => {
      listener(session);
      return () => {};
    },
  };
});

jest.mock('../src/navigation/RootNavigator', () => {
  const { Text } = jest.requireActual('react-native');
  return {
    __esModule: true,
    default: () => <Text>RootStub</Text>,
  };
});

jest.mock('../src/features/share/linkHandler', () => ({
  startShareLinkHandling: () => () => {},
}));

jest.setTimeout(30_000);

const API_URL = 'https://api.example.test';

function jsonResponse(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: () => 'application/json' },
    json: () => Promise.resolve(body),
    text: () => Promise.resolve(JSON.stringify(body)),
  } as unknown as Response;
}

function requestUrl(input: RequestInfo | URL): string {
  if (typeof input === 'string') {
    return input;
  }
  if (input instanceof URL) {
    return input.toString();
  }
  return input.url;
}

test('mounting the app entry fetches the backend capability snapshot', async () => {
  const savedApiUrl = process.env.EXPO_PUBLIC_API_URL;
  process.env.EXPO_PUBLIC_API_URL = API_URL;
  const fetchMock = jest.fn().mockResolvedValue(
    jsonResponse(200, { flags: { SHARE_IMPORT_ANDROID: false }, overrides: {} }),
  );
  globalThis.fetch = fetchMock as typeof fetch;

  const {
    applyFeatureFlagPayload,
    DEFAULT_FEATURE_FLAGS,
    isFeatureEnabled,
  } = require('../src/config/featureFlags');
  applyFeatureFlagPayload(DEFAULT_FEATURE_FLAGS);

  try {
    const AppShell = require('../src/app/AppShell').default as React.ComponentType;
    await render(<AppShell />);
    for (let i = 0; i < 6; i += 1) {
      await Promise.resolve();
    }

    const urls = fetchMock.mock.calls.map(call => requestUrl(call[0]));
    expect(urls).toContain(`${API_URL}/feature-flags`);
    // The backend disable reached the client.
    expect(isFeatureEnabled('SHARE_IMPORT_ANDROID')).toBe(false);
  } finally {
    applyFeatureFlagPayload(DEFAULT_FEATURE_FLAGS);
    if (savedApiUrl === undefined) delete process.env.EXPO_PUBLIC_API_URL;
    else process.env.EXPO_PUBLIC_API_URL = savedApiUrl;
  }
});
