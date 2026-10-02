import { act, render, screen, waitFor } from '@testing-library/react-native';
import { NavigationContainer } from '@react-navigation/native';
import * as firebaseConfig from '../src/config/firebase';
import RootNavigator from '../src/navigation/RootNavigator';
import {
  setActiveGeneration,
  getActiveGeneration,
} from '../src/features/generation/activeGeneration';
import { isFeatureEnabled } from '../src/config/featureFlags';
import type { AuthSession } from '../src/config/firebase';

jest.setTimeout(30_000);

// The auth subscription is the seam under test: the navigator must feed it
// through the account-isolation watcher, so a session that ends WITHOUT the
// user asking (expiry, revocation) clears account-bound pointers exactly like
// an explicit sign-out. Spying on the real module lets the test drive that
// transition deterministically without replacing any other auth-surface export
// the screens rely on.
let mockEmit: ((session: AuthSession) => void) | null = null;

jest.mock('../src/features/my-model/photoSource', () => ({
  choosePhoto: jest.fn().mockResolvedValue(null),
  takePhoto: jest.fn().mockResolvedValue(null),
  isCameraPermissionDenied: jest.fn().mockReturnValue(false),
}));
jest.mock('../src/features/vault/vaultApi', () => ({
  vaultApi: { list: jest.fn().mockResolvedValue([]) },
}));
jest.mock('../src/features/collections/collectionsApi', () => ({
  collectionsApi: { list: jest.fn().mockResolvedValue([]) },
}));
jest.mock('../src/features/looks/looksApi', () => ({
  looksApi: { list: jest.fn().mockResolvedValue([]) },
}));

beforeEach(() => {
  mockEmit = null;
  jest
    .spyOn(firebaseConfig, 'subscribeToAuthSession')
    .mockImplementation(listener => {
      mockEmit = listener;
      return () => {
        mockEmit = null;
      };
    });
});

afterEach(() => {
  jest.restoreAllMocks();
});

function mount() {
  return render(
    <NavigationContainer>
      <RootNavigator />
    </NavigationContainer>,
  );
}

const SIGNED_IN: AuthSession = {
  status: 'signed-in',
  uid: 'account-a',
  getIdToken: async () => 'token',
};

test('a session that ends without the user asking clears account pointers', async () => {
  setActiveGeneration('generation-of-account-a');
  await mount();

  await act(async () => {
    mockEmit?.(SIGNED_IN);
  });
  expect(getActiveGeneration()).toBe('generation-of-account-a');

  // Expiry / revocation: the subscription reports signed-out on its own.
  await act(async () => {
    mockEmit?.({ status: 'signed-out' });
  });

  await waitFor(() => expect(getActiveGeneration()).toBeNull());
  // Restoring the declared defaults is this seam's reset for flag overrides.
  expect(isFeatureEnabled('TRYON_MAX')).toBe(true);
});

test('a live signed-in session keeps the account pointers', async () => {
  setActiveGeneration('generation-of-account-a');
  await mount();

  await act(async () => {
    mockEmit?.(SIGNED_IN);
  });

  await screen.findByRole('header', { name: 'Home' });
  expect(getActiveGeneration()).toBe('generation-of-account-a');
});
