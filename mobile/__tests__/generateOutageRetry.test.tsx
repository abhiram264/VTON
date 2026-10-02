import { act, fireEvent, render, screen } from '@testing-library/react-native';
import GenerateScreen from '../src/screens/GenerateScreen';
import { ACTION_TRY_AGAIN } from '../src/features/generation/failureContract';
import type { LocalPhoto } from '../src/features/my-model/types';
import type { vaultApi } from '../src/features/vault/vaultApi';
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

// Drives the screen to the outage save-failure branch: the generation fails
// with an outage code and the Vault save rejects, so the only remaining
// affordance is the Try Again control.
function outageSaveFailedFetch() {
  return (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = requestUrl(input);
    const method = (init?.method ?? 'GET').toUpperCase();
    if (url.startsWith('file:///')) return bytesResponse(PNG_BYTES, 'image/png');
    if (url.includes('/uploads') && method === 'POST') {
      if (url.includes('/complete')) return jsonResponse(200, { id: 'up-1', status: 'READY' });
      return jsonResponse(200, {
        id: 'up-1',
        method: 'PUT',
        url: 'https://upload.invalid/1',
        headers: { 'Content-Type': 'image/png' },
        expiresAt: '2026-08-23T12:00:00Z',
      });
    }
    if (url === 'https://upload.invalid/1' && method === 'PUT') return emptyResponse(200);
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
        failureCode: 'PROVIDER_UNAVAILABLE',
        statusVersion: 1,
        createdAt: '2026-08-23T12:00:00Z',
        updatedAt: '2026-08-23T12:00:00Z',
      });
    }
    throw new Error(`unexpected fetch ${method} ${url}`);
  };
}

describe('GenerateScreen outage retry affordance (C23)', () => {
  const fetchMock = jest.fn();

  beforeEach(() => {
    fetchMock.mockReset();
    global.fetch = fetchMock as unknown as typeof fetch;
    clearActiveGeneration();
  });

  test('the save-failure retry is a visible control, not an unstyled label', async () => {
    fetchMock.mockImplementation(outageSaveFailedFetch());
    const vaultSave = jest.fn().mockRejectedValue(new Error('Vault storage unavailable'));

    await render(
      <GenerateScreen
        picker={jest.fn().mockResolvedValue(PHOTO_A)}
        vault={{ createFromPhoto: vaultSave } as unknown as typeof vaultApi}
      />,
    );

    await act(async () => {
      fireEvent.press(screen.getByRole('button', { name: 'Choose product photo' }));
    });

    const retry = await screen.findByRole('button', { name: ACTION_TRY_AGAIN });

    // The label is white; without a background style it would be painted on
    // the paper-coloured page and be unreadable, and with no padding or radius
    // the touch target would collapse to the text bounds.
    const flattened = Object.assign({}, ...[].concat(retry.props.style as never));
    expect(flattened.backgroundColor).toBe('#6b4f2a');
    expect(flattened.paddingVertical).toBe(14);
    expect(flattened.borderRadius).toBe(10);
    expect(flattened.alignItems).toBe('center');

    // And it is still a live control: pressing it re-runs the operation.
    await act(async () => {
      fireEvent.press(retry);
    });
    expect(fetchMock.mock.calls.length).toBeGreaterThan(0);
  });
});
