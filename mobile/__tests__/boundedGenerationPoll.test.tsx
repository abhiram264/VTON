import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { NavigationContainer } from '@react-navigation/native';
import LooksScreen from '../src/screens/LooksScreen';
import { looksApi } from '../src/features/looks/looksApi';
import { generationApi } from '../src/features/generation/generationApi';
import type { GenerationResource, LookResource } from '../src/features/generation/types';

jest.mock('../src/features/looks/looksApi', () => ({
  looksApi: { list: jest.fn(), regenerate: jest.fn(), share: jest.fn() },
}));

jest.mock('../src/features/generation/generationApi', () => ({
  generationApi: { get: jest.fn() },
  // The real poller: the bound under test lives in it, not in a double.
  pollGeneration: jest.requireActual('../src/features/generation/generationApi')
    .pollGeneration,
}));

const noop = () => undefined;
// Yields to the macrotask queue between polls so a runaway poll fails the
// test on its own timeout instead of starving the event loop.
const yieldingDelay = (ms: number) =>
  new Promise<void>(resolve => setTimeout(resolve, 0));


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

test('a generation that never settles ends in a visible error and releases the busy lock', async () => {
  jest.mocked(looksApi.list).mockResolvedValue([look]);
  jest.mocked(looksApi.regenerate).mockResolvedValue(stuck);
  jest.mocked(generationApi.get).mockResolvedValue(stuck);
  const open = jest.fn();

  await render(
    <NavigationContainer>
      <LooksScreen
        onModify={noop}
        onOpenLook={open}
        onProfile={noop}
        pollDelay={yieldingDelay}
      />
    </NavigationContainer>,
  );
  await fireEvent.press(await screen.findByRole('button', { name: 'Look look-1' }));
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
});
