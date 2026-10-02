import { fireEvent, render, screen } from '@testing-library/react-native';
import App from '../App';
import { choosePhoto } from '../src/features/my-model/photoSource';

const STAGED_URI = 'file:///data/user/0/com.aifashionvault.app/files/share-staging/item.img';

jest.mock('../src/features/my-model/photoSource', () => ({
  choosePhoto: jest.fn(),
  takePhoto: jest.fn().mockResolvedValue(null),
  isCameraPermissionDenied: jest.fn().mockReturnValue(false),
}));

jest.mock('../src/features/vault/vaultApi', () => ({ vaultApi: { list: jest.fn().mockResolvedValue([]) } }));
jest.mock('../src/features/collections/collectionsApi', () => ({ collectionsApi: { list: jest.fn().mockResolvedValue([]) } }));
jest.mock('../src/features/looks/looksApi', () => ({ looksApi: { list: jest.fn().mockResolvedValue([]) } }));

// A screen swap alone would pass even if the payload were dropped, so pin the
// real consumer: the mounted share-import client reads the staged image bytes
// (importClient.ts readStagedImage) before any API call. If Home swallowed the
// payload, nothing ever fetches the staged file.
test('a pending share on Home is consumed by the real share import client', async () => {
  const requested: string[] = [];
  globalThis.fetch = ((input: unknown) => {
    const url = typeof input === 'string' ? input : String((input as {url?: string}).url);
    requested.push(url);
    return Promise.resolve(
      new Response(new Uint8Array([1, 2, 3]), {
        status: 200,
        headers: {'content-type': 'image/jpeg'},
      }),
    );
  }) as unknown as typeof fetch;

  jest.mocked(choosePhoto).mockResolvedValue({ uri: STAGED_URI });

  await render(<App />);
  await screen.findByRole('header', { name: 'Home' });
  expect(requested).not.toContain(STAGED_URI);

  fireEvent.press(screen.getByRole('button', { name: 'Import Item' }));

  await screen.findByText(/Preparing shared item|Shared item ready|Add a photo to continue|Could not prepare the shared item/);
  expect(requested).toContain(STAGED_URI);
});
