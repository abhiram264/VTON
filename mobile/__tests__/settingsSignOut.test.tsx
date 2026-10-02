import { fireEvent, render, screen } from '@testing-library/react-native';
import { type FirebaseApp } from '@firebase/app';
import { type Auth } from '@firebase/auth';
import { NavigationContainer } from '@react-navigation/native';
import SettingsScreen, {
  SIGNED_OUT_NOTICE,
  SIGN_OUT_FAILED_NOTICE,
  SIGN_OUT_UNAVAILABLE_NOTICE,
} from '../src/screens/SettingsScreen';
import RootNavigator from '../src/navigation/RootNavigator';
import type { AuthSession, AuthSurface } from '../src/config/firebase';

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

jest.setTimeout(30_000);

const auth = { name: 'stub-auth' } as unknown as Auth;
const AVAILABLE: AuthSurface = {
  status: 'available',
  app: { name: 'vton' } as FirebaseApp,
  auth,
  config: {
    apiKey: 'test-api-key',
    authDomain: 'test.invalid',
    projectId: 'test',
    appId: '1:1:web:1',
  },
};
const UNAVAILABLE: AuthSurface = {
  status: 'unavailable',
  reason: 'FIREBASE_NOT_CONFIGURED',
};

const SIGNED_IN: AuthSession = {
  status: 'signed-in',
  uid: 'user-42',
  getIdToken: async () => 'token',
};

test('a successful sign-out reports it and never claims more than it did', async () => {
  const signOut = jest.fn(async () => 'signed-out' as const);
  const onSignedOut = jest.fn();

  await render(
    <SettingsScreen
      session={SIGNED_IN}
      surface={AVAILABLE}
      signOut={signOut}
      onSignedOut={onSignedOut}
    />,
  );

  expect(screen.getByText('Signed in.')).toBeTruthy();
  // A raw Firebase UID is a debug identifier and must not reach the UI (C23).
  expect(screen.queryByText(/user-42/)).toBeNull();
  fireEvent.press(screen.getByRole('button', { name: 'Sign out' }));

  expect(await screen.findByText(SIGNED_OUT_NOTICE)).toBeTruthy();
  expect(signOut).toHaveBeenCalledTimes(1);
  expect(onSignedOut).toHaveBeenCalledTimes(1);
  expect(screen.queryByText(SIGN_OUT_FAILED_NOTICE)).toBeNull();
});

test('a failed SDK sign-out is reported honestly, never as signed out', async () => {
  const signOut = jest.fn(async () => {
    throw new Error('network down');
  });
  const onSignedOut = jest.fn();

  await render(
    <SettingsScreen
      session={SIGNED_IN}
      surface={AVAILABLE}
      signOut={signOut}
      onSignedOut={onSignedOut}
    />,
  );

  fireEvent.press(screen.getByRole('button', { name: 'Sign out' }));

  expect(await screen.findByText(SIGN_OUT_FAILED_NOTICE)).toBeTruthy();
  expect(screen.queryByText(SIGNED_OUT_NOTICE)).toBeNull();
  expect(onSignedOut).not.toHaveBeenCalled();
  // The session is untouched, so the control is still offered.
  expect(screen.getByRole('button', { name: 'Sign out' })).toBeEnabled();
});

test('an unavailable outcome is reported as unavailable, not as signed out', async () => {
  // The outcome mapping belongs to this screen: `unavailable` must never be
  // rendered as a completed sign-out.
  const signOut = jest.fn(async () => 'unavailable' as const);
  const onSignedOut = jest.fn();

  await render(
    <SettingsScreen
      session={SIGNED_IN}
      surface={AVAILABLE}
      signOut={signOut}
      onSignedOut={onSignedOut}
    />,
  );

  fireEvent.press(screen.getByRole('button', { name: 'Sign out' }));

  expect(await screen.findByText(SIGN_OUT_UNAVAILABLE_NOTICE)).toBeTruthy();
  expect(screen.queryByText(SIGNED_OUT_NOTICE)).toBeNull();
  expect(onSignedOut).not.toHaveBeenCalled();
});

test('the default sign-out path uses the injected surface, not a global', async () => {
  // No `signOut` prop: the screen must resolve the surface it was handed. An
  // unavailable surface returns `unavailable` without touching the SDK, which
  // is observable here as the unavailable notice rather than a thrown error.
  await render(
    <SettingsScreen session={SIGNED_IN} surface={UNAVAILABLE} />,
  );

  fireEvent.press(screen.getByRole('button', { name: 'Sign out' }));
  expect(await screen.findByText(SIGN_OUT_UNAVAILABLE_NOTICE)).toBeTruthy();
});

test('an already signed-out session offers no sign-out control', async () => {
  await render(
    <SettingsScreen session={{ status: 'signed-out' }} surface={AVAILABLE} />,
  );

  expect(screen.getByText('Not signed in.')).toBeTruthy();
  expect(screen.queryByRole('button', { name: 'Sign out' })).toBeNull();
});

test('My Model stays reachable from the settings surface', async () => {
  const onOpenMyModel = jest.fn();

  await render(
    <SettingsScreen session={SIGNED_IN} surface={AVAILABLE} onOpenMyModel={onOpenMyModel} />,
  );

  fireEvent.press(screen.getByRole('button', { name: 'My Model' }));
  expect(onOpenMyModel).toHaveBeenCalledTimes(1);
});

test('the settings route is reachable from the app and offers sign out', async () => {
  // Mount-level proof: the component tests above would pass even if nothing
  // ever rendered it, so this walks the real navigation tree to the settings
  // destination and asserts the sign-out control is there.
  await render(
    <NavigationContainer>
      <RootNavigator session={SIGNED_IN} />
    </NavigationContainer>,
  );

  await screen.findByRole('header', { name: 'Home' });
  fireEvent.press(screen.getByRole('button', { name: 'Profile and settings' }));

  expect(await screen.findByRole('header', { name: 'Settings' })).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Sign out' })).toBeEnabled();
});
