/**
 * C14 recovery/cancellation contract.
 *
 * The behaviours defended here are the ones that decide whether a user loses
 * an already-charged job after a restart, and whether "cancel requested" is
 * ever presented as "provider stopped".
 */
import {
  createGenerationApi,
} from './generationApi';
import {
  CANCELLED_BY_USER,
  GENERATION_NOT_CANCELLABLE,
  recoverActiveGeneration,
  requestCancellation,
} from './generationLifecycle';
import type { GenerationApi, GenerationResource } from './types';
import { ApiProblemError } from '../my-model/http';

const BASE_URL = 'https://api.test';
const GENERATION_ID = '44444444-4444-4444-4444-444444444444';

function job(
  overrides: Partial<GenerationResource> = {},
): GenerationResource {
  return {
    id: GENERATION_ID,
    status: 'QUEUED',
    phase: 'QUEUED',
    statusVersion: 0,
    createdAt: '2026-09-05T00:00:00Z',
    updatedAt: '2026-09-05T00:00:00Z',
    ...overrides,
  };
}

describe('recoverActiveGeneration', () => {
  test('returns the newest non-terminal job from the server list', async () => {
    const completed = job({
      id: 'newest',
      status: 'COMPLETED',
      phase: 'READY',
    });
    const active = job({
      id: 'active',
      status: 'GENERATING',
      phase: 'GENERATING',
      statusVersion: 3,
    });
    const older = job({ id: 'older', status: 'QUEUED' });
    const list = jest
      .fn<Promise<GenerationResource[]>, []>()
      .mockResolvedValue([completed, active, older]);

    const recovery = await recoverActiveGeneration({ list });

    expect(recovery).toEqual({ kind: 'active', generation: active });
    expect(list).toHaveBeenCalledTimes(1);
  });

  test('reports none when every listed job is terminal', async () => {
    const list = jest
      .fn<Promise<GenerationResource[]>, []>()
      .mockResolvedValue([
        job({ id: 'a', status: 'FAILED', phase: 'FAILED' }),
        job({ id: 'b', status: 'COMPLETED', phase: 'READY' }),
      ]);

    expect(await recoverActiveGeneration({ list })).toEqual({ kind: 'none' });
  });

  test('reports none for an empty list, and never conflates it with a failed lookup', async () => {
    const empty = jest
      .fn<Promise<GenerationResource[]>, []>()
      .mockResolvedValue([]);
    const failing = jest
      .fn<Promise<GenerationResource[]>, []>()
      .mockRejectedValue(new TypeError('Network request failed'));

    expect(await recoverActiveGeneration({ list: empty })).toEqual({
      kind: 'none',
    });
    expect(await recoverActiveGeneration({ list: failing })).toEqual({
      kind: 'unavailable',
    });
  });

  test('treats a non-array payload as unavailable rather than as no work', async () => {
    const list = jest
      .fn<Promise<GenerationResource[]>, []>()
      .mockResolvedValue(
        undefined as unknown as GenerationResource[],
      );

    expect(await recoverActiveGeneration({ list })).toEqual({
      kind: 'unavailable',
    });
  });
});

describe('requestCancellation', () => {
  const api = (cancel: GenerationApi['cancel']) => ({ cancel });

  test('reports cancelled only when the server reached the terminal cancelled state', async () => {
    const cancelled = job({
      status: 'FAILED',
      phase: 'FAILED',
      failureCode: CANCELLED_BY_USER,
    });
    const result = await requestCancellation(
      api(jest.fn().mockResolvedValue(cancelled)),
      GENERATION_ID,
    );

    expect(result).toEqual({ outcome: 'cancelled', generation: cancelled });
  });

  test('reports cancel-requested while the provider has not stopped', async () => {
    const stillRunning = job({ status: 'GENERATING', phase: 'GENERATING' });
    const result = await requestCancellation(
      api(jest.fn().mockResolvedValue(stillRunning)),
      GENERATION_ID,
    );

    expect(result).toEqual({
      outcome: 'cancel-requested',
      generation: stillRunning,
    });
  });

  test('reports not-cancellable for the server conflict, and failure for anything else', async () => {
    const conflict = await requestCancellation(
      api(
        jest
          .fn()
          .mockRejectedValue(new ApiProblemError(GENERATION_NOT_CANCELLABLE)),
      ),
      GENERATION_ID,
    );
    const other = await requestCancellation(
      api(jest.fn().mockRejectedValue(new ApiProblemError('HTTP_503'))),
      GENERATION_ID,
    );
    const unknown = await requestCancellation(
      api(jest.fn().mockRejectedValue(new TypeError('Network request failed'))),
      GENERATION_ID,
    );

    expect(conflict).toEqual({ outcome: 'not-cancellable' });
    expect(other).toEqual({ outcome: 'failed', code: 'HTTP_503' });
    expect(unknown).toEqual({ outcome: 'failed' });
  });
});

describe('generation api list/cancel endpoints', () => {
  const fetchMock = jest.fn();

  beforeEach(() => {
    fetchMock.mockReset();
    globalThis.fetch = fetchMock as typeof fetch;
  });

  const config = { baseUrl: BASE_URL, getAccessToken: () => 'token' };

  test('list reads the owner-scoped collection from GET /generations', async () => {
    fetchMock.mockResolvedValue({
      ok: true,
      status: 200,
      headers: { get: () => 'application/json' },
      json: () => Promise.resolve([job({ status: 'GENERATING' })]),
    } as unknown as Response);

    const jobs = await createGenerationApi(config).list();

    expect(fetchMock.mock.calls[0][0]).toBe(`${BASE_URL}/generations`);
    expect((fetchMock.mock.calls[0][1] as RequestInit).method).toBe('GET');
    expect(jobs).toHaveLength(1);
    expect(jobs[0].status).toBe('GENERATING');
  });

  test('list rejects a non-array payload instead of reporting no work', async () => {
    fetchMock.mockResolvedValue({
      ok: true,
      status: 200,
      headers: { get: () => 'application/json' },
      json: () => Promise.resolve({ generations: [] }),
    } as unknown as Response);

    await expect(createGenerationApi(config).list()).rejects.toThrow(
      'Invalid generation list response',
    );
  });

  test('cancel posts to the mounted cancel route and parses the resource', async () => {
    fetchMock.mockResolvedValue({
      ok: true,
      status: 200,
      headers: { get: () => 'application/json' },
      json: () =>
        Promise.resolve(
          job({ status: 'FAILED', phase: 'FAILED', failureCode: 'CANCELLED_BY_USER' }),
        ),
    } as unknown as Response);

    const result = await createGenerationApi(config).cancel(GENERATION_ID);

    expect(fetchMock.mock.calls[0][0]).toBe(
      `${BASE_URL}/generations/${GENERATION_ID}/cancel`,
    );
    expect((fetchMock.mock.calls[0][1] as RequestInit).method).toBe('POST');
    expect(result.failureCode).toBe('CANCELLED_BY_USER');
  });
});
