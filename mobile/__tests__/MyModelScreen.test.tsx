import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react-native';
import MyModelScreen from '../src/screens/MyModelScreen';
import { choosePhoto, takePhoto } from '../src/features/my-model/photoSource';

jest.mock('../src/features/my-model/photoSource', () => ({
  takePhoto: jest.fn(),
  choosePhoto: jest.fn(),
  isCameraPermissionDenied: jest.fn(() => false),
}));

const takePhotoMock = jest.mocked(takePhoto);
const choosePhotoMock = jest.mocked(choosePhoto);

const DISPLAY_URL =
  'https://signed.example.invalid/read?X-Amz-Signature=read-token';

const AVATAR = {
  id: '11111111-1111-1111-1111-111111111111',
  imageId: '33333333-3333-3333-3333-333333333333',
  revision: 1,
  width: 8,
  height: 6,
  mediaType: 'image/jpeg',
  displayUrl: DISPLAY_URL,
  displayExpiresAt: '2026-08-23T12:00:00Z',
  warnings: [] as string[],
};

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
  if (typeof input === 'string') {
    return input;
  }
  if (input instanceof URL) {
    return input.toString();
  }
  return input.url;
}

describe('MyModelScreen', () => {
  const fetchMock = jest.fn();

  beforeEach(() => {
    fetchMock.mockReset();
    takePhotoMock.mockReset();
    choosePhotoMock.mockReset();
    globalThis.fetch = fetchMock as typeof fetch;
  });

  test('empty guidance is visible', async () => {
    fetchMock.mockResolvedValue(await jsonResponse(200, []));

    await render(<MyModelScreen />);

    await waitFor(() => {
      expect(
        screen.getByText(/clear, well-lit, full-body photo/i),
      ).toBeTruthy();
    });
    expect(screen.getByRole('button', { name: 'Take photo' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Choose photo' })).toBeTruthy();
    expect(screen.queryByLabelText('My Model preview')).toBeNull();
  });

  test('cancelled take photo leaves the empty screen unchanged', async () => {
    fetchMock.mockResolvedValue(await jsonResponse(200, []));
    takePhotoMock.mockResolvedValue(null);

    await render(<MyModelScreen />);
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Take photo' })).toBeTruthy();
    });

    fireEvent.press(screen.getByRole('button', { name: 'Take photo' }));

    await waitFor(() => {
      expect(takePhotoMock).toHaveBeenCalled();
    });
    expect(screen.getByText(/clear, well-lit, full-body photo/i)).toBeTruthy();
    expect(screen.queryByLabelText('My Model preview')).toBeNull();
    expect(
      fetchMock.mock.calls.some(([input]) =>
        requestUrl(input).includes('/uploads'),
      ),
    ).toBe(false);
  });

  test('cancelled choose photo leaves the empty screen unchanged', async () => {
    fetchMock.mockResolvedValue(await jsonResponse(200, []));
    choosePhotoMock.mockResolvedValue(null);

    await render(<MyModelScreen />);
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Choose photo' })).toBeTruthy();
    });

    fireEvent.press(screen.getByRole('button', { name: 'Choose photo' }));

    await waitFor(() => {
      expect(choosePhotoMock).toHaveBeenCalled();
    });
    expect(screen.getByText(/clear, well-lit, full-body photo/i)).toBeTruthy();
    expect(screen.queryByLabelText('My Model preview')).toBeNull();
    expect(
      fetchMock.mock.calls.some(([input]) =>
        requestUrl(input).includes('/uploads'),
      ),
    ).toBe(false);
  });

  test('ready state shows the private preview', async () => {
    fetchMock.mockResolvedValue(await jsonResponse(200, [AVATAR]));

    await render(<MyModelScreen />);

    const preview = await screen.findByLabelText('My Model preview');
    expect(preview.props.source).toEqual({ uri: DISPLAY_URL });
    expect(screen.getByRole('button', { name: 'Replace' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Delete' })).toBeTruthy();
    expect(screen.queryByText(/clear, well-lit, full-body photo/i)).toBeNull();
  });

  test('delete returns to empty', async () => {
    fetchMock.mockImplementation((input, init) => {
      const url = requestUrl(input);
      const method = (init?.method ?? 'GET').toUpperCase();
      if (url.endsWith('/avatars') && method === 'GET') {
        return jsonResponse(200, [AVATAR]);
      }
      if (url.endsWith(`/avatars/${AVATAR.id}`) && method === 'DELETE') {
        return emptyResponse(204);
      }
      throw new Error(`unexpected fetch ${method} ${url}`);
    });

    await render(<MyModelScreen />);
    await screen.findByLabelText('My Model preview');

    await act(async () => {
      fireEvent.press(screen.getByRole('button', { name: 'Delete' }));
    });

    await waitFor(() => {
      expect(
        screen.getByText(/clear, well-lit, full-body photo/i),
      ).toBeTruthy();
    });
    expect(screen.queryByLabelText('My Model preview')).toBeNull();
    expect(screen.getByRole('button', { name: 'Take photo' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Choose photo' })).toBeTruthy();
  });
});
