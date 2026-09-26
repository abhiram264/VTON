import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react-native';
import MyModelScreen from '../src/screens/MyModelScreen';

jest.mock('../src/config/apiConfig', () => ({
  productionApiConfig: {
    baseUrl: 'https://api.test',
    getAccessToken: () => 'test-token',
  },
}));
import { Alert } from 'react-native';
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

const JPEG_BYTES = new Uint8Array([0xff, 0xd8, 0xff, 0xe0]).buffer;

function bytesResponse(
  bytes: ArrayBuffer,
  contentType: string,
): Promise<Response> {
  return Promise.resolve({
    ok: true,
    status: 200,
    headers: { get: () => contentType },
    json: () => Promise.resolve({}),
    arrayBuffer: () => Promise.resolve(bytes),
    text: () => Promise.resolve(''),
  } as unknown as Response);
}

const CLEAR_PHOTO_CODES = [
  'AVATAR_PERSON_NOT_SINGLE',
  'AVATAR_FACE_NOT_VISIBLE',
  'AVATAR_BODY_NOT_VISIBLE',
  'AVATAR_TOO_BLURRED',
  'AVATAR_TOO_DARK',
  'AVATAR_RESOLUTION_INSUFFICIENT',
  'AVATAR_ASPECT_RATIO',
] as const;

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

  afterEach(() => {
    jest.restoreAllMocks();
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

  test('null pick shows a user-visible notice instead of a silent no-op', async () => {
    fetchMock.mockResolvedValue(await jsonResponse(200, []));
    takePhotoMock.mockResolvedValue(null);

    await render(<MyModelScreen />);
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Take photo' })).toBeTruthy();
    });

    fireEvent.press(screen.getByRole('button', { name: 'Take photo' }));

    const notice = await screen.findByText(
      'No photo was selected. Photo selection may be unavailable. Try again.',
    );
    expect(notice.props.accessibilityLiveRegion).toBe('polite');
    expect(screen.getByRole('button', { name: 'Take photo' })).toBeTruthy();
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

    const alert = jest.spyOn(Alert, 'alert').mockImplementation((_title, _message, buttons) => {
      buttons?.find(button => button.style === 'destructive')?.onPress?.();
    });
    await render(<MyModelScreen />);
    await screen.findByLabelText('My Model preview');

    await act(async () => {
      fireEvent.press(screen.getByRole('button', { name: 'Delete' }));
    });
    expect(alert).toHaveBeenCalledTimes(1);
    expect(alert.mock.calls[0][0]).toBe('Delete My Model?');
    expect(alert.mock.calls[0][1]).toBe(
      'This permanently deletes your private My Model photo.',
    );
    expect(alert.mock.calls[0][2]).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ text: 'Cancel' }),
        expect.objectContaining({ text: 'Delete', style: 'destructive' }),
      ]),
    );
    alert.mockRestore();

    await waitFor(() => {
      expect(
        screen.getByText(/clear, well-lit, full-body photo/i),
      ).toBeTruthy();
    });
    expect(screen.queryByLabelText('My Model preview')).toBeNull();
    expect(screen.getByRole('button', { name: 'Take photo' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Choose photo' })).toBeTruthy();
  });

  test.each(CLEAR_PHOTO_CODES)(
    'semantic 422 code %s displays clearer photo copy with polite live region and no sensitive leaks',
    async code => {
      takePhotoMock.mockResolvedValue({ uri: 'file:///photo.jpg' });
      fetchMock.mockImplementation((input, init) => {
        const url = requestUrl(input);
        const method = (init?.method ?? 'GET').toUpperCase();
        if (url.endsWith('/avatars') && method === 'GET') {
          return jsonResponse(200, []);
        }
        if (url === 'file:///photo.jpg') {
          return bytesResponse(JPEG_BYTES, 'image/jpeg');
        }
        if (url.includes('/uploads') && method === 'POST') {
          if (url.includes('/complete')) {
            return jsonResponse(200, { id: 'up-1', status: 'READY' });
          }
          return jsonResponse(200, {
            id: 'up-1',
            method: 'PUT',
            url: 'https://upload.invalid/1',
            headers: { 'Content-Type': 'image/jpeg' },
            expiresAt: '2026-08-23T12:00:00Z',
          });
        }
        if (url === 'https://upload.invalid/1' && method === 'PUT') {
          return emptyResponse(200);
        }
        if (url.endsWith('/avatars') && method === 'POST') {
          return jsonResponse(422, { code });
        }
        throw new Error(`unexpected fetch ${method} ${url}`);
      });
      await render(<MyModelScreen />);
      await waitFor(() => {
        expect(screen.getByRole('button', { name: 'Take photo' })).toBeTruthy();
      });

      await act(async () => {
        fireEvent.press(screen.getByRole('button', { name: 'Take photo' }));
      });

      const notice = await screen.findByText(
        'We need a clearer full-body photo to create an accurate preview.',
      );
      expect(notice).toBeTruthy();
      expect(notice.props.accessibilityLiveRegion).toBe('polite');
      expect(screen.queryByText(code)).toBeNull();
      expect(screen.queryByText(/https?:\/\//)).toBeNull();
      expect(screen.queryByText(/photo\.jpg/)).toBeNull();
    },
  );

  test('avatar preflight unavailable 503 retains generic error notice', async () => {
    takePhotoMock.mockResolvedValue({ uri: 'file:///photo.jpg' });
    fetchMock.mockImplementation((input, init) => {
      const url = requestUrl(input);
      const method = (init?.method ?? 'GET').toUpperCase();
      if (url.endsWith('/avatars') && method === 'GET') {
        return jsonResponse(200, []);
      }
      if (url === 'file:///photo.jpg') {
        return bytesResponse(JPEG_BYTES, 'image/jpeg');
      }
      if (url.includes('/uploads') && method === 'POST') {
        if (url.includes('/complete')) {
          return jsonResponse(200, { id: 'up-1', status: 'READY' });
        }
        return jsonResponse(200, {
          id: 'up-1',
          method: 'PUT',
          url: 'https://upload.invalid/1',
          headers: { 'Content-Type': 'image/jpeg' },
          expiresAt: '2026-08-23T12:00:00Z',
        });
      }
      if (url === 'https://upload.invalid/1' && method === 'PUT') {
        return emptyResponse(200);
      }
      if (url.endsWith('/avatars') && method === 'POST') {
        return jsonResponse(503, { code: 'AVATAR_PREFLIGHT_UNAVAILABLE' });
      }
      throw new Error(`unexpected fetch ${method} ${url}`);
    });
    await render(<MyModelScreen />);
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Take photo' })).toBeTruthy();
    });

    await act(async () => {
      fireEvent.press(screen.getByRole('button', { name: 'Take photo' }));
    });

    const notice = await screen.findByText(
      'Could not save My Model. Try another photo.',
    );
    expect(notice).toBeTruthy();
    expect(notice.props.accessibilityLiveRegion).toBe('polite');
    expect(
      screen.queryByText(
        'We need a clearer full-body photo to create an accurate preview.',
      ),
    ).toBeNull();
    expect(screen.queryByText('AVATAR_PREFLIGHT_UNAVAILABLE')).toBeNull();
    expect(screen.queryByText(/https?:\/\//)).toBeNull();
  });
});
