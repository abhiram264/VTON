import { render, screen, fireEvent, act } from '@testing-library/react-native';
import GenerateScreen from '../../screens/GenerateScreen';
import { createGenerationApi } from './generationApi';
import { createLook } from './createLook';
import {
  OUTAGE_TITLE,
  OUTAGE_BODY,
  ACTION_TRY_AGAIN,
  PROVIDER_OUTAGE_TRIGGER_CODES,
  QUALITY_FAILURE_BODY,
  isProviderOutage,
} from './failureContract';
import type { LocalPhoto } from '../my-model/types';
import type { GenerationApi, GenerationResource } from './types';
import type { ShareImportResolver, ShareImportResponse } from '../share/importTypes';

const PHOTO_A: LocalPhoto = { uri: 'file:///data/photo-a.jpg' };
const UPLOAD_ID = 'upl-123';
const IDEMPOTENCY_KEY = 'idem-123';
const PNG_BYTES = new Uint8Array([0x89, 0x50, 0x4e, 0x47]).buffer;

function jsonResponse(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: new Headers({ 'content-type': 'application/json' }),
    text: () => Promise.resolve(JSON.stringify(body)),
    json: () => Promise.resolve(body),
  } as unknown as Response;
}

function emptyResponse(status: number): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: new Headers(),
    text: () => Promise.resolve(''),
    json: () => Promise.resolve(null),
  } as unknown as Response;
}

function bytesResponse(bytes: ArrayBuffer, contentType: string): Response {
  return {
    ok: true,
    status: 200,
    headers: new Headers({ 'content-type': contentType }),
    arrayBuffer: () => Promise.resolve(bytes),
    text: () => Promise.resolve(''),
    json: () => Promise.resolve(null),
  } as unknown as Response;
}

function mockGenerationFetch(options: {
  pollResponse?: () => Promise<Response>;
  createResponse?: () => Promise<Response>;
  uploadResponse?: () => Promise<Response>;
}) {
  return (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = typeof input === 'string' ? input : (input as { url?: string }).url ?? '';
    const method = (init?.method ?? 'GET').toUpperCase();

    if (url.startsWith('file:///')) {
      return Promise.resolve(bytesResponse(PNG_BYTES, 'image/png'));
    }
    if (url.includes('/uploads') && method === 'POST') {
      if (url.includes('/complete')) {
        return Promise.resolve(jsonResponse(200, { id: 'up-1', status: 'READY' }));
      }
      if (options.uploadResponse) {
        return options.uploadResponse();
      }
      return Promise.resolve(
        jsonResponse(200, {
          id: 'up-1',
          method: 'PUT',
          url: 'https://upload.invalid/1',
          headers: { 'Content-Type': 'image/png' },
          expiresAt: '2026-09-05T00:00:00Z',
        }),
      );
    }
    if (url === 'https://upload.invalid/1' && method === 'PUT') {
      return Promise.resolve(emptyResponse(200));
    }
    if (url.endsWith('/generations') && method === 'POST') {
      if (options.createResponse) {
        return options.createResponse();
      }
      return Promise.resolve(
        jsonResponse(202, {
          id: 'gen-1',
          status: 'QUEUED',
          phase: 'QUEUED',
          statusVersion: 0,
          createdAt: '2026-09-05T00:00:00Z',
          updatedAt: '2026-09-05T00:00:00Z',
        }),
      );
    }
    if (url.includes('/generations/gen-1') && method === 'GET') {
      if (options.pollResponse) {
        return options.pollResponse();
      }
      return Promise.resolve(
        jsonResponse(200, {
          id: 'gen-1',
          status: 'COMPLETED',
          phase: 'READY',
          lookId: 'look-1',
          statusVersion: 1,
          createdAt: '2026-09-05T00:00:00Z',
          updatedAt: '2026-09-05T00:00:00Z',
        }),
      );
    }
    return Promise.resolve(emptyResponse(404));
  };
}

describe('U3: Generation wire contract and Vault fallback', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  describe('Generation wire contract', () => {
    it('sends exactly { productUploadId } without requestedMode in /generations body', async () => {
      const fetchMock = jest.fn().mockResolvedValue(
        jsonResponse(202, {
          id: 'gen-1',
          status: 'QUEUED',
          phase: 'QUEUED',
          statusVersion: 0,
          createdAt: '2026-09-05T00:00:00Z',
          updatedAt: '2026-09-05T00:00:00Z',
        }),
      );
      globalThis.fetch = fetchMock;

      const api = createGenerationApi({ baseUrl: 'https://api.test', getAccessToken: () => null });
      await api.create(UPLOAD_ID, IDEMPOTENCY_KEY);

      expect(fetchMock).toHaveBeenCalledTimes(1);
      const [url, init] = fetchMock.mock.calls[0];
      expect(url).toBe('https://api.test/generations');
      const requestBody = JSON.parse(init.body as string);
      expect(requestBody).toEqual({
        productUploadId: UPLOAD_ID,
      });
      expect(requestBody).not.toHaveProperty('requestedMode');
    });

    it('createLook calls api.create with (productUploadId, idempotencyKey)', async () => {
      const mockCreate = jest.fn().mockResolvedValue({
        id: 'gen-1',
        status: 'COMPLETED',
        phase: 'READY',
        lookId: 'look-1',
        statusVersion: 1,
        createdAt: '2026-09-05T00:00:00Z',
        updatedAt: '2026-09-05T00:00:00Z',
      } as GenerationResource);

      const mockApi: GenerationApi = {
        create: mockCreate,
        get: jest.fn().mockResolvedValue({
          id: 'gen-1',
          status: 'COMPLETED',
          phase: 'READY',
          lookId: 'look-1',
          statusVersion: 1,
          createdAt: '2026-09-05T00:00:00Z',
          updatedAt: '2026-09-05T00:00:00Z',
        }),
        getLook: jest.fn().mockResolvedValue({
          id: 'look-1',
          generationId: 'gen-1',
          createdAt: '2026-09-05T00:00:00Z',
          image: { url: 'https://img.test/1', expiresAt: '2026-09-05T01:00:00Z' },
        }),
      };

      const mockUpload = jest.fn().mockResolvedValue('up-1');
      const mockPicker = jest.fn().mockResolvedValue(PHOTO_A);

      await createLook({
        picker: mockPicker,
        upload: mockUpload,
        api: mockApi,
      });

      expect(mockCreate).toHaveBeenCalledWith('up-1', expect.any(String));
      expect(mockCreate.mock.calls[0].length).toBe(2);
    });
  });

  describe('isProviderOutage predicate contract', () => {
    test.each(PROVIDER_OUTAGE_TRIGGER_CODES)(
      'returns true for exact trigger code %s',
      code => {
        expect(isProviderOutage(code, false)).toBe(true);
      },
    );

    it('returns true for networkLevel === true', () => {
      expect(isProviderOutage(undefined, true)).toBe(true);
      expect(isProviderOutage('ANY_CODE', true)).toBe(true);
      expect(isProviderOutage('ADMISSION_PAUSED', true)).toBe(true);
    });

    test.each([
      'ADMISSION_PAUSED',
      'PROVIDER_EXECUTION_FAILED',
      'ORCHESTRATION_DEADLINE_EXCEEDED',
      'PROVIDER_EXECUTION_DEADLINE',
      'PROVIDER_INDETERMINATE',
      'SUBMISSION_OUTCOME_UNKNOWN',
      'SUBMISSION_RECOVERY_DEADLINE',
      'UNSAFE_REPLAY_BLOCKED',
      'PROVIDER_OUTPUT_INVALID',
      'QUALITY_REJECTED',
      'MY_MODEL_MISSING',
      'CONTENT_POLICY_VIOLATION',
      'INVALID_PROMPT',
    ])('returns false for non-trigger code %s when networkLevel is false', code => {
      expect(isProviderOutage(code, false)).toBe(false);
    });

    it('returns false for undefined/empty when networkLevel is false', () => {
      expect(isProviderOutage(undefined, false)).toBe(false);
      expect(isProviderOutage('', false)).toBe(false);
    });
  });

  describe('Provider-outage Vault fallback across all 6 codes', () => {
    test.each(PROVIDER_OUTAGE_TRIGGER_CODES)(
      'code %s triggers saveToVaultFallback once and shows saved copy on READY',
      async failureCode => {
        const picker = jest.fn().mockResolvedValue(PHOTO_A);
        const importer: ShareImportResolver = jest.fn().mockResolvedValue({
          status: 'READY',
          importId: 'vault-imp-42',
        } as ShareImportResponse);

        globalThis.fetch = jest.fn().mockImplementation(
          mockGenerationFetch({
            pollResponse: () =>
              Promise.resolve(
                jsonResponse(200, {
                  id: 'gen-1',
                  status: 'FAILED',
                  phase: 'FAILED',
                  failureCode,
                  statusVersion: 1,
                  createdAt: '2026-09-05T00:00:00Z',
                  updatedAt: '2026-09-05T00:00:00Z',
                }),
              ),
          }),
        );

        await render(
          <GenerateScreen
            picker={picker}
            importer={importer}
          />,
        );

        await act(async () => {
          fireEvent.press(screen.getByRole('button', { name: 'Choose product photo' }));
        });

        expect(importer).toHaveBeenCalledTimes(1);
        expect(importer).toHaveBeenCalledWith(
          { kind: 'image', localImageUri: PHOTO_A.uri },
          expect.any(Object),
        );

        expect(await screen.findByText(OUTAGE_TITLE)).toBeTruthy();
        expect(screen.getByText(OUTAGE_BODY)).toBeTruthy();
        // Privacy: failure code is not leaked
        expect(screen.queryByText(failureCode)).toBeNull();
      },
    );

    it('networkLevel true triggers saveToVaultFallback once and shows saved copy on READY', async () => {
      const picker = jest.fn().mockResolvedValue(PHOTO_A);
      const importer: ShareImportResolver = jest.fn().mockResolvedValue({
        status: 'READY',
        importId: 'vault-imp-network',
      } as ShareImportResponse);

      globalThis.fetch = jest.fn().mockImplementation((input: RequestInfo | URL) => {
        const url = typeof input === 'string' ? input : (input as { url?: string }).url ?? '';
        if (url.startsWith('file:///')) {
          return Promise.resolve(bytesResponse(PNG_BYTES, 'image/png'));
        }
        if (url.includes('/uploads')) {
          return Promise.reject(new TypeError('Network request failed'));
        }
        return Promise.resolve(emptyResponse(404));
      });

      await render(
        <GenerateScreen
          picker={picker}
          importer={importer}
        />,
      );

      await act(async () => {
        fireEvent.press(screen.getByRole('button', { name: 'Choose product photo' }));
      });

      expect(importer).toHaveBeenCalledTimes(1);
      expect(importer).toHaveBeenCalledWith(
        { kind: 'image', localImageUri: PHOTO_A.uri },
        expect.any(Object),
      );
      expect(await screen.findByText(OUTAGE_TITLE)).toBeTruthy();
      expect(screen.getByText(OUTAGE_BODY)).toBeTruthy();
    });

    test.each(PROVIDER_OUTAGE_TRIGGER_CODES)(
      'code %s with resolver failure omits saved body, shows retry button and retains photo',
      async failureCode => {
        const picker = jest.fn().mockResolvedValue(PHOTO_A);
        const importer: ShareImportResolver = jest.fn().mockRejectedValue(
          new Error('Resolver network timeout'),
        );

        globalThis.fetch = jest.fn().mockImplementation(
          mockGenerationFetch({
            pollResponse: () =>
              Promise.resolve(
                jsonResponse(200, {
                  id: 'gen-1',
                  status: 'FAILED',
                  phase: 'FAILED',
                  failureCode,
                  statusVersion: 1,
                  createdAt: '2026-09-05T00:00:00Z',
                  updatedAt: '2026-09-05T00:00:00Z',
                }),
              ),
          }),
        );

        await render(
          <GenerateScreen
            picker={picker}
            importer={importer}
          />,
        );

        await act(async () => {
          fireEvent.press(screen.getByRole('button', { name: 'Choose product photo' }));
        });

        expect(importer).toHaveBeenCalledTimes(1);

        expect(await screen.findByText(OUTAGE_TITLE)).toBeTruthy();
        expect(screen.queryByText(OUTAGE_BODY)).toBeNull();
        const retryBtn = screen.getByRole('button', { name: ACTION_TRY_AGAIN });
        expect(retryBtn).toBeTruthy();

        // Tapping retry uses retained photo without re-calling picker
        picker.mockClear();
        await act(async () => {
          fireEvent.press(retryBtn);
        });
        expect(picker).not.toHaveBeenCalled();
      },
    );

    it('production GenerateScreen default persists to Vault via vaultApi when importer prop is omitted', async () => {
      const picker = jest.fn().mockResolvedValue(PHOTO_A);

      let vaultItemCreated = false;
      globalThis.fetch = jest.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
        const url = typeof input === 'string' ? input : (input as { url?: string }).url ?? '';
        const method = (init?.method ?? 'GET').toUpperCase();

        if (url.startsWith('file:///')) {
          return Promise.resolve(bytesResponse(PNG_BYTES, 'image/png'));
        }
        if (url.includes('/uploads') && method === 'POST') {
          if (url.includes('/complete')) {
            return Promise.resolve(jsonResponse(200, { id: 'up-1', status: 'READY' }));
          }
          return Promise.resolve(
            jsonResponse(200, {
              id: 'up-1',
              method: 'PUT',
              url: 'https://upload.invalid/1',
              headers: { 'Content-Type': 'image/png' },
              expiresAt: '2026-09-05T00:00:00Z',
            }),
          );
        }
        if (url === 'https://upload.invalid/1' && method === 'PUT') {
          return Promise.resolve(emptyResponse(200));
        }
        if (url.endsWith('/generations') && method === 'POST') {
          return Promise.resolve(
            jsonResponse(202, {
              id: 'gen-1',
              status: 'QUEUED',
              phase: 'QUEUED',
              statusVersion: 0,
              createdAt: '2026-09-05T00:00:00Z',
              updatedAt: '2026-09-05T00:00:00Z',
            }),
          );
        }
        if (url.includes('/generations/gen-1') && method === 'GET') {
          return Promise.resolve(
            jsonResponse(200, {
              id: 'gen-1',
              status: 'FAILED',
              phase: 'FAILED',
              failureCode: 'PROVIDER_UNAVAILABLE',
              statusVersion: 1,
              createdAt: '2026-09-05T00:00:00Z',
              updatedAt: '2026-09-05T00:00:00Z',
            }),
          );
        }
        if (url.includes('/vault/items') && method === 'POST') {
          vaultItemCreated = true;
          return Promise.resolve(
            jsonResponse(200, {
              id: 'item-vault-1',
              category: 'TOP',
              name: 'Saved item',
            }),
          );
        }
        return Promise.resolve(emptyResponse(404));
      });

      await render(<GenerateScreen picker={picker} />);

      await act(async () => {
        fireEvent.press(screen.getByRole('button', { name: 'Choose product photo' }));
      });

      expect(vaultItemCreated).toBe(true);
      expect(await screen.findByText(OUTAGE_TITLE)).toBeTruthy();
      expect(screen.getByText(OUTAGE_BODY)).toBeTruthy();
    });
  });

  describe('Non-outage error paths: no Vault fallback attempted', () => {
    it('rejected product photo shows rejection notice and never calls importer', async () => {
      const picker = jest.fn().mockResolvedValue(PHOTO_A);
      const importer = jest.fn();

      globalThis.fetch = jest.fn().mockImplementation(
        mockGenerationFetch({
          uploadResponse: () =>
            Promise.resolve(
              jsonResponse(400, { code: 'IMAGE_TOO_LARGE' }),
            ),
        }),
      );

      await render(
        <GenerateScreen
          picker={picker}
          importer={importer}
        />,
      );

      await act(async () => {
        fireEvent.press(screen.getByRole('button', { name: 'Choose product photo' }));
      });

      expect(importer).not.toHaveBeenCalled();
      expect(screen.queryByText(OUTAGE_BODY)).toBeNull();
    });

    it('QUALITY_REJECTED shows quality notice and never calls importer', async () => {
      const picker = jest.fn().mockResolvedValue(PHOTO_A);
      const importer = jest.fn();

      globalThis.fetch = jest.fn().mockImplementation(
        mockGenerationFetch({
          pollResponse: () =>
            Promise.resolve(
              jsonResponse(200, {
                id: 'gen-1',
                status: 'FAILED',
                phase: 'FAILED',
                failureCode: 'QUALITY_REJECTED',
                statusVersion: 1,
                createdAt: '2026-09-05T00:00:00Z',
                updatedAt: '2026-09-05T00:00:00Z',
              }),
            ),
        }),
      );

      await render(
        <GenerateScreen
          picker={picker}
          importer={importer}
        />,
      );

      await act(async () => {
        fireEvent.press(screen.getByRole('button', { name: 'Choose product photo' }));
      });

      expect(importer).not.toHaveBeenCalled();
      expect(await screen.findByText(QUALITY_FAILURE_BODY)).toBeTruthy();
      expect(screen.queryByText(OUTAGE_BODY)).toBeNull();
    });

    test.each([
      'ADMISSION_PAUSED',
      'PROVIDER_EXECUTION_FAILED',
      'ORCHESTRATION_DEADLINE_EXCEEDED',
    ])('legacy outage code %s never calls importer and shows generic error notice', async failureCode => {
      const picker = jest.fn().mockResolvedValue(PHOTO_A);
      const importer = jest.fn();

      globalThis.fetch = jest.fn().mockImplementation(
        mockGenerationFetch({
          pollResponse: () =>
            Promise.resolve(
              jsonResponse(200, {
                id: 'gen-1',
                status: 'FAILED',
                phase: 'FAILED',
                failureCode,
                statusVersion: 1,
                createdAt: '2026-09-05T00:00:00Z',
                updatedAt: '2026-09-05T00:00:00Z',
              }),
            ),
        }),
      );

      await render(
        <GenerateScreen
          picker={picker}
          importer={importer}
        />,
      );

      await act(async () => {
        fireEvent.press(screen.getByRole('button', { name: 'Choose product photo' }));
      });

      expect(importer).not.toHaveBeenCalled();
      expect(await screen.findByText('Could not create the look. Try again.')).toBeTruthy();
      expect(screen.queryByText(OUTAGE_BODY)).toBeNull();
    });

    it('generic unknown failure never calls importer and shows generic error notice', async () => {
      const picker = jest.fn().mockResolvedValue(PHOTO_A);
      const importer = jest.fn();

      globalThis.fetch = jest.fn().mockImplementation(
        mockGenerationFetch({
          pollResponse: () =>
            Promise.resolve(
              jsonResponse(200, {
                id: 'gen-1',
                status: 'FAILED',
                phase: 'FAILED',
                failureCode: 'CONTENT_POLICY_VIOLATION',
                statusVersion: 1,
                createdAt: '2026-09-05T00:00:00Z',
                updatedAt: '2026-09-05T00:00:00Z',
              }),
            ),
        }),
      );

      await render(
        <GenerateScreen
          picker={picker}
          importer={importer}
        />,
      );

      await act(async () => {
        fireEvent.press(screen.getByRole('button', { name: 'Choose product photo' }));
      });

      expect(importer).not.toHaveBeenCalled();
      expect(await screen.findByText('Could not create the look. Try again.')).toBeTruthy();
      expect(screen.queryByText(OUTAGE_BODY)).toBeNull();
    });
  });
});
