import { act, fireEvent, render, screen } from '@testing-library/react-native';
import AccountDeletionCard from '../src/features/account/AccountDeletionCard';
import { deleteAccount } from '../src/features/account/accountApi';
import { ApiProblemError } from '../src/features/my-model/http';

const BASE_URL = 'https://api.example.invalid';
const TOKEN = 'injected-access-token';

function requestUrl(input: RequestInfo | URL): string {
  if (typeof input === 'string') {
    return input;
  }
  if (input instanceof URL) {
    return input.toString();
  }
  return input.url;
}

function response(status: number, body: unknown, json: boolean): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: () => (json ? 'application/json' : null) },
    json: () => Promise.resolve(body),
    text: () => Promise.resolve(JSON.stringify(body)),
    arrayBuffer: () => Promise.resolve(new ArrayBuffer(0)),
  } as unknown as Response;
}

describe('deleteAccount', () => {
  const fetchMock = jest.fn();

  beforeEach(() => {
    fetchMock.mockReset();
    globalThis.fetch = fetchMock as typeof fetch;
  });

  const config = { baseUrl: BASE_URL, getAccessToken: () => TOKEN };

  test('issues DELETE /me with the bearer token and accepts a 204', async () => {
    fetchMock.mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
      expect(requestUrl(input)).toBe(`${BASE_URL}/me`);
      expect((init?.method ?? '').toUpperCase()).toBe('DELETE');
      expect(init?.headers).toEqual(
        expect.objectContaining({ Authorization: `Bearer ${TOKEN}` }),
      );
      return Promise.resolve(response(204, null, false));
    });

    await expect(deleteAccount(config)).resolves.toBeUndefined();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  test('a non-2xx deletion is surfaced as an ApiProblemError, never as success', async () => {
    fetchMock.mockImplementation(() =>
      Promise.resolve(response(500, { code: 'DEPENDENCY_UNAVAILABLE' }, true)),
    );

    await expect(deleteAccount(config)).rejects.toBeInstanceOf(ApiProblemError);
  });
});

describe('AccountDeletionCard', () => {
  test('requires explicit confirmation and never requests deletion on cancel', async () => {
    const remove = jest.fn(() => Promise.resolve());
    const signOut = jest.fn(() => Promise.resolve());
    await render(<AccountDeletionCard deps={{ deleteAccount: remove, signOut }} />);

    await act(async () => {
      fireEvent.press(screen.getByRole('button', { name: 'Delete account' }));
    });
    expect(remove).not.toHaveBeenCalled();

    await act(async () => {
      fireEvent.press(screen.getByRole('button', { name: 'Cancel deletion' }));
    });
    expect(remove).not.toHaveBeenCalled();
    expect(signOut).not.toHaveBeenCalled();
    expect(screen.queryByTestId('account-deletion-requested')).toBeNull();
  });

  test('a server-accepted deletion ends the local session and reports a request, not an erase', async () => {
    const remove = jest.fn(() => Promise.resolve());
    const signOut = jest.fn(() => Promise.resolve());
    await render(<AccountDeletionCard deps={{ deleteAccount: remove, signOut }} />);

    await act(async () => {
      fireEvent.press(screen.getByRole('button', { name: 'Delete account' }));
    });
    await act(async () => {
      fireEvent.press(screen.getByRole('button', { name: 'Confirm delete account' }));
    });

    expect(remove).toHaveBeenCalledTimes(1);
    expect(signOut).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId('account-deletion-requested')).toBeTruthy();
    expect(screen.getByText(/has been requested/)).toBeTruthy();
  });

  test('a failed deletion reports honestly and keeps the session', async () => {
    const remove = jest.fn(() => Promise.reject(new ApiProblemError('HTTP_500')));
    const signOut = jest.fn(() => Promise.resolve());
    await render(<AccountDeletionCard deps={{ deleteAccount: remove, signOut }} />);

    await act(async () => {
      fireEvent.press(screen.getByRole('button', { name: 'Delete account' }));
    });
    await act(async () => {
      fireEvent.press(screen.getByRole('button', { name: 'Confirm delete account' }));
    });

    expect(signOut).not.toHaveBeenCalled();
    expect(screen.queryByTestId('account-deletion-requested')).toBeNull();
    expect(screen.getByText(/Nothing was deleted/)).toBeTruthy();
  });

  test('an accepted deletion with a failed local sign-out still reports the request', async () => {
    const remove = jest.fn(() => Promise.resolve());
    const signOut = jest.fn(() => Promise.reject(new Error('no session')));
    await render(<AccountDeletionCard deps={{ deleteAccount: remove, signOut }} />);

    await act(async () => {
      fireEvent.press(screen.getByRole('button', { name: 'Delete account' }));
    });
    await act(async () => {
      fireEvent.press(screen.getByRole('button', { name: 'Confirm delete account' }));
    });

    expect(screen.getByTestId('account-deletion-requested')).toBeTruthy();
    expect(screen.getByText(/could not sign you out/)).toBeTruthy();
  });
});
