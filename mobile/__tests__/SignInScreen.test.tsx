import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { type FirebaseApp } from '@firebase/app';
import { signInWithEmailAndPassword, type Auth } from '@firebase/auth';
import SignInScreen, {
  NOT_CONFIGURED_MESSAGE,
} from '../src/screens/SignInScreen';
import type { AuthSurface } from '../src/config/firebase';

jest.mock('@firebase/auth', () => ({
  getAuth: jest.fn(() => ({})),
  onAuthStateChanged: jest.fn(() => () => {}),
  signInWithEmailAndPassword: jest.fn(async () => ({ uid: 'user-1' })),
}));

const signInWithEmailAndPasswordMock = jest.mocked(signInWithEmailAndPassword);

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

const PASSWORD = 'sup3r-secret-pass';

function renderedText(node: unknown = screen.toJSON()): string {
  if (typeof node === 'string') return node;
  if (Array.isArray(node)) return node.map(renderedText).join(' ');
  if (node && typeof node === 'object') {
    const { children, ...props } = node as {
      children?: unknown;
      [key: string]: unknown;
    };
    const text = (props as Record<string, unknown>).text;
    return [typeof text === 'string' ? text : '', renderedText(children)].join(
      ' ',
    );
  }
  return '';
}

beforeEach(() => {
  signInWithEmailAndPasswordMock.mockClear();
});

test('an unconfigured surface explains itself and never signs in', async () => {
  const signIn = jest.fn(async () => {});

  await render(
    <SignInScreen
      surface={UNAVAILABLE}
      signIn={signIn}
      onSignedIn={() => {}}
    />,
  );

  expect(screen.getByText(NOT_CONFIGURED_MESSAGE)).toBeTruthy();
  expect(screen.getByText(/Firebase is not configured/i)).toBeTruthy();
  const submit = screen.getByRole('button', { name: 'Sign In' });
  expect(submit.props.accessibilityState).toMatchObject({ disabled: true });

  await fireEvent.press(submit);
  await fireEvent.changeText(screen.getByLabelText('Email'), 'a@b.test');
  await fireEvent.changeText(screen.getByLabelText('Password'), PASSWORD);
  await fireEvent.press(submit);

  await waitFor(() => {
    expect(signIn).not.toHaveBeenCalled();
  });
  expect(signInWithEmailAndPasswordMock).not.toHaveBeenCalled();
});

test('an empty field submits nothing', async () => {
  const signIn = jest.fn(async () => {});

  await render(<SignInScreen surface={AVAILABLE} signIn={signIn} />);
  await fireEvent.press(screen.getByRole('button', { name: 'Sign In' }));

  await waitFor(() => {
    expect(screen.getByText('Enter your email and password.')).toBeTruthy();
  });
  expect(signIn).not.toHaveBeenCalled();
});

test('a successful submit signs in with the typed credentials', async () => {
  const signIn = jest.fn(async () => {});
  const onSignedIn = jest.fn();

  await render(
    <SignInScreen
      surface={AVAILABLE}
      signIn={signIn}
      onSignedIn={onSignedIn}
    />,
  );
  await fireEvent.changeText(screen.getByLabelText('Email'), 'person@example.test');
  await fireEvent.changeText(screen.getByLabelText('Password'), PASSWORD);
  await fireEvent.press(screen.getByRole('button', { name: 'Sign In' }));

  await waitFor(() => {
    expect(signIn).toHaveBeenCalledWith('person@example.test', PASSWORD);
  });
  expect(onSignedIn).toHaveBeenCalledTimes(1);
});

test('a rejected submit shows a human line and no raw error', async () => {
  const onSignedIn = jest.fn();
  const signIn = jest.fn(async () => {
    throw Object.assign(new Error('raw'), { code: 'auth/wrong-password' });
  });

  await render(
    <SignInScreen
      surface={AVAILABLE}
      signIn={signIn}
      onSignedIn={onSignedIn}
    />,
  );
  await fireEvent.changeText(screen.getByLabelText('Email'), 'person@example.test');
  await fireEvent.changeText(screen.getByLabelText('Password'), PASSWORD);
  await fireEvent.press(screen.getByRole('button', { name: 'Sign In' }));

  await waitFor(() => {
    expect(screen.getByText('Email or password is incorrect.')).toBeTruthy();
  });
  expect(onSignedIn).not.toHaveBeenCalled();
  expect(renderedText()).not.toContain('auth/wrong-password');
  expect(renderedText()).not.toContain('raw');
  expect(renderedText()).not.toContain(PASSWORD);
});

test('a network failure shows the connection line', async () => {
  const onSignedIn = jest.fn();
  const signIn = jest.fn(async () => {
    throw Object.assign(new Error('offline'), {
      code: 'auth/network-request-failed',
    });
  });

  await render(
    <SignInScreen
      surface={AVAILABLE}
      signIn={signIn}
      onSignedIn={onSignedIn}
    />,
  );
  await fireEvent.changeText(screen.getByLabelText('Email'), 'person@example.test');
  await fireEvent.changeText(screen.getByLabelText('Password'), PASSWORD);
  await fireEvent.press(screen.getByRole('button', { name: 'Sign In' }));

  await waitFor(() => {
    expect(screen.getByText(/check your connection/i)).toBeTruthy();
  });
  expect(onSignedIn).not.toHaveBeenCalled();
});

test('without an injected signIn the screen calls the auth SDK', async () => {
  const onSignedIn = jest.fn();

  await render(<SignInScreen surface={AVAILABLE} onSignedIn={onSignedIn} />);
  await fireEvent.changeText(screen.getByLabelText('Email'), 'person@example.test');
  await fireEvent.changeText(screen.getByLabelText('Password'), PASSWORD);
  await fireEvent.press(screen.getByRole('button', { name: 'Sign In' }));

  await waitFor(() => {
    expect(signInWithEmailAndPasswordMock).toHaveBeenCalledWith(
      auth,
      'person@example.test',
      PASSWORD,
    );
  });
  expect(onSignedIn).toHaveBeenCalledTimes(1);
});
