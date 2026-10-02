/**
 * C14: "pause foreground polling appropriately and refresh on resume".
 *
 * The observable contract is that a backgrounded app issues no polling
 * requests, and that the same poll finishes once the app is foregrounded
 * again — the job was never abandoned, just parked.
 */
import { pollGeneration } from '../src/features/generation/generationApi';
import type { ForegroundMonitor } from '../src/features/generation/generationApi';
import type { GenerationResource } from '../src/features/generation/types';

const GENERATION_ID = '44444444-4444-4444-4444-444444444444';

function job(
  status: GenerationResource['status'],
  phase: GenerationResource['phase'],
): GenerationResource {
  return {
    id: GENERATION_ID,
    status,
    phase,
    statusVersion: 1,
    createdAt: '2026-09-05T00:00:00Z',
    updatedAt: '2026-09-05T00:00:00Z',
  };
}

// Controllable foreground seam: tests decide when the app comes back.
function controllableMonitor(startForeground: boolean): {
  monitor: ForegroundMonitor;
  setForeground: (value: boolean) => void;
} {
  let foreground = startForeground;
  const listeners = new Set<(value: boolean) => void>();
  return {
    monitor: {
      isForeground: () => foreground,
      subscribe: listener => {
        listeners.add(listener);
        return () => listeners.delete(listener);
      },
    },
    setForeground: value => {
      foreground = value;
      listeners.forEach(listener => listener(value));
    },
  };
}

describe('foreground-aware polling', () => {
  test('issues no request while the app is backgrounded, then finishes after resume', async () => {
    const { monitor, setForeground } = controllableMonitor(false);
    const get = jest
      .fn<Promise<GenerationResource>, [string]>()
      .mockResolvedValue(job('GENERATING', 'GENERATING'));

    const poll = pollGeneration({
      get,
      id: GENERATION_ID,
      foreground: monitor,
      delay: () => Promise.resolve(),
    });

    // Parked: the loop has started but has not touched the network.
    await Promise.resolve();
    expect(get).not.toHaveBeenCalled();

    get.mockResolvedValue(job('COMPLETED', 'READY'));
    setForeground(true);

    const result = await poll;
    expect(result.status).toBe('COMPLETED');
    expect(get).toHaveBeenCalledTimes(1);
  });

  test('polls normally while the app stays in the foreground', async () => {
    const { monitor } = controllableMonitor(true);
    const get = jest
      .fn<Promise<GenerationResource>, [string]>()
      .mockResolvedValueOnce(job('GENERATING', 'GENERATING'))
      .mockResolvedValueOnce(job('COMPLETED', 'READY'));

    const result = await pollGeneration({
      get,
      id: GENERATION_ID,
      foreground: monitor,
      delay: () => Promise.resolve(),
    });

    expect(result.status).toBe('COMPLETED');
    expect(get).toHaveBeenCalledTimes(2);
  });

  test('an abort while parked rejects instead of hanging', async () => {
    const { monitor } = controllableMonitor(false);
    const controller = new AbortController();
    const get = jest
      .fn<Promise<GenerationResource>, [string]>()
      .mockResolvedValue(job('GENERATING', 'GENERATING'));

    const poll = pollGeneration({
      get,
      id: GENERATION_ID,
      foreground: monitor,
      signal: controller.signal,
      delay: () => Promise.resolve(),
    });
    await Promise.resolve();
    controller.abort();

    await expect(poll).rejects.toThrow('Aborted');
    expect(get).not.toHaveBeenCalled();
  });
});
