import { act, render, screen, waitFor } from '@testing-library/react-native';
import { NavigationContainer, createNavigationContainerRef } from '@react-navigation/native';
import RootNavigator, { type RootStackParamList } from '../src/navigation/RootNavigator';
import { studioApi } from '../src/features/studio/studioApi';
import type { AuthSession } from '../src/config/firebase';

// A consumed Studio route param must not survive in the navigator: leaving and
// re-entering the Studio tab would otherwise re-clone the same Look (and, for a
// Vault selection, silently bring back a garment the user removed). This mounts
// the REAL navigator, so it defends the RootNavigator wiring itself rather than
// the screen's callback in isolation.
jest.mock('../src/features/studio/studioApi', () => ({
  ...jest.requireActual('../src/features/studio/studioApi'),
  studioApi: {
    get: jest.fn(),
    activeDraft: jest.fn(),
    create: jest.fn(),
    createFromLook: jest.fn(),
    addItem: jest.fn(),
    removeItem: jest.fn(),
    replaceItem: jest.fn(),
    command: jest.fn(),
    generate: jest.fn(),
  },
}));
jest.mock('../src/features/vault/vaultApi', () => ({ vaultApi: { list: jest.fn().mockResolvedValue([]), image: jest.fn() } }));
jest.mock('../src/features/collections/collectionsApi', () => ({ collectionsApi: { list: jest.fn().mockResolvedValue([]) } }));
jest.mock('../src/features/looks/looksApi', () => ({ looksApi: { list: jest.fn().mockResolvedValue([]) } }));
jest.mock('../src/features/my-model/avatarApi', () => ({ avatarApi: { list: jest.fn().mockResolvedValue([]) } }));

jest.setTimeout(30_000);

const navigationRef = createNavigationContainerRef<RootStackParamList>();
const signedIn: AuthSession = { status: 'signed-in' } as AuthSession;

beforeEach(() => {
  jest.clearAllMocks();
  jest.mocked(studioApi.activeDraft).mockResolvedValue({ sessionId: 'draft-1', updatedAt: '2026-10-03T00:00:00Z', itemIds: [] });
  jest.mocked(studioApi.create).mockResolvedValue({ sessionId: 'draft-2', updatedAt: '2026-10-03T00:00:00Z', itemIds: [] });
  jest.mocked(studioApi.createFromLook).mockResolvedValue({
    sessionId: 'cloned-1',
    updatedAt: '2026-10-03T00:00:00Z',
    itemIds: [],
    unresolved: [],
    avatarUnavailable: false,
  });
});

function renderApp() {
  return render(
    <NavigationContainer ref={navigationRef}>
      <RootNavigator session={signedIn} />
    </NavigationContainer>,
  );
}

test('a cloned source Look is consumed once and is not re-cloned on tab re-entry', async () => {
  await renderApp();
  await screen.findByRole('header', { name: 'Home' });

  await act(async () => {
    navigationRef.navigate('MainTabs', { screen: 'StudioTab', params: { sourceLookId: 'look-1' } });
  });
  await waitFor(() => expect(studioApi.createFromLook).toHaveBeenCalledWith('look-1'));
  await screen.findByRole('header', { name: 'Studio' });

  // The navigator must not still be carrying the param it handed to the screen.
  const params = navigationRef.getCurrentRoute()?.params as
    | { sourceLookId?: string; selectedVaultItemId?: string }
    | undefined;
  expect(params?.sourceLookId).toBeUndefined();
  expect(params?.selectedVaultItemId).toBeUndefined();

  // Leave the tab and come back with no params, the way a user would.
  await act(async () => {
    navigationRef.navigate('MainTabs', { screen: 'HomeTab' });
  });
  await act(async () => {
    navigationRef.navigate('MainTabs', { screen: 'StudioTab' });
  });

  await waitFor(() => expect(studioApi.activeDraft).toHaveBeenCalled());
  expect(studioApi.createFromLook).toHaveBeenCalledTimes(1);
});
