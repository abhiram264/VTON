import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react-native';
import GenerateScreen from '../src/screens/GenerateScreen';

jest.mock('../src/config/apiConfig', () => ({
  productionApiConfig: {
    baseUrl: 'https://api.test',
    getAccessToken: () => 'test-token',
  },
}));
import {
  ACTION_CHOOSE_ANOTHER_PHOTO,
  ACTION_TRY_AGAIN,
  OUTAGE_BODY,
  OUTAGE_TITLE,
  QUALITY_FAILURE_BODY,
  PROVIDER_OUTAGE_TRIGGER_CODES,
} from '../src/features/generation/failureContract';
import type { LocalPhoto } from '../src/features/my-model/types';
import type { VaultItemSummary } from '../src/features/vault/vaultApi';
import { clearActiveGeneration } from '../src/features/generation/activeGeneration';

const PNG_BYTES = new Uint8Array([0x89, 0x50, 0x4e, 0x47]).buffer;
const PHOTO_A: LocalPhoto = { uri: 'file:///product-a.png' };
const PHOTO_B: LocalPhoto = { uri: 'file:///product-b.png' };

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

function mockGenerationFetch(options: {
  pollResponse?: () => Promise<Response>;
  createResponse?: () => Promise<Response>;
  uploadResponse?: () => Promise<Response>;
  lookResponse?: () => Promise<Response>;
}) {
  return (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = requestUrl(input);
    const method = (init?.method ?? 'GET').toUpperCase();

    if (url.startsWith('file:///')) {
      return bytesResponse(PNG_BYTES, 'image/png');
    }
    if (url.includes('/uploads') && method === 'POST') {
      if (url.includes('/complete')) {
        return jsonResponse(200, { id: 'up-1', status: 'READY' });
      }
      if (options.uploadResponse) {
        return options.uploadResponse();
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
      if (options.createResponse) {
        return options.createResponse();
      }
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
      if (options.pollResponse) {
        return options.pollResponse();
      }
      return jsonResponse(200, {
        id: 'gen-1',
        status: 'COMPLETED',
        phase: 'READY',
        lookId: 'look-1',
        statusVersion: 1,
        createdAt: '2026-08-23T12:00:00Z',
        updatedAt: '2026-08-23T12:00:00Z',
      });
    }
    if (url.includes('/looks/look-1') && method === 'GET') {
      if (options.lookResponse) {
        return options.lookResponse();
      }
      return jsonResponse(200, {
        id: 'look-1',
        image: {
          url: 'https://signed.example.invalid/read',
          expiresAt: '2026-08-23T12:00:00Z',
        },
      });
    }
    throw new Error(`unexpected fetch ${method} ${url}`);
  };
}

describe('GenerateScreen', () => {
  const fetchMock = jest.fn();

  beforeEach(() => {
    fetchMock.mockReset();
    globalThis.fetch = fetchMock as typeof fetch;
    clearActiveGeneration();
  });

  test('quality failure shows exact copy, live region, and action buttons without generic notice or sensitive leaks', async () => {
    const picker = jest.fn().mockResolvedValue(PHOTO_A);
    fetchMock.mockImplementation(
      mockGenerationFetch({
        pollResponse: () =>
          jsonResponse(200, {
            id: 'gen-1',
            status: 'FAILED',
            phase: 'FAILED',
            failureCode: 'QUALITY_REJECTED',
            statusVersion: 1,
            createdAt: '2026-08-23T12:00:00Z',
            updatedAt: '2026-08-23T12:00:00Z',
          }),
      }),
    );

    await render(<GenerateScreen picker={picker} />);
    await act(async () => {
      fireEvent.press(
        screen.getByRole('button', { name: 'Choose product photo' }),
      );
    });

    const qualityNotice = await screen.findByText(QUALITY_FAILURE_BODY);
    expect(qualityNotice).toBeTruthy();
    expect(qualityNotice.props.accessibilityLiveRegion).toBe('polite');
    expect(
      screen.getByRole('button', { name: ACTION_TRY_AGAIN }),
    ).toBeTruthy();
    expect(
      screen.getByRole('button', { name: ACTION_CHOOSE_ANOTHER_PHOTO }),
    ).toBeTruthy();

    expect(
      screen.queryByText('Could not create the look. Try again.'),
    ).toBeNull();
    expect(screen.queryByText(OUTAGE_TITLE)).toBeNull();
    expect(screen.queryByText(OUTAGE_BODY)).toBeNull();
    expect(screen.queryByText('QUALITY_REJECTED')).toBeNull();
    expect(screen.queryByText(/https?:\/\//)).toBeNull();
    expect(screen.queryByText(/product-a\.png/)).toBeNull();
  });

  test('announces generation phase labels politely while polling', async () => {
    const picker = jest.fn().mockResolvedValue(PHOTO_A);
    let polls = 0;
    fetchMock.mockImplementation(
      mockGenerationFetch({
        pollResponse: () => {
          polls += 1;
          return polls === 1
            ? jsonResponse(200, {
                id: 'gen-1',
                status: 'GENERATING',
                phase: 'GENERATING',
                statusVersion: 1,
                createdAt: '2026-08-23T12:00:00Z',
                updatedAt: '2026-08-23T12:00:00Z',
              })
            : jsonResponse(200, {
                id: 'gen-1',
                status: 'COMPLETED',
                phase: 'READY',
                lookId: 'look-1',
                statusVersion: 2,
                createdAt: '2026-08-23T12:00:00Z',
                updatedAt: '2026-08-23T12:00:00Z',
              });
        },
      }),
    );

    await render(<GenerateScreen picker={picker} />);
    await act(async () => {
      fireEvent.press(
        screen.getByRole('button', { name: 'Choose product photo' }),
      );
    });

    const phase = await screen.findByText('Generating');
    expect(phase.props.accessibilityLiveRegion).toBe('polite');
  });

  test('non-retryable create outcome blocks a second paid attempt', async () => {
    const picker = jest.fn().mockResolvedValue(PHOTO_A);
    fetchMock.mockImplementation(
      mockGenerationFetch({
        createResponse: () => jsonResponse(409, { code: 'CREATE_OUTCOME_UNKNOWN' }),
      }),
    );

    await render(<GenerateScreen picker={picker} />);
    await act(async () => {
      fireEvent.press(
        screen.getByRole('button', { name: 'Choose product photo' }),
      );
    });

    expect(
      await screen.findByText('We could not confirm whether this Look was created.'),
    ).toBeTruthy();
    expect(screen.queryByRole('button', { name: ACTION_TRY_AGAIN })).toBeNull();
    expect(screen.queryByText(OUTAGE_TITLE)).toBeNull();
  });

  test('quality Try Again reuses retained photo, skips picker, and executes fresh upload/create', async () => {
    const picker = jest.fn().mockResolvedValue(PHOTO_A);
    const onCreateLook = jest.fn();
    let pollCount = 0;

    fetchMock.mockImplementation(
      mockGenerationFetch({
        pollResponse: () => {
          pollCount += 1;
          if (pollCount === 1) {
            return jsonResponse(200, {
              id: 'gen-1',
              status: 'FAILED',
              phase: 'FAILED',
              failureCode: 'QUALITY_REJECTED',
              statusVersion: 1,
              createdAt: '2026-08-23T12:00:00Z',
              updatedAt: '2026-08-23T12:00:00Z',
            });
          }
          return jsonResponse(200, {
            id: 'gen-1',
            status: 'COMPLETED',
            phase: 'READY',
            lookId: 'look-1',
            statusVersion: 2,
            createdAt: '2026-08-23T12:00:00Z',
            updatedAt: '2026-08-23T12:00:00Z',
          });
        },
      }),
    );

    await render(
      <GenerateScreen picker={picker} onCreateLook={onCreateLook} />,
    );
    await act(async () => {
      fireEvent.press(
        screen.getByRole('button', { name: 'Choose product photo' }),
      );
    });

    expect(await screen.findByText(QUALITY_FAILURE_BODY)).toBeTruthy();
    expect(picker).toHaveBeenCalledTimes(1);

    await act(async () => {
      fireEvent.press(screen.getByRole('button', { name: ACTION_TRY_AGAIN }));
    });

    await waitFor(() => {
      expect(onCreateLook).toHaveBeenCalledWith('look-1');
    });
    expect(picker).toHaveBeenCalledTimes(1);
  });

  test('quality Choose Another Photo reopens picker and uses newly picked photo', async () => {
    const picker = jest
      .fn()
      .mockResolvedValueOnce(PHOTO_A)
      .mockResolvedValueOnce(PHOTO_B);
    const onCreateLook = jest.fn();
    let pollCount = 0;

    fetchMock.mockImplementation(
      mockGenerationFetch({
        pollResponse: () => {
          pollCount += 1;
          if (pollCount === 1) {
            return jsonResponse(200, {
              id: 'gen-1',
              status: 'FAILED',
              phase: 'FAILED',
              failureCode: 'QUALITY_REJECTED',
              statusVersion: 1,
              createdAt: '2026-08-23T12:00:00Z',
              updatedAt: '2026-08-23T12:00:00Z',
            });
          }
          return jsonResponse(200, {
            id: 'gen-1',
            status: 'COMPLETED',
            phase: 'READY',
            lookId: 'look-1',
            statusVersion: 2,
            createdAt: '2026-08-23T12:00:00Z',
            updatedAt: '2026-08-23T12:00:00Z',
          });
        },
      }),
    );

    await render(
      <GenerateScreen picker={picker} onCreateLook={onCreateLook} />,
    );
    await act(async () => {
      fireEvent.press(
        screen.getByRole('button', { name: 'Choose product photo' }),
      );
    });

    expect(await screen.findByText(QUALITY_FAILURE_BODY)).toBeTruthy();
    expect(picker).toHaveBeenCalledTimes(1);

    await act(async () => {
      fireEvent.press(
        screen.getByRole('button', { name: ACTION_CHOOSE_ANOTHER_PHOTO }),
      );
    });

    await waitFor(() => {
      expect(onCreateLook).toHaveBeenCalledWith('look-1');
    });
    expect(picker).toHaveBeenCalledTimes(2);
  });

  test('terminal PROVIDER_UNAVAILABLE outage saves item to Vault and displays truthful copy', async () => {
    const picker = jest.fn().mockResolvedValue(PHOTO_A);
    const vaultSave = jest.fn().mockResolvedValue({
      id: 'vault-item-1',
      userId: 'user-1',
      imageId: 'img-1',
      name: 'Item',
      category: 'TOP',
      createdAt: '2026-08-23T12:00:00Z',
    } as VaultItemSummary);

    fetchMock.mockImplementation(
      mockGenerationFetch({
        pollResponse: () =>
          jsonResponse(200, {
            id: 'gen-1',
            status: 'FAILED',
            phase: 'FAILED',
            failureCode: 'PROVIDER_UNAVAILABLE',
            statusVersion: 1,
            createdAt: '2026-08-23T12:00:00Z',
            updatedAt: '2026-08-23T12:00:00Z',
          }),
      }),
    );

    await render(
      <GenerateScreen
        picker={picker}
        vault={{ createFromPhoto: vaultSave } as unknown as typeof import('../src/features/vault/vaultApi').vaultApi}
      />,
    );
    await act(async () => {
      fireEvent.press(
        screen.getByRole('button', { name: 'Choose product photo' }),
      );
    });

    const outageTitle = await screen.findByText(OUTAGE_TITLE);
    const outageBody = await screen.findByText(OUTAGE_BODY);
    expect(outageTitle).toBeTruthy();
    expect(outageBody).toBeTruthy();
    expect(outageTitle.props.accessibilityLiveRegion).toBe('polite');
    expect(outageBody.props.accessibilityLiveRegion).toBe('polite');
    expect(vaultSave).toHaveBeenCalledWith(PHOTO_A);

    expect(screen.queryByText('PROVIDER_UNAVAILABLE')).toBeNull();
    expect(screen.queryByText(/https?:\/\//)).toBeNull();
    expect(screen.queryByText(/product-a\.png/)).toBeNull();
  });

  test('outage persists to Vault truthfully: OUTAGE_BODY only appears after Vault save resolves', async () => {
    const picker = jest.fn().mockResolvedValue(PHOTO_A);
    let resolveVault!: (item: VaultItemSummary) => void;
    const vaultPromise = new Promise<VaultItemSummary>(resolve => {
      resolveVault = resolve;
    });
    const vaultSave = jest.fn().mockReturnValue(vaultPromise);

    fetchMock.mockImplementation(
      mockGenerationFetch({
        pollResponse: () =>
          jsonResponse(200, {
            id: 'gen-1',
            status: 'FAILED',
            phase: 'FAILED',
            failureCode: 'PROVIDER_UNAVAILABLE',
            statusVersion: 1,
            createdAt: '2026-08-23T12:00:00Z',
            updatedAt: '2026-08-23T12:00:00Z',
          }),
      }),
    );

    await render(
      <GenerateScreen
        picker={picker}
        vault={{ createFromPhoto: vaultSave } as unknown as typeof import('../src/features/vault/vaultApi').vaultApi}
      />,
    );
    await act(async () => {
      fireEvent.press(
        screen.getByRole('button', { name: 'Choose product photo' }),
      );
    });

    const outageTitle = await screen.findByText(OUTAGE_TITLE);
    expect(outageTitle).toBeTruthy();
    expect(outageTitle.props.accessibilityLiveRegion).toBe('polite');
    expect(screen.queryByText(OUTAGE_BODY)).toBeNull();

    await act(async () => {
      resolveVault({
        id: 'vault-item-1',
        userId: 'user-1',
        imageId: 'img-1',
        name: 'Item',
        category: 'TOP',
        createdAt: '2026-08-23T12:00:00Z',
      } as VaultItemSummary);
    });

    expect(await screen.findByText(OUTAGE_BODY)).toBeTruthy();
  });

  test('vault save failure fallback: displays OUTAGE_TITLE + Try Again, omits OUTAGE_BODY', async () => {
    const picker = jest.fn().mockResolvedValue(PHOTO_A);
    const vaultSave = jest
      .fn()
      .mockRejectedValue(new Error('Vault storage unavailable'));

    fetchMock.mockImplementation(
      mockGenerationFetch({
        pollResponse: () =>
          jsonResponse(200, {
            id: 'gen-1',
            status: 'FAILED',
            phase: 'FAILED',
            failureCode: 'PROVIDER_UNAVAILABLE',
            statusVersion: 1,
            createdAt: '2026-08-23T12:00:00Z',
            updatedAt: '2026-08-23T12:00:00Z',
          }),
      }),
    );

    await render(
      <GenerateScreen
        picker={picker}
        vault={{ createFromPhoto: vaultSave } as unknown as typeof import('../src/features/vault/vaultApi').vaultApi}
      />,
    );
    await act(async () => {
      fireEvent.press(
        screen.getByRole('button', { name: 'Choose product photo' }),
      );
    });

    const outageTitle = await screen.findByText(OUTAGE_TITLE);
    expect(outageTitle).toBeTruthy();
    expect(outageTitle.props.accessibilityLiveRegion).toBe('polite');
    expect(screen.queryByText(OUTAGE_BODY)).toBeNull();
    expect(
      screen.getByRole('button', { name: ACTION_TRY_AGAIN }),
    ).toBeTruthy();

    expect(screen.queryByText('Vault storage unavailable')).toBeNull();
    expect(screen.queryByText(/https?:\/\//)).toBeNull();
  });

  test('create-time ADMISSION_PAUSED 503 shows generic notice without outage or Vault persist', async () => {
    const picker = jest.fn().mockResolvedValue(PHOTO_A);
    const vaultSave = jest.fn().mockResolvedValue({
      id: 'vault-1',
    } as VaultItemSummary);

    fetchMock.mockImplementation(
      mockGenerationFetch({
        createResponse: () =>
          jsonResponse(503, { code: 'ADMISSION_PAUSED' }),
      }),
    );

    await render(
      <GenerateScreen
        picker={picker}
        vault={{ createFromPhoto: vaultSave } as unknown as typeof import('../src/features/vault/vaultApi').vaultApi}
      />,
    );
    await act(async () => {
      fireEvent.press(
        screen.getByRole('button', { name: 'Choose product photo' }),
      );
    });

    const notice = await screen.findByText(
      'Could not create the look. Try again.',
    );
    expect(notice).toBeTruthy();
    expect(notice.props.accessibilityLiveRegion).toBe('polite');
    expect(screen.queryByRole('button', { name: ACTION_TRY_AGAIN })).toBeNull();
    expect(screen.queryByText(OUTAGE_TITLE)).toBeNull();
    expect(screen.queryByText(OUTAGE_BODY)).toBeNull();
    expect(vaultSave).not.toHaveBeenCalled();
    expect(screen.queryByText('ADMISSION_PAUSED')).toBeNull();
    expect(screen.queryByText(/product-a\.png/)).toBeNull();
  });

  test('uncoded create-phase network failure blocks retry and does not persist to Vault', async () => {
    const picker = jest.fn().mockResolvedValue(PHOTO_A);
    const vaultSave = jest.fn().mockResolvedValue({
      id: 'vault-1',
    } as VaultItemSummary);

    fetchMock.mockImplementation((input, init) => {
      const url = requestUrl(input);
      const method = (init?.method ?? 'GET').toUpperCase();
      if (url.startsWith('file:///')) {
        return bytesResponse(PNG_BYTES, 'image/png');
      }
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
        return Promise.reject(new TypeError('Network request failed'));
      }
      throw new Error(`unexpected fetch ${method} ${url}`);
    });

    await render(
      <GenerateScreen
        picker={picker}
        vault={{ createFromPhoto: vaultSave } as unknown as typeof import('../src/features/vault/vaultApi').vaultApi}
      />,
    );
    await act(async () => {
      fireEvent.press(
        screen.getByRole('button', { name: 'Choose product photo' }),
      );
    });

    expect(
      await screen.findByText('We could not confirm whether this Look was created.'),
    ).toBeTruthy();
    expect(screen.queryByRole('button', { name: ACTION_TRY_AGAIN })).toBeNull();
    expect(screen.queryByText(OUTAGE_TITLE)).toBeNull();
    expect(vaultSave).not.toHaveBeenCalled();
  });

  test.each(PROVIDER_OUTAGE_TRIGGER_CODES)(
    'terminal outage failureCode %s triggers outage view',
    async failureCode => {
      const picker = jest.fn().mockResolvedValue(PHOTO_A);
      const vaultSave = jest.fn().mockResolvedValue({ id: 'v-1' } as VaultItemSummary);

      fetchMock.mockImplementation(
        mockGenerationFetch({
          pollResponse: () =>
            jsonResponse(200, {
              id: 'gen-1',
              status: 'FAILED',
              phase: 'FAILED',
              failureCode,
              statusVersion: 1,
              createdAt: '2026-08-23T12:00:00Z',
              updatedAt: '2026-08-23T12:00:00Z',
            }),
        }),
      );

      await render(
        <GenerateScreen
          picker={picker}
          vault={{ createFromPhoto: vaultSave } as unknown as typeof import('../src/features/vault/vaultApi').vaultApi}
        />,
      );
      await act(async () => {
        fireEvent.press(
          screen.getByRole('button', { name: 'Choose product photo' }),
        );
      });

      expect(await screen.findByText(OUTAGE_TITLE)).toBeTruthy();
      expect(screen.getByText(OUTAGE_BODY)).toBeTruthy();
      expect(vaultSave).toHaveBeenCalledWith(PHOTO_A);
      expect(screen.queryByText(failureCode)).toBeNull();
    },
  );

  test.each([
    'PROVIDER_EXECUTION_FAILED',
    'PROVIDER_EXECUTION_DEADLINE',
    'PROVIDER_INDETERMINATE',
    'SUBMISSION_OUTCOME_UNKNOWN',
    'SUBMISSION_RECOVERY_DEADLINE',
    'UNSAFE_REPLAY_BLOCKED',
    'PROVIDER_OUTPUT_INVALID',
    'ORCHESTRATION_DEADLINE_EXCEEDED',
    'ADMISSION_PAUSED',
  ])(
    'legacy outage failureCode %s produces generic notice without outage view or Vault save',
    async failureCode => {
      const picker = jest.fn().mockResolvedValue(PHOTO_A);
      const vaultSave = jest.fn().mockResolvedValue({ id: 'v-1' } as VaultItemSummary);

      fetchMock.mockImplementation(
        mockGenerationFetch({
          pollResponse: () =>
            jsonResponse(200, {
              id: 'gen-1',
              status: 'FAILED',
              phase: 'FAILED',
              failureCode,
              statusVersion: 1,
              createdAt: '2026-08-23T12:00:00Z',
              updatedAt: '2026-08-23T12:00:00Z',
            }),
        }),
      );

      await render(
        <GenerateScreen
          picker={picker}
          vault={{ createFromPhoto: vaultSave } as unknown as typeof import('../src/features/vault/vaultApi').vaultApi}
        />,
      );
      await act(async () => {
        fireEvent.press(
          screen.getByRole('button', { name: 'Choose product photo' }),
        );
      });

      const notice = await screen.findByText(
        'Could not create the look. Try again.',
      );
      expect(notice).toBeTruthy();
      expect(notice.props.accessibilityLiveRegion).toBe('polite');
      expect(screen.queryByText(OUTAGE_TITLE)).toBeNull();
      expect(screen.queryByText(OUTAGE_BODY)).toBeNull();
      expect(vaultSave).not.toHaveBeenCalled();
      expect(screen.queryByText(failureCode)).toBeNull();
    },
  );

  test('MY_MODEL_MISSING preserves notice and triggers onMissingModel callback', async () => {
    const picker = jest.fn().mockResolvedValue(PHOTO_A);
    const onMissingModel = jest.fn();

    fetchMock.mockImplementation(
      mockGenerationFetch({
        pollResponse: () =>
          jsonResponse(200, {
            id: 'gen-1',
            status: 'FAILED',
            phase: 'FAILED',
            failureCode: 'MY_MODEL_MISSING',
            statusVersion: 1,
            createdAt: '2026-08-23T12:00:00Z',
            updatedAt: '2026-08-23T12:00:00Z',
          }),
      }),
    );

    await render(
      <GenerateScreen picker={picker} onMissingModel={onMissingModel} />,
    );
    await act(async () => {
      fireEvent.press(
        screen.getByRole('button', { name: 'Choose product photo' }),
      );
    });

    const notice = await screen.findByText('Create your My Model first.');
    expect(notice).toBeTruthy();
    expect(notice.props.accessibilityLiveRegion).toBe('polite');
    expect(onMissingModel).toHaveBeenCalledTimes(1);
    expect(screen.queryByText(QUALITY_FAILURE_BODY)).toBeNull();
    expect(screen.queryByText(OUTAGE_TITLE)).toBeNull();
  });

  test('image rejection preserves specific rejection notice', async () => {
    const picker = jest.fn().mockResolvedValue(PHOTO_A);

    fetchMock.mockImplementation((input, init) => {
      const url = requestUrl(input);
      const method = (init?.method ?? 'GET').toUpperCase();
      if (url.startsWith('file:///')) {
        return bytesResponse(new ArrayBuffer(31 * 1024 * 1024), 'image/png');
      }
      throw new Error(`unexpected fetch ${method} ${url}`);
    });

    await render(<GenerateScreen picker={picker} />);
    await act(async () => {
      fireEvent.press(
        screen.getByRole('button', { name: 'Choose product photo' }),
      );
    });

    const notice = await screen.findByText(
      'That image is too large. Use an image up to 30 MB.',
    );
    expect(notice).toBeTruthy();
    expect(notice.props.accessibilityLiveRegion).toBe('polite');
    expect(screen.queryByText(QUALITY_FAILURE_BODY)).toBeNull();
    expect(screen.queryByText(OUTAGE_TITLE)).toBeNull();
  });

  test('unmapped generic failure preserves generic notice', async () => {
    const picker = jest.fn().mockResolvedValue(PHOTO_A);

    fetchMock.mockImplementation(
      mockGenerationFetch({
        pollResponse: () =>
          jsonResponse(200, {
            id: 'gen-1',
            status: 'FAILED',
            phase: 'FAILED',
            failureCode: 'UNKNOWN_ERROR',
            statusVersion: 1,
            createdAt: '2026-08-23T12:00:00Z',
            updatedAt: '2026-08-23T12:00:00Z',
          }),
      }),
    );

    await render(<GenerateScreen picker={picker} />);
    await act(async () => {
      fireEvent.press(
        screen.getByRole('button', { name: 'Choose product photo' }),
      );
    });

    const notice = await screen.findByText(
      'Could not create the look. Try again.',
    );
    expect(notice).toBeTruthy();
    expect(notice.props.accessibilityLiveRegion).toBe('polite');
    expect(screen.queryByText(QUALITY_FAILURE_BODY)).toBeNull();
    expect(screen.queryByText(OUTAGE_TITLE)).toBeNull();
    expect(screen.queryByText('UNKNOWN_ERROR')).toBeNull();
  });
  test('malformed generation response shows generic notice without outage or Vault persist', async () => {
    const picker = jest.fn().mockResolvedValue(PHOTO_A);
    const vaultSave = jest.fn();

    fetchMock.mockImplementation(
      mockGenerationFetch({
        createResponse: () =>
          jsonResponse(202, { id: 'gen-1', status: 'QUEUED' }),
      }),
    );

    await render(
      <GenerateScreen
        picker={picker}
        vault={{ createFromPhoto: vaultSave } as unknown as typeof import('../src/features/vault/vaultApi').vaultApi}
      />,
    );
    await act(async () => {
      fireEvent.press(
        screen.getByRole('button', { name: 'Choose product photo' }),
      );
    });

    const notice = await screen.findByText(
      'Could not create the look. Try again.',
    );
    expect(notice).toBeTruthy();
    expect(notice.props.accessibilityLiveRegion).toBe('polite');
    expect(screen.queryByRole('button', { name: ACTION_TRY_AGAIN })).toBeNull();
    expect(screen.queryByText(OUTAGE_TITLE)).toBeNull();
    expect(screen.queryByText(OUTAGE_BODY)).toBeNull();
    expect(vaultSave).not.toHaveBeenCalled();
  });

  test('the screen title is exposed as an accessible header', async () => {
    await render(<GenerateScreen />);

    // A screen reader announces this as a heading, so the user can jump to it
    // instead of hearing an unlabelled run of text.
    expect(await screen.findByRole('header', { name: 'Create a Look' })).toBeTruthy();
  });
});
