import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { NavigationContainer } from '@react-navigation/native';
import { generationApi } from '../src/features/generation/generationApi';
import LooksScreen from '../src/screens/LooksScreen';
import { looksApi } from '../src/features/looks/looksApi';
// The bound-pinning test reads the constants from the REAL module, past this
// file's jest.mock, which replaces the whole module export surface.
const {POLL_DEADLINE_MS, POLL_MAX_ATTEMPTS, pollGeneration} = jest.requireActual(
  '../src/features/generation/generationApi',
);
import type { GenerationResource, LookResource } from '../src/features/generation/types';

jest.mock('../src/features/looks/looksApi', () => ({
  looksApi: { list: jest.fn(), regenerate: jest.fn() },
}));

jest.mock('../src/features/generation/generationApi', () => ({
  generationApi: { get: jest.fn(), list: jest.fn().mockResolvedValue([]) },
  // The real poller: the bound under test lives in it, not in a double.
  pollGeneration: jest.requireActual('../src/features/generation/generationApi')
    .pollGeneration,
}));

const noop = () => undefined;
// The screen-level test below drives the poll through a REJECTING get, so it
// exercises the screen's error surfacing and lock release without depending on
// how many attempts the production bound allows. The bound itself is proven
// directly against the real poller in the test below this one. An instant
// delay keeps both cheap.
const instantDelay = (_ms: number) => Promise.resolve();


const look: LookResource = {
  id: 'look-1',
  generationId: 'old-generation',
  createdAt: '2026-08-30T10:00:00Z',
  image: {
    url: 'https://signed.example.invalid/read?X-Amz-Signature=read-1',
    expiresAt: '2026-08-30T10:05:00Z',
  },
};

// A generation the server never finishes: every poll returns the same
// non-terminal status, forever.
const stuck: GenerationResource = {
  id: 'generation-stuck',
  status: 'GENERATING',
  phase: 'GENERATING',
  statusVersion: 1,
  createdAt: '',
  updatedAt: '',
};

beforeEach(() => jest.clearAllMocks());

// The client bound must not expire before the SERVER's own job deadline, or a
// slow-but-successful generation is reported as a client-side failure while the
// server completes it correctly. The server allows a job
// ORCHESTRATION_DEADLINE = 30 minutes (GenerationJob.java:61) with up to 2
// technical retries / 3 transmissions (GenerationRetryBudget.java:9), each
// bounded by the adapter read timeout of 300s (InferHubConfig.java:32). The
// invariant below is asserted against that deadline rather than a bare literal,
// so a silent re-narrow below the server budget fails here even though this
// file exercises the attempt bound via the injected fast delay, not the
// wall-clock deadline.
test('default poll bound covers the server job deadline and its retries', () => {
  const SERVER_ORCHESTRATION_DEADLINE_MS = 30 * 60 * 1000;
  const ADAPTER_READ_TIMEOUT_MS = 300 * 1000;
  const MAX_TRANSMISSIONS = 3;
  const POLL_DEADLINE_MARGIN_MS = 60 * 1000;

  expect(POLL_DEADLINE_MS).toBeGreaterThanOrEqual(SERVER_ORCHESTRATION_DEADLINE_MS);
  expect(POLL_DEADLINE_MS).toBeGreaterThanOrEqual(
    MAX_TRANSMISSIONS * ADAPTER_READ_TIMEOUT_MS,
  );
  expect(POLL_MAX_ATTEMPTS * 2000).toBeGreaterThanOrEqual(POLL_DEADLINE_MS);
  // And an UPPER bound, so the set is not one-directional: the client should
  // wait out the server's budget plus its documented margin, not an arbitrary
  // longer time. Without this, a pathologically large bound would satisfy every
  // assertion above.
  expect(POLL_DEADLINE_MS).toBeLessThanOrEqual(
    SERVER_ORCHESTRATION_DEADLINE_MS + POLL_DEADLINE_MARGIN_MS,
  );
});

// The production attempt bound is what makes the poll finite; this asserts it
// directly against the real poller, at the real defaults, with no screen in the
// loop so 930 attempts cost microseconds instead of React renders.
test('the production attempt bound terminates a generation that never settles', async () => {
  await expect(
    pollGeneration({
      get: async () => stuck,
      id: stuck.id,
      delay: instantDelay,
    }),
  ).rejects.toThrow(/maximum attempts exceeded/i);
});

test('a generation that cannot be checked ends in a visible error and releases the busy lock', async () => {
  jest.mocked(looksApi.list).mockResolvedValue([look]);
  jest.mocked(looksApi.regenerate).mockResolvedValue(stuck);
  // The check itself fails, which is the same catch path a bound exhaustion
  // takes: the screen must surface an error and release its busy lock rather
  // than leaving the user locked out.
  jest.mocked(generationApi.get).mockRejectedValue(new Error('check failed'));
  const open = jest.fn();

  await render(
    <NavigationContainer>
      <LooksScreen
        onModify={noop}
        onOpenLook={open}
        onProfile={noop}
        pollDelay={instantDelay}
      />
    </NavigationContainer>,
  );
  await fireEvent.press(await screen.findByRole('button', { name: 'Look from 30 Aug 2026' }));
  const regenerate = screen.getByRole('button', { name: 'Regenerate' });
  await fireEvent.press(regenerate);

  // The bound must end the poll on its own: an error the user can read, and
  // no Look opened.
  expect(await screen.findByText(/could not check the accepted generation/i)).toBeTruthy();
  expect(open).not.toHaveBeenCalled();

  // The lock is released: the retry control is live again.
  await waitFor(() =>
    expect(
      screen.getByRole('button', { name: 'Resume regeneration' }),
    ).toBeEnabled(),
  );
// The default 5s is a cliff on a loaded CI box: this test drives a real render,
// a press, a poll round-trip and two awaited queries, all of which the wave's
// full-suite runs have measured at 6-25s under concurrent load while the work
// itself is milliseconds. The timeout is raised rather than the assertions
// relaxed, so a genuinely hung screen still fails here.
}, 30_000);
