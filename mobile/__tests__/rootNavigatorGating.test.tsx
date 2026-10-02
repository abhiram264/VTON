import { act, render, screen } from '@testing-library/react-native';
import { NavigationContainer, StackActions, createNavigationContainerRef } from '@react-navigation/native';
import RootNavigator, { type RootStackParamList } from '../src/navigation/RootNavigator';
import { productionApiConfig } from '../src/config/apiConfig';
import type { AuthSession } from '../src/config/firebase';
import type { SharePayload } from '../src/features/share/types';

jest.mock('../src/features/vault/vaultApi', () => ({ vaultApi: { list: jest.fn().mockResolvedValue([]) } }));
jest.mock('../src/features/collections/collectionsApi', () => {
  const stub = {
    list: jest.fn().mockResolvedValue([]),
    create: jest.fn(),
    rename: jest.fn(),
    remove: jest.fn(),
    addItem: jest.fn(),
    removeItem: jest.fn(),
  };
  // ShareImportScreen builds its own client from the same config so its picker
  // cannot resolve a different API base URL; the factory must exist here too.
  return { collectionsApi: stub, createCollectionsApi: () => stub };
});
jest.mock('../src/features/looks/looksApi', () => ({ looksApi: { list: jest.fn().mockResolvedValue([]) } }));

// Probe: records every payload the navigator hands to the Share Import
// screen, so the test asserts the payload SURVIVES the sign-in transition.
const receivedPayloads: SharePayload[] = [];
jest.mock('../src/screens/ShareImportScreen', () => {
  const { Text } = require('react-native');
  return {
    __esModule: true,
    default: ({ payload }: { payload: SharePayload }) => {
      receivedPayloads.push(payload);
      return <Text>PAYLOAD_PROBE</Text>;
    },
  };
});

// Every test in this file mounts the real navigator and awaits rendered
// queries. The default 5s per-test timeout was measured tripping on a loaded
// box (concurrent Maven + jest) while the work itself is milliseconds, which
// reported a pass as a failure. The timeout is raised rather than any
// assertion relaxed, so a genuinely hung navigator still fails here.
jest.setTimeout(30_000);

const navigationRef = createNavigationContainerRef<RootStackParamList>();

function Harness({ session }: { session: AuthSession }) {
  return (
    <NavigationContainer ref={navigationRef}>
      <RootNavigator session={session} />
    </NavigationContainer>
  );
}

function treeState(): 'holding' | 'sign-in' | 'main-tabs' | 'unknown' {
  if (screen.queryByTestId('auth-holding')) return 'holding';
  if (screen.queryByRole('header', { name: 'Sign In' })) return 'sign-in';
  if (screen.queryByRole('tab', { name: /HOME/ })) return 'main-tabs';
  return 'unknown';
}

test('loading renders the holding view, not sign-in and not MainTabs', async () => {
  await render(<Harness session={{ status: 'loading' }} />);
  expect(screen.queryByTestId('auth-holding')).toBeTruthy();
  expect(screen.queryByRole('header', { name: 'Sign In' })).toBeNull();
  expect(screen.queryByRole('tab', { name: /HOME/ })).toBeNull();
});

test('signed-out renders the sign-in screen and not MainTabs', async () => {
  await render(<Harness session={{ status: 'signed-out' }} />);
  expect(screen.getByRole('header', { name: 'Sign In' })).toBeTruthy();
  expect(screen.queryByRole('tab', { name: /HOME/ })).toBeNull();
  expect(screen.queryByTestId('auth-holding')).toBeNull();
});

test('unavailable passes through to the app: MainTabs, not the sign-in wall', async () => {
  await render(<Harness session={{ status: 'unavailable' }} />);
  expect(await screen.findByRole('tab', { name: /HOME/ })).toBeTruthy();
  expect(screen.queryByRole('header', { name: 'Sign In' })).toBeNull();
  expect(screen.queryByTestId('auth-holding')).toBeNull();
});

// The honest half of the unavailable pass-through: rendering opens up, the
// token seam does not. If anyone weakens apiConfig.getAccessToken, the
// pass-through above would become a real bearer-less path — this fails first.
test('getAccessToken still rejects when the surface is unavailable', async () => {
  await expect(productionApiConfig.getAccessToken()).rejects.toThrow('Access token is not configured');
});

test('signed-out and unavailable genuinely diverge: only signed-out walls the app', async () => {
  await render(<Harness session={{ status: 'signed-out' }} />);
  expect(screen.getByRole('header', { name: 'Sign In' })).toBeTruthy();
  expect(screen.queryByRole('tab', { name: /HOME/ })).toBeNull();
});

test('signed-in renders MainTabs and not the sign-in screen', async () => {
  await render(<Harness session={{ status: 'signed-in', uid: 'u1', getIdToken: async () => 't' }} />);
  expect(await screen.findByRole('tab', { name: /HOME/ })).toBeTruthy();
  expect(screen.queryByRole('header', { name: 'Sign In' })).toBeNull();
});

test('loading to signed-in never shows the sign-in screen at any committed state', async () => {
  const utils = await render(<Harness session={{ status: 'loading' }} />);
  const states = [treeState()];
  utils.rerender(<Harness session={{ status: 'signed-in', uid: 'u1', getIdToken: async () => 't' }} />);
  expect(await screen.findByRole('tab', { name: /HOME/ })).toBeTruthy();
  states.push(treeState());
  expect(states).not.toContain('sign-in');
  expect(states[0]).toBe('holding');
  expect(states[states.length - 1]).toBe('main-tabs');
});

test('a share pushed while unavailable reaches the Share Import screen with the payload', async () => {
  receivedPayloads.length = 0;
  const utils = await render(<Harness session={{ status: 'unavailable' }} />);

  const payload: SharePayload = { kind: 'url', url: 'https://example.com/item/2' };
  await act(async () => {
    for (let i = 0; i < 100 && !navigationRef.isReady(); i += 1) {
      await Promise.resolve();
    }
    navigationRef.dispatch(StackActions.push('ShareImport', { payload }));
  });

  // The unavailable pass-through must not strand the payload behind a null.
  expect(receivedPayloads).toEqual([payload]);
  utils.unmount();
});

test('a share pushed while signed-out is not consumed and survives to sign-in', async () => {
  receivedPayloads.length = 0;
  const utils = await render(<Harness session={{ status: 'signed-out' }} />);
  expect(screen.getByRole('header', { name: 'Sign In' })).toBeTruthy();

  const payload: SharePayload = { kind: 'url', url: 'https://example.com/item/1' };
  // AppShell queues by-name pushes until the ref is ready; mirror that drain.
  await act(async () => {
    for (let i = 0; i < 100 && !navigationRef.isReady(); i += 1) {
      await Promise.resolve();
    }
    navigationRef.dispatch(StackActions.push('ShareImport', { payload }));
  });
  expect(navigationRef.isReady()).toBe(true);
  expect(navigationRef.getState()?.routes.map(r => r.name)).toContain('ShareImport');
  // Signed-out consumer must not receive the payload; sign-in still shown.
  expect(screen.getByRole('header', { name: 'Sign In' })).toBeTruthy();
  expect(receivedPayloads).toHaveLength(0);

  // Sign in happens in place: the same tree, the pushed route still on the
  // stack, the payload params untouched across the transition.
  await act(async () => {
    await utils.rerender(<Harness session={{ status: 'signed-in', uid: 'u1', getIdToken: async () => 't' }} />);
  });
  // The exact payload object survives the transition, still consumable.
  expect(receivedPayloads).toEqual([payload]);
});
