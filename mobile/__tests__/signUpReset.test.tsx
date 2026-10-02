import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { type FirebaseApp } from '@firebase/app';
import {
  createUserWithEmailAndPassword,
  sendPasswordResetEmail,
  type Auth,
} from '@firebase/auth';
import SignInScreen, {
  NOT_CONFIGURED_MESSAGE,
  RESET_ACCEPTED_MESSAGE,
} from '../src/screens/SignInScreen';
import type { AuthSurface } from '../src/config/firebase';

jest.setTimeout(30_000);

jest.mock('@firebase/auth', () => ({
  getAuth: jest.fn(() => ({})),
  onAuthStateChanged: jest.fn(() => () => {}),
  signInWithEmailAndPassword: jest.fn(async () => ({ uid: 'user-1' })),
  createUserWithEmailAndPassword: jest.fn(async () => ({ uid: 'user-1' })),
  sendPasswordResetEmail: jest.fn(async () => undefined),
}));

const createUserMock = jest.mocked(createUserWithEmailAndPassword);
const resetMock = jest.mocked(sendPasswordResetEmail);

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

const EMAIL = 'person@example.test';
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

async function openSignUp() {
  await fireEvent.press(screen.getByRole('button', { name: 'Create Account' }));
}

beforeEach(() => {
  createUserMock.mockClear();
  resetMock.mockClear();
});

test('creating an account submits the typed credentials and signs the user in', async () => {
  const signUp = jest.fn(async () => {});
  const onSignedIn = jest.fn();

  await render(
    <SignInScreen
      surface={AVAILABLE}
      signUp={signUp}
      onSignedIn={onSignedIn}
    />,
  );
  await openSignUp();
  await fireEvent.changeText(screen.getByLabelText('Email'), EMAIL);
  await fireEvent.changeText(screen.getByLabelText('Password'), PASSWORD);
  await fireEvent.press(screen.getByRole('button', { name: 'Create Account' }));

  await waitFor(() => {
    expect(signUp).toHaveBeenCalledWith(EMAIL, PASSWORD);
  });
  expect(onSignedIn).toHaveBeenCalledTimes(1);
});

test('without an injected signUp the screen calls the account-creation SDK', async () => {
  await render(<SignInScreen surface={AVAILABLE} />);
  await openSignUp();
  await fireEvent.changeText(screen.getByLabelText('Email'), EMAIL);
  await fireEvent.changeText(screen.getByLabelText('Password'), PASSWORD);
  await fireEvent.press(screen.getByRole('button', { name: 'Create Account' }));

  await waitFor(() => {
    expect(createUserMock).toHaveBeenCalledWith(auth, EMAIL, PASSWORD);
  });
});

test('an already-registered address is not disclosed: only the neutral line renders', async () => {
  const onSignedIn = jest.fn();
  const signUp = jest.fn(async () => {
    throw Object.assign(new Error('raw'), {
      code: 'auth/email-already-in-use',
    });
  });

  await render(
    <SignInScreen
      surface={AVAILABLE}
      signUp={signUp}
      onSignedIn={onSignedIn}
    />,
  );
  await openSignUp();
  await fireEvent.changeText(screen.getByLabelText('Email'), EMAIL);
  await fireEvent.changeText(screen.getByLabelText('Password'), PASSWORD);
  await fireEvent.press(screen.getByRole('button', { name: 'Create Account' }));

  await waitFor(() => {
    expect(
      screen.getByText(
        'Could not create the account. Try signing in instead, or reset your password.',
      ),
    ).toBeTruthy();
  });
  expect(onSignedIn).not.toHaveBeenCalled();
  const text = renderedText();
  expect(text).not.toContain('auth/email-already-in-use');
  expect(text).not.toContain('already');
  expect(text).not.toContain(PASSWORD);
});

test('a weak password is reported without echoing the raw error', async () => {
  const signUp = jest.fn(async () => {
    throw Object.assign(new Error('raw'), { code: 'auth/weak-password' });
  });

  await render(<SignInScreen surface={AVAILABLE} signUp={signUp} />);
  await openSignUp();
  await fireEvent.changeText(screen.getByLabelText('Email'), EMAIL);
  await fireEvent.changeText(screen.getByLabelText('Password'), PASSWORD);
  await fireEvent.press(screen.getByRole('button', { name: 'Create Account' }));

  await waitFor(() => {
    expect(screen.getByText(/stronger password/i)).toBeTruthy();
  });
  expect(renderedText()).not.toContain('auth/weak-password');
});

test('a password reset reports acceptance without confirming the address', async () => {
  const sendPasswordReset = jest.fn(async () => {});

  await render(
    <SignInScreen surface={AVAILABLE} sendPasswordReset={sendPasswordReset} />,
  );
  await fireEvent.changeText(screen.getByLabelText('Email'), EMAIL);
  await fireEvent.press(
    screen.getByRole('button', { name: 'Forgot password?' }),
  );

  await waitFor(() => {
    expect(sendPasswordReset).toHaveBeenCalledWith(EMAIL);
  });
  expect(screen.getByText(RESET_ACCEPTED_MESSAGE)).toBeTruthy();
});

test('an unknown address for reset still renders the accepted line, never user-not-found', async () => {
  const sendPasswordReset = jest.fn(async () => {
    throw Object.assign(new Error('raw'), { code: 'auth/user-not-found' });
  });

  await render(
    <SignInScreen surface={AVAILABLE} sendPasswordReset={sendPasswordReset} />,
  );
  await fireEvent.changeText(screen.getByLabelText('Email'), EMAIL);
  await fireEvent.press(
    screen.getByRole('button', { name: 'Forgot password?' }),
  );

  await waitFor(() => {
    expect(screen.getByText(RESET_ACCEPTED_MESSAGE)).toBeTruthy();
  });
  expect(renderedText()).not.toContain('auth/user-not-found');
});

test('reset with an empty email asks for one and calls nothing', async () => {
  const sendPasswordReset = jest.fn(async () => {});

  await render(
    <SignInScreen surface={AVAILABLE} sendPasswordReset={sendPasswordReset} />,
  );
  await fireEvent.press(
    screen.getByRole('button', { name: 'Forgot password?' }),
  );

  await waitFor(() => {
    expect(screen.getByText('Enter your email address.')).toBeTruthy();
  });
  expect(sendPasswordReset).not.toHaveBeenCalled();
});

test('the operation lock releases so a later creation can run again', async () => {
  const signUp = jest.fn(async () => {});

  await render(<SignInScreen surface={AVAILABLE} signUp={signUp} />);
  await openSignUp();
  await fireEvent.changeText(screen.getByLabelText('Email'), EMAIL);
  await fireEvent.changeText(screen.getByLabelText('Password'), PASSWORD);
  const button = screen.getByRole('button', { name: 'Create Account' });

  await fireEvent.press(button);
  await fireEvent.press(button);

  // A stuck lock would leave the second press unrecorded; a leaking lock
  // would let the first press double-fire. Both are caught here.
  expect(signUp).toHaveBeenCalledTimes(2);
});

test('an unconfigured surface disables account creation and reset', async () => {
  const signUp = jest.fn(async () => {});
  const sendPasswordReset = jest.fn(async () => {});

  await render(
    <SignInScreen
      surface={UNAVAILABLE}
      signUp={signUp}
      sendPasswordReset={sendPasswordReset}
    />,
  );

  expect(screen.getByText(NOT_CONFIGURED_MESSAGE)).toBeTruthy();
  expect(
    screen.getByRole('button', { name: 'Create Account' }).props
      .accessibilityState,
  ).toMatchObject({ disabled: true });
  expect(
    screen.getByRole('button', { name: 'Forgot password?' }).props
      .accessibilityState,
  ).toMatchObject({ disabled: true });

  await fireEvent.press(screen.getByRole('button', { name: 'Create Account' }));
  await fireEvent.press(
    screen.getByRole('button', { name: 'Forgot password?' }),
  );
  expect(signUp).not.toHaveBeenCalled();
  expect(sendPasswordReset).not.toHaveBeenCalled();
});

test('switching back to sign-in restores the sign-in affordance', async () => {
  await render(<SignInScreen surface={AVAILABLE} signUp={jest.fn()} />);
  await openSignUp();
  await fireEvent.press(screen.getByRole('button', { name: 'Sign In' }));

  await waitFor(() => {
    expect(screen.getByRole('button', { name: 'Create Account' })).toBeTruthy();
  });
});
