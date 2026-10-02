/**
 * C14 item 6: "retry choices must explain whether they create a new
 * chargeable request".
 *
 * The screen knows which outcome it reached, so each branch must carry the
 * right disclosure: a retry that mints a new generation says it is a new
 * request, and a retry that only re-saves to the Vault (no generation at all)
 * must NOT be described as one.
 */
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import GenerateScreen from '../src/screens/GenerateScreen';
import {
  ACTION_TRY_AGAIN,
  RETRY_NEW_CHARGE_NOTICE,
  RETRY_RESUMES_ACCEPTED_NOTICE,
} from '../src/features/generation/failureContract';
import type { LocalPhoto } from '../src/features/my-model/types';
import { clearActiveGeneration } from '../src/features/generation/activeGeneration';

jest.mock('../src/config/apiConfig', () => ({
  productionApiConfig: {
    baseUrl: 'https://api.test',
    getAccessToken: () => 'test-token',
  },
}));

jest.setTimeout(30_000);

const PNG_BYTES = new Uint8Array([0x89, 0x50, 0x4e, 0x47]).buffer;
const PHOTO_A: LocalPhoto = { uri: 'file:///product-a.png' };

function jsonResponse(status: number, body: unknown): Promise<Response> {
  return Promise.resolve({
    ok: status >= 200 && status < 300,
    status,
    headers: { get: () => 'application/json' },
    json: () => Promise.resolve(body),
    arrayBuffer: () => Promise.resolve(new ArrayBuffer(0)),
    text: () => Promise.resolve(JSON.stringify(body)),
  } as unknown as Response);
}

function bytesResponse(bytes: ArrayBuffer, contentType: string): Promise<Response> {
  return Promise.resolve({
    ok: true,
    status: 200,
    headers: { get: () => contentType },
    json: () => Promise.resolve({}),
    arrayBuffer: () => Promise.resolve(bytes),
    text: () => Promise.resolve(''),
  } as unknown as Response);
}

function emptyResponse(status: number): Promise<Response> {
  return Promise.resolve({
    ok: status >= 200 && status < 300,
    status,
    headers: { get: () => null },
    json: () => Promise.resolve({}),
    arrayBuffer: () => Promise.resolve(new ArrayBuffer(0)),
    text: () => Promise.resolve(''),
  } as unknown as Response);
}

function requestUrl(input: RequestInfo | URL): string {
  if (typeof input === 'string') return input;
  if (input instanceof URL) return input.toString();
  return input.url;
}

function terminalFailureFetch(failureCode: string) {
  return (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = requestUrl(input);
    const method = (init?.method ?? 'GET').toUpperCase();
    if (url.startsWith('file:///')) return bytesResponse(PNG_BYTES, 'image/png');
    if (url.includes('/uploads') && method === 'POST') {
      if (url.includes('/complete')) {
        return jsonResponse(200, { id: 'up-1', status: 'READY' });
      }
      return jsonResponse(200, {
        id: 'up-1',
        method: 'PUT',
        url: 'https://upload.invalid/1',
        headers: { 'Content-Type': 'image/png' },
        expiresAt: '2026-08-23T12:00:00Z',
      });
    }
    if (url === 'https://upload.invalid/1' && method === 'PUT') {
      return emptyResponse(200);
    }
    if (url.endsWith('/generations') && method === 'POST') {
      return jsonResponse(202, {
        id: 'gen-1',
        status: 'QUEUED',
        phase: 'QUEUED',
        statusVersion: 0,
        createdAt: '2026-08-23T12:00:00Z',
        updatedAt: '2026-08-23T12:00:00Z',
      });
    }
    if (url.includes('/generations/gen-1') && method === 'GET') {
      return jsonResponse(200, {
        id: 'gen-1',
        status: 'FAILED',
        phase: 'FAILED',
        failureCode,
        statusVersion: 1,
        createdAt: '2026-08-23T12:00:00Z',
        updatedAt: '2026-08-23T12:00:00Z',
      });
    }
    throw new Error(`unexpected fetch ${method} ${url}`);
  };
}

// Outage on create: the provider path is unavailable, so the screen falls to
// its outage branch and tries to save the photo to the Vault instead.
function providerOutageFetch() {
  return (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = requestUrl(input);
    const method = (init?.method ?? 'GET').toUpperCase();
    if (url.startsWith('file:///')) return bytesResponse(PNG_BYTES, 'image/png');
    if (url.includes('/uploads') && method === 'POST') {
      if (url.includes('/complete')) {
        return jsonResponse(200, { id: 'up-1', status: 'READY' });
      }
      return jsonResponse(200, {
        id: 'up-1',
        method: 'PUT',
        url: 'https://upload.invalid/1',
        headers: { 'Content-Type': 'image/png' },
        expiresAt: '2026-08-23T12:00:00Z',
      });
    }
    if (url === 'https://upload.invalid/1' && method === 'PUT') {
      return emptyResponse(200);
    }
    if (url.endsWith('/generations') && method === 'POST') {
      return jsonResponse(503, { code: 'PROVIDER_UNAVAILABLE' });
    }
    throw new Error(`unexpected fetch ${method} ${url}`);
  };
}

async function driveToFailure(
  fetchImpl: (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>,
  vault?: unknown,
) {
  const picker = jest.fn().mockResolvedValue(PHOTO_A);
  globalThis.fetch = jest.fn(fetchImpl) as unknown as typeof fetch;
  await render(
    vault === undefined
      ? <GenerateScreen picker={picker} />
      : <GenerateScreen picker={picker} vault={vault as never} />,
  );
  await act(async () => {
    fireEvent.press(
      screen.getByRole('button', { name: 'Choose product photo' }),
    );
  });
}

describe('retry charge disclosure', () => {
  beforeEach(() => {
    clearActiveGeneration();
  });

  test('a quality-failure Try Again discloses that it starts a new request', async () => {
    await driveToFailure(terminalFailureFetch('QUALITY_REJECTED'));

    expect(await screen.findByRole('button', { name: ACTION_TRY_AGAIN })).toBeTruthy();
    expect(await screen.findByText(RETRY_NEW_CHARGE_NOTICE)).toBeTruthy();
  });

  test('a generic failure discloses that trying again starts a new request', async () => {
    await driveToFailure(terminalFailureFetch('UNKNOWN_ERROR'));

    expect(
      await screen.findByText('Could not create the look. Try again.'),
    ).toBeTruthy();
    expect(screen.getByText(RETRY_NEW_CHARGE_NOTICE)).toBeTruthy();
  });

  test('an outage whose photo was saved still discloses that trying again starts a new request', async () => {
    await driveToFailure(providerOutageFetch(), {
      createFromPhoto: jest.fn().mockResolvedValue({ id: 'item-1' }),
    });

    expect(await screen.findByText(/saved to the Vault/i)).toBeTruthy();
    expect(screen.getByText(RETRY_NEW_CHARGE_NOTICE)).toBeTruthy();
  });

  test('the Vault-only save retry is not described as a new chargeable request', async () => {
    await driveToFailure(providerOutageFetch(), {
      createFromPhoto: jest.fn().mockRejectedValue(new Error('vault down')),
    });

    // The outage block reaches its save-failed branch...
    expect(
      await screen.findByText(
        'We could not save this item to the Vault. Try again.',
      ),
    ).toBeTruthy();
    // ...whose only retry re-saves to the Vault and starts no generation.
    expect(screen.getByRole('button', { name: ACTION_TRY_AGAIN })).toBeTruthy();
    expect(screen.queryByText(RETRY_NEW_CHARGE_NOTICE)).toBeNull();
  });

  test('resuming an accepted generation says it is not a new request', async () => {
    const onCreateLook = jest.fn();
    let listCalls = 0;
    let polls = 0;
    const fetchMock = jest.fn(
      (input: RequestInfo | URL, init?: RequestInit) => {
      const url = requestUrl(input);
      const method = (init?.method ?? 'GET').toUpperCase();
      if (url.endsWith('/generations') && method === 'GET') {
        listCalls += 1;
        return jsonResponse(200, [
          {
            id: 'gen-1',
            status: 'GENERATING',
            phase: 'GENERATING',
            statusVersion: 2,
            createdAt: '2026-08-23T12:00:00Z',
            updatedAt: '2026-08-23T12:01:00Z',
          },
        ]);
      }
      if (url.includes('/generations/gen-1') && method === 'GET') {
        polls += 1;
        // Two non-terminal polls keep the resume window open long enough to
        // observe it deterministically; the third finishes the job so the poll
        // loop actually terminates.
        if (polls < 3) {
          return jsonResponse(200, {
            id: 'gen-1',
            status: 'GENERATING',
            phase: 'GENERATING',
            statusVersion: 2 + polls,
            createdAt: '2026-08-23T12:00:00Z',
            updatedAt: '2026-08-23T12:02:00Z',
          });
        }
        return jsonResponse(200, {
          id: 'gen-1',
          status: 'COMPLETED',
          phase: 'READY',
          lookId: 'look-1',
          statusVersion: 9,
          createdAt: '2026-08-23T12:00:00Z',
          updatedAt: '2026-08-23T12:03:00Z',
        });
      }
      throw new Error(`unexpected fetch ${method} ${url}`);
      },
    );
    globalThis.fetch = fetchMock as unknown as typeof fetch;

    await render(<GenerateScreen picker={jest.fn()} onCreateLook={onCreateLook} />);

    // While resuming, the screen says the work is already under way...
    expect(
      await screen.findByText(RETRY_RESUMES_ACCEPTED_NOTICE, {}, { timeout: 5000 }),
    ).toBeTruthy();
    // ...and never frames it as a new chargeable request.
    expect(screen.queryByText(RETRY_NEW_CHARGE_NOTICE)).toBeNull();

    // The resumed job finishes and opens its Look; no generation was created.
    await waitFor(() => expect(onCreateLook).toHaveBeenCalledWith('look-1'), {
      timeout: 10_000,
    });
    expect(listCalls).toBe(1);
    const posts = fetchMock.mock.calls.filter(
      ([input, init]) =>
        requestUrl(input).endsWith('/generations') &&
        (init?.method ?? 'GET').toUpperCase() === 'POST',
    );
    expect(posts).toHaveLength(0);
  });
});
