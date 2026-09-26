import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { NavigationContainer } from '@react-navigation/native';
import LooksScreen from '../src/screens/LooksScreen';
import { looksApi } from '../src/features/looks/looksApi';
import type { GenerationResource, LookResource } from '../src/features/generation/types';
import { generationApi } from '../src/features/generation/generationApi';
import { Share } from 'react-native';
jest.mock('../src/features/looks/looksApi', () => ({
  looksApi: { list: jest.fn(), regenerate: jest.fn() },
}));
// `pollGeneration` stays real so the bound under test is the real one; only the
// network client is faked, and it is faked per test by clearAllMocks.
jest.mock('../src/features/generation/generationApi', () => ({
  generationApi: { get: jest.fn() },
  pollGeneration: jest.requireActual('../src/features/generation/generationApi').pollGeneration,
}));

const noop = () => undefined;

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

beforeEach(() => jest.clearAllMocks());
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
    const image = await screen.findByRole('image', {
      name: `Look image ${look.id}`,
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

  await fireEvent.press(await screen.findByRole('button', { name: `Look ${firstLook.id}` }));
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

  await fireEvent.press(await screen.findByRole('button', { name: `Look ${firstLook.id}` }));
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
