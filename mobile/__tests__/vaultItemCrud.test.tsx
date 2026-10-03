import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import VaultScreen from '../src/screens/VaultScreen';
import { vaultApi } from '../src/features/vault/vaultApi';
import { ApiProblemError } from '../src/features/my-model/http';

// The real vault client is exercised here: the only things faked are the
// network and the API config seam (which otherwise needs a signed-in Firebase
// user). That keeps these tests on the actual request paths and parsers rather
// than on a mocked client that could drift from the mounted routes.
jest.mock('../src/config/apiConfig', () => ({
  productionApiConfig: {
    baseUrl: 'https://api.example.invalid',
    getAccessToken: jest.fn(async () => 'test-token'),
  },
}));

const ITEM_ID = '11111111-1111-1111-1111-111111111111';

const ITEM = {
  id: ITEM_ID,
  name: 'Silk top',
  brand: 'Example',
  category: 'TOP',
  subcategory: null,
  color: 'Ivory',
  sourceReferenceId: null,
  createdAt: '2026-09-01T10:00:00Z',
};

const IMAGE = {
  url: 'https://signed.example.invalid/read?X-Amz-Signature=t',
  expiresAt: '2026-09-01T10:05:00Z',
};

type Result = { status: number; body?: unknown };
type Route = { method: string; path: string; respond: () => Result };

let routes: Route[] = [];
const calls: { method: string; url: string; body?: string }[] = [];

function json(status: number, body: unknown): Result {
  return { status, body };
}

function empty(status: number): Result {
  return { status };
}

function override(method: string, path: string, respond: () => Result) {
  routes = routes.map(route =>
    route.method === method && route.path === path ? { ...route, respond } : route,
  );
}

beforeEach(() => {
  calls.length = 0;
  routes = [
    { method: 'GET', path: '/vault/items', respond: () => json(200, [ITEM]) },
    { method: 'GET', path: `/vault/items/${ITEM_ID}`, respond: () => json(200, ITEM) },
    { method: 'GET', path: `/vault/items/${ITEM_ID}/image`, respond: () => json(200, IMAGE) },
    { method: 'PATCH', path: `/vault/items/${ITEM_ID}`, respond: () => json(200, { ...ITEM, category: 'BOTTOM' }) },
    { method: 'DELETE', path: `/vault/items/${ITEM_ID}`, respond: () => empty(204) },
  ];
  globalThis.fetch = jest.fn(async (url: string, init?: { method?: string; body?: string }) => {
    const method = (init?.method ?? 'GET').toUpperCase();
    calls.push({ method, url, body: init?.body });
    const path = url.replace('https://api.example.invalid', '');
    const route = routes.find(value => value.method === method && value.path === path);
    const result = route ? route.respond() : json(404, { code: 'VAULT_ITEM_NOT_FOUND' });
    return {
      ok: result.status >= 200 && result.status < 300,
      status: result.status,
      headers: { get: () => (result.body === undefined ? null : 'application/json') },
      json: () => Promise.resolve(result.body ?? {}),
      arrayBuffer: () => Promise.resolve(new ArrayBuffer(0)),
      text: () => Promise.resolve(JSON.stringify(result.body ?? {})),
    } as unknown as Response;
  }) as unknown as typeof fetch;
});

const noop = () => undefined;

async function openDetail() {
  await render(<VaultScreen onProfile={noop} />);
  fireEvent.press(await screen.findByRole('button', { name: 'Silk top' }));
  await screen.findByRole('button', { name: 'Delete item' });
}

test('opens an item on the mounted detail route and shows its signed preview', async () => {
  await openDetail();

  expect(calls.some(call => call.method === 'GET' && call.url.endsWith(`/vault/items/${ITEM_ID}`))).toBe(true);
  expect(calls.some(call => call.method === 'GET' && call.url.endsWith(`/vault/items/${ITEM_ID}/image`))).toBe(true);
  expect(screen.getByLabelText('Item name').props.value).toBe('Silk top');
  expect(await screen.findByText(/Preview available until/)).toBeTruthy();
});

test('an unavailable preview is retryable and is not reported as a deletion', async () => {
  override('GET', `/vault/items/${ITEM_ID}/image`, () => json(503, { code: 'VAULT_MEDIA_UNAVAILABLE' }));

  await openDetail();

  expect(await screen.findByText('Preview temporarily unavailable.')).toBeTruthy();
  expect(screen.queryByText('This item is no longer available.')).toBeNull();

  const before = calls.filter(call => call.url.endsWith('/image')).length;
  fireEvent.press(screen.getByRole('button', { name: 'Retry preview' }));
  await waitFor(() =>
    expect(calls.filter(call => call.url.endsWith('/image')).length).toBe(before + 1),
  );
});

test('deleting an item requires explicit confirmation before the delete request is sent', async () => {
  await openDetail();

  fireEvent.press(screen.getByRole('button', { name: 'Delete item' }));
  await screen.findByRole('button', { name: 'Cancel delete' });
  expect(calls.some(call => call.method === 'DELETE')).toBe(false);

  fireEvent.press(screen.getByRole('button', { name: 'Cancel delete' }));
  await screen.findByRole('button', { name: 'Delete item' });
  expect(calls.some(call => call.method === 'DELETE')).toBe(false);

  fireEvent.press(screen.getByRole('button', { name: 'Delete item' }));
  fireEvent.press(await screen.findByRole('button', { name: 'Confirm delete' }));

  await waitFor(() =>
    expect(calls.some(call => call.method === 'DELETE' && call.url.endsWith(`/vault/items/${ITEM_ID}`))).toBe(true),
  );
  expect(await screen.findByRole('header', { name: 'Vault' })).toBeTruthy();
});

test('a delete refused because the item is in use keeps the item and explains why', async () => {
  override('DELETE', `/vault/items/${ITEM_ID}`, () => json(409, { code: 'VAULT_ITEM_IN_USE' }));

  await openDetail();
  fireEvent.press(screen.getByRole('button', { name: 'Delete item' }));
  fireEvent.press(await screen.findByRole('button', { name: 'Confirm delete' }));

  expect(await screen.findByText('Delete blocked: this item is still used by an active draft or Look.')).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Delete item' })).toBeTruthy();
  expect(screen.queryByRole('header', { name: 'Vault' })).toBeNull();
});

test('an item the owner cannot access is reported as gone rather than retryable', async () => {
  override('GET', `/vault/items/${ITEM_ID}`, () => json(404, { code: 'VAULT_ITEM_NOT_FOUND' }));

  await render(<VaultScreen onProfile={noop} />);
  fireEvent.press(await screen.findByRole('button', { name: 'Silk top' }));

  expect(await screen.findByText('This item is no longer available.')).toBeTruthy();
  expect(screen.queryByRole('button', { name: 'Retry item' })).toBeNull();
});

test('a preview that answers not-found after the detail loaded is reported as gone, not as a retry', async () => {
  override('GET', `/vault/items/${ITEM_ID}/image`, () => json(404, { code: 'VAULT_ITEM_NOT_FOUND' }));

  await render(<VaultScreen onProfile={noop} />);
  fireEvent.press(await screen.findByRole('button', { name: 'Silk top' }));

  expect(await screen.findByText('This item is no longer available.')).toBeTruthy();
  expect(screen.queryByRole('button', { name: 'Retry preview' })).toBeNull();
});

test('editing the category persists through the mounted patch route and refreshes the list', async () => {
  await openDetail();

  fireEvent.press(screen.getByRole('radio', { name: 'BOTTOM' }));
  await waitFor(() =>
    expect(screen.getByRole('radio', { name: 'BOTTOM' }).props.accessibilityState.checked).toBe(true),
  );
  fireEvent.press(screen.getByRole('button', { name: 'Save changes' }));

  await waitFor(() => {
    const patch = calls.find(call => call.method === 'PATCH');
    expect(patch?.url.endsWith(`/vault/items/${ITEM_ID}`)).toBe(true);
    expect(JSON.parse(patch?.body ?? '{}')).toEqual({ name: 'Silk top', category: 'BOTTOM' });
  });
  expect(await screen.findByText('Changes saved.')).toBeTruthy();
  await waitFor(() =>
    expect(calls.filter(call => call.method === 'GET' && call.url.endsWith('/vault/items')).length).toBeGreaterThan(1),
  );
});

test('an unclassified item is shown honestly and can be corrected', async () => {
  override('GET', '/vault/items', () => json(200, [{ ...ITEM, category: 'UNKNOWN' }]));
  override('GET', `/vault/items/${ITEM_ID}`, () => json(200, { ...ITEM, category: 'UNKNOWN' }));

  await openDetail();

  expect(await screen.findByText(/This item is unclassified/)).toBeTruthy();
  expect(screen.getByRole('radio', { name: 'UNKNOWN' }).props.accessibilityState.checked).toBe(true);
});

test('the client rejects an image capability without a usable url', async () => {
  override('GET', `/vault/items/${ITEM_ID}/image`, () => json(200, { url: '' }));

  await expect(vaultApi.image(ITEM_ID)).rejects.toThrow('Invalid Vault image response');
});

test('the client surfaces an opaque not-found capability as the vault problem code', async () => {
  override('GET', `/vault/items/${ITEM_ID}/image`, () => json(404, { code: 'VAULT_ITEM_NOT_FOUND' }));

  await expect(vaultApi.image(ITEM_ID)).rejects.toBeInstanceOf(ApiProblemError);
});
