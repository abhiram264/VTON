import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { NavigationContainer } from '@react-navigation/native';
import LooksScreen from '../src/screens/LooksScreen';
import { looksApi } from '../src/features/looks/looksApi';
import type { GenerationResource, LookResource } from '../src/features/generation/types';
import { generationApi } from '../src/features/generation/generationApi';
import { Share } from 'react-native';
import { ApiProblemError } from '../src/features/my-model/http';
jest.mock('../src/features/looks/looksApi', () => ({
  looksApi: { list: jest.fn(), regenerate: jest.fn(), get: jest.fn(), remove: jest.fn() },
}));
// `pollGeneration` stays real so the bound under test is the real one; only the
// network client is faked, and it is faked per test by clearAllMocks.
jest.mock('../src/features/generation/generationApi', () => ({
  generationApi: { get: jest.fn(), list: jest.fn() },
  pollGeneration: jest.requireActual('../src/features/generation/generationApi').pollGeneration,
  // generationLifecycle (real, under test) classifies terminal states with the
  // real predicate; only the network client is faked.
  isTerminalGeneration: jest.requireActual('../src/features/generation/generationApi')
    .isTerminalGeneration,
}));

const noop = () => undefined;

const FIRST_LABEL = 'Look from 30 Aug 2026';
const SECOND_LABEL = 'Look from 31 Aug 2026';

const firstLook: LookResource = {
  id: 'look-1',
  generationId: 'generation-1',
  createdAt: '2026-08-30T10:00:00Z',
  image: {
    url: 'https://signed.example.invalid/read?X-Amz-Signature=read-1',
    expiresAt: '2026-08-30T10:05:00Z',
  },
};

const secondLook: LookResource = {
  id: 'look-2',
  generationId: 'generation-2',
  createdAt: '2026-08-31T10:00:00Z',
  image: {
    url: 'https://signed.example.invalid/read?X-Amz-Signature=read-2',
    expiresAt: '2026-08-31T10:05:00Z',
  },
};

const queuedGeneration: GenerationResource = {
  id: 'g-1',
  status: 'GENERATING',
  phase: 'GENERATING',
  statusVersion: 1,
  createdAt: '2026-08-30T10:00:00Z',
  updatedAt: '2026-08-30T10:00:00Z',
};

const completedGeneration: GenerationResource = {
  id: 'g-1',
  status: 'COMPLETED',
  phase: 'READY',
  statusVersion: 4,
  createdAt: '2026-08-30T10:00:00Z',
  updatedAt: '2026-08-30T10:03:00Z',
  lookId: 'look-9',
};

beforeEach(() => {
  jest.clearAllMocks();
  // Default: nothing in flight. Individual tests override.
  jest.mocked(generationApi.list).mockResolvedValue([]);
});
// Unmount between tests: a screen left mounted keeps the focus effect active and
// blurs the next test's screen, which is a harness artefact, not a product bug.
afterEach(cleanup);

async function renderLooksScreen() {
  await render(
    <NavigationContainer>
      <LooksScreen
        onModify={noop}
        onOpenLook={noop}
        onProfile={noop}
        pollDelay={async () => undefined}
      />
    </NavigationContainer>,
  );
}

test('shows every saved Look the API returns as a viewable image', async () => {
  jest.mocked(looksApi.list).mockResolvedValue([firstLook, secondLook]);
  await renderLooksScreen();

  for (const look of [firstLook, secondLook]) {
    const label = look.id === firstLook.id ? FIRST_LABEL : SECOND_LABEL;
    const image = await screen.findByRole('image', {
      name: `${label} preview`,
    });
    expect(image.props.source).toEqual({ uri: look.image.url });
  }
  expect(screen.queryByText('No saved Looks yet.')).toBeNull();
});

test('shows an honest empty state when no Looks are saved', async () => {
  jest.mocked(looksApi.list).mockResolvedValue([]);
  await renderLooksScreen();

  expect(await screen.findByText('No saved Looks yet.')).toBeTruthy();
  expect(screen.queryAllByRole('image')).toHaveLength(0);
});

test('no raw Look or generation id is ever rendered to the user', async () => {
  jest.mocked(looksApi.list).mockResolvedValue([firstLook, secondLook]);
  jest.mocked(looksApi.regenerate).mockResolvedValue(queuedGeneration);
  jest.mocked(generationApi.get).mockResolvedValue(completedGeneration);
  await renderLooksScreen();

  // Break the first thumbnail so the broken-preview notice renders too: it used
  // to print the raw Look id.
  const image = await screen.findByRole('image', { name: `${FIRST_LABEL} preview` });
  await fireEvent(image, 'error');
  expect(
    await screen.findByText(`Preview unavailable for ${FIRST_LABEL}.`),
  ).toBeTruthy();

  // A regeneration is recorded so the "Recent generations" row renders.
  await fireEvent.press(await screen.findByRole('button', { name: FIRST_LABEL }));
  await waitFor(() =>
    expect(
      screen.getByRole('button', { name: 'Regenerate' }).props.accessibilityState.disabled,
    ).toBe(false),
  );
  await fireEvent.press(screen.getByRole('button', { name: 'Regenerate' }));
  await waitFor(() => expect(screen.getByText(/Last regeneration: COMPLETED/)).toBeTruthy());

  // Neither identifier appears anywhere in the rendered tree.
  for (const id of [firstLook.id, secondLook.id, queuedGeneration.id, firstLook.generationId]) {
    expect(screen.queryByText(new RegExp(id))).toBeNull();
    expect(screen.queryByLabelText(new RegExp(id))).toBeNull();
  }
});

test('an expired signed preview is refreshed from the mounted Look route, not left broken', async () => {
  jest.mocked(looksApi.list).mockResolvedValue([firstLook]);
  jest.mocked(looksApi.get).mockResolvedValue({
    ...firstLook,
    image: { url: 'https://signed.example.invalid/read?X-Amz-Signature=fresh', expiresAt: '2026-08-30T10:20:00Z' },
    items: [],
    provider: null,
    providerModel: null,
    qualityStatus: null,
  });
  await renderLooksScreen();

  const image = await screen.findByRole('image', { name: `${FIRST_LABEL} preview` });
  await fireEvent(image, 'error');
  expect(await screen.findByText(`Preview unavailable for ${FIRST_LABEL}.`)).toBeTruthy();

  await fireEvent.press(screen.getByRole('button', { name: `Refresh preview for ${FIRST_LABEL}` }));

  // The fresh signed read is now the one on screen; the Look was re-read, not
  // re-created, and no share capability was involved.
  const refreshed = await screen.findByRole('image', { name: `${FIRST_LABEL} preview` });
  expect(refreshed.props.source).toEqual({
    uri: 'https://signed.example.invalid/read?X-Amz-Signature=fresh',
  });
  expect(looksApi.get).toHaveBeenCalledWith(firstLook.id);
});

test('a refresh that fails says so and keeps the honest broken-preview state', async () => {
  jest.mocked(looksApi.list).mockResolvedValue([firstLook]);
  jest.mocked(looksApi.get).mockRejectedValue(new Error('HTTP_503'));
  await renderLooksScreen();

  const image = await screen.findByRole('image', { name: `${FIRST_LABEL} preview` });
  await fireEvent(image, 'error');
  await fireEvent.press(screen.getByRole('button', { name: `Refresh preview for ${FIRST_LABEL}` }));

  expect(await screen.findByText('Could not refresh that preview. Try again later.')).toBeTruthy();
  expect(screen.queryByRole('image', { name: `${FIRST_LABEL} preview` })).toBeNull();
});

test('a regeneration accepted before a restart is resumed, never re-charged', async () => {
  jest.mocked(looksApi.list).mockResolvedValue([firstLook]);
  // The server still holds the in-flight job this process never got to poll.
  jest.mocked(generationApi.list).mockResolvedValue([queuedGeneration]);
  jest.mocked(generationApi.get).mockResolvedValue(completedGeneration);
  const onOpenLook = jest.fn();
  await render(
    <NavigationContainer>
      <LooksScreen onModify={noop} onOpenLook={onOpenLook} onProfile={noop} pollDelay={async () => undefined} />
    </NavigationContainer>,
  );

  expect(
    await screen.findByText(
      'A regeneration is already in progress. Press Regenerate to resume it without starting another.',
    ),
  ).toBeTruthy();

  await fireEvent.press(await screen.findByRole('button', { name: FIRST_LABEL }));
  await waitFor(() =>
    expect(
      screen.getByRole('button', { name: 'Regenerate' }).props.accessibilityState.disabled,
    ).toBe(false),
  );
  await fireEvent.press(screen.getByRole('button', { name: 'Regenerate' }));

  // The recovered job is polled...
  await waitFor(() => expect(generationApi.get).toHaveBeenCalledWith(queuedGeneration.id));
  // ...and NO second (chargeable) regeneration is started.
  expect(looksApi.regenerate).not.toHaveBeenCalled();
  await waitFor(() => expect(onOpenLook).toHaveBeenCalledWith(completedGeneration.lookId));
});

test('no Look share URL is ever produced or handed to the OS share sheet', async () => {
  jest.mocked(looksApi.list).mockResolvedValue([firstLook, secondLook]);
  await renderLooksScreen();

  // The affordance itself is gone: no control a user could press to share.
  expect(screen.queryByRole('button', { name: 'Share' })).toBeNull();
  expect(screen.queryByText(/share it/i)).toBeNull();

  // And the capability is unreachable, not merely hidden: acting on a selected
  // Look must never issue the share-link request that mints the presigned URL,
  // and must never hand anything to the OS share sheet. Regeneration and
  // modification are the user's real actions on a selected Look.
  jest.mocked(looksApi.regenerate).mockResolvedValue(queuedGeneration);
  jest.mocked(generationApi.get).mockResolvedValue(queuedGeneration);
  const shareSpy = jest.spyOn(Share, 'share');

  await fireEvent.press(await screen.findByRole('button', { name: FIRST_LABEL }));
  await screen.findByRole('button', { name: 'Regenerate' });
  await waitFor(() =>
    expect(screen.getByRole('button', { name: 'Modify' }).props.accessibilityState.disabled).toBe(
      false,
    ),
  );
  await fireEvent.press(screen.getByRole('button', { name: 'Modify' }));
  await fireEvent.press(screen.getByRole('button', { name: 'Regenerate' }));
  await waitFor(() => expect(looksApi.regenerate).toHaveBeenCalled());

  // No share URL is minted, and nothing reaches the share sheet.
  expect(shareSpy).not.toHaveBeenCalled();
  // The client capability itself is gone, not just unused: looksApi exposes no share method at
  // all. Asserting "not called" over a method that exists would keep the exfiltration surface
  // alive in the app and would only prove nobody pressed the button.
  expect((looksApi as unknown as Record<string, unknown>).share).toBeUndefined();
  shareSpy.mockRestore();
});

test('a poll that never settles is bounded, releases the busy lock, and shows an honest failure', async () => {
  jest.mocked(looksApi.list).mockResolvedValue([firstLook]);
  await renderLooksScreen();

  jest.mocked(looksApi.regenerate).mockResolvedValue(queuedGeneration);
  // Never terminal: the generation is still GENERATING on every single poll.
  jest.mocked(generationApi.get).mockResolvedValue(queuedGeneration);

  await fireEvent.press(await screen.findByRole('button', { name: FIRST_LABEL }));
  await screen.findByRole('button', { name: 'Regenerate' });
  await waitFor(() =>
    expect(
      screen.getByRole('button', { name: 'Regenerate' }).props.accessibilityState.disabled,
    ).toBe(false),
  );
  await fireEvent.press(screen.getByRole('button', { name: 'Regenerate' }));

  // (a) the call TERMINATES instead of looping forever. The poll is bounded, so
  // a generation that never settles is given up on rather than polled endlessly.
  await waitFor(() =>
    expect(
      screen.getByRole('button', { name: 'Resume regeneration' }).props.accessibilityState.disabled,
    ).toBe(false),
  );

  // (c) and the failure is VISIBLE and HONEST: the accepted generation is kept
  // for a resume, and the user is told the check failed rather than shown a
  // fabricated success.
  expect(
    await screen.findByText(
      'Could not check the accepted generation. Tap Resume regeneration to retry without starting another one.',
    ),
  ).toBeTruthy();
  expect(screen.queryByText(/COMPLETED/)).toBeNull();

  // (b) the busy lock was RELEASED: the previously-locked control is pressable
  // again, so the user is not locked out of every action until an app restart.
  fireEvent.press(screen.getByRole('button', { name: 'Resume regeneration' }));
  // The resume polls the SAME accepted generation: no second one is started.
  await waitFor(() => expect(jest.mocked(generationApi.get).mock.calls.length).toBeGreaterThan(0));
  expect(jest.mocked(looksApi.regenerate).mock.calls).toHaveLength(1);
// This test deliberately runs the REAL attempt bound to its end (the bound is
// ~930 polls so that it cannot expire before the server's 30-minute job
// deadline), and each poll re-renders the screen. The default 5s is therefore
// a cliff under concurrent load even though the work is bounded. The timeout is
// raised rather than the bound shortened, so the test keeps exercising the
// production default end-to-end; the bound's own invariant is pinned separately
// in boundedGenerationPoll.test.tsx.
}, 60_000);

// C23: an ended session is not a transient list failure and must not be
// described with generic navigation advice.
test('an expired session is reported as a session problem, not as a load blip', async () => {
  jest.mocked(looksApi.list).mockRejectedValue(new ApiProblemError('UNAUTHORIZED'));

  await renderLooksScreen();

  expect(
    await screen.findByText('Your session has expired. Sign in again to see your Looks.'),
  ).toBeTruthy();
  expect(screen.queryByText('Could not load Looks. You can keep using navigation.')).toBeNull();
});

test('a transient list failure keeps the existing navigation advice', async () => {
  jest.mocked(looksApi.list).mockRejectedValue(new Error('Network request failed'));

  await renderLooksScreen();

  expect(
    await screen.findByText('Could not load Looks. You can keep using navigation.'),
  ).toBeTruthy();
});
