import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { NavigationContainer } from '@react-navigation/native';
import HomeScreen from '../src/screens/HomeScreen';
import LooksScreen from '../src/screens/LooksScreen';
import StudioScreen from '../src/screens/StudioScreen';
import VaultScreen from '../src/screens/VaultScreen';
import { vaultApi } from '../src/features/vault/vaultApi';
import { collectionsApi } from '../src/features/collections/collectionsApi';
import { looksApi } from '../src/features/looks/looksApi';
import { studioApi } from '../src/features/studio/studioApi';
import { generationApi } from '../src/features/generation/generationApi';
import * as featureFlags from '../src/config/featureFlags';

jest.mock('../src/features/vault/vaultApi', () => ({
  vaultApi: {
    list: jest.fn(),
    image: jest.fn().mockResolvedValue({
      url: 'https://signed.example.invalid/item?X-Amz-Signature=read-token',
      expiresAt: '2026-09-01T00:05:00Z',
    }),
  },
}));
jest.mock('../src/features/collections/collectionsApi', () => ({ collectionsApi: { list: jest.fn() } }));
jest.mock('../src/features/looks/looksApi', () => ({ looksApi: { list: jest.fn(), regenerate: jest.fn() } }));
jest.mock('../src/features/studio/studioApi', () => ({ studioApi: { get: jest.fn().mockResolvedValue({ sessionId: 'session-1', itemIds: [] }), command: jest.fn() } }));
jest.mock('../src/features/generation/generationApi', () => ({ generationApi: { get: jest.fn(), list: jest.fn().mockResolvedValue([]) }, pollGeneration: jest.requireActual('../src/features/generation/generationApi').pollGeneration, isTerminalGeneration: jest.requireActual('../src/features/generation/generationApi').isTerminalGeneration }));

const look = { id: 'look-1', generationId: 'old-generation', createdAt: '2026-08-30T10:00:00Z', image: { url: 'https://example/look', expiresAt: '2026-08-30T10:05:00Z' } };
const noop = () => undefined;

beforeEach(() => jest.clearAllMocks());

test.each([
  ['Tops', 'TOP'], ['Bottoms', 'BOTTOM'], ['One Pieces', 'ONE_PIECE'], ['Ethnic', 'ETHNIC_FULL_BODY'],
  ['Footwear', 'FOOTWEAR'], ['Headwear', 'HEADWEAR'], ['Accessories', 'ACCESSORY'],
])('maps %s to the backend taxonomy', async (label, category) => {
  jest.mocked(vaultApi.list).mockResolvedValue([]);
  await render(<VaultScreen onProfile={noop} />);
  fireEvent.press(screen.getByRole('radio', { name: label }));
  await waitFor(() => expect(vaultApi.list).toHaveBeenLastCalledWith(category));
});

test('browses collection membership from the collection and vault sources', async () => {
  jest.mocked(vaultApi.list).mockResolvedValue([
    { id: 'item-1', category: 'TOP', name: 'Silk top' },
    { id: 'item-2', category: 'BOTTOM', name: 'Trousers' },
  ]);
  jest.mocked(collectionsApi.list).mockResolvedValue([{
    id: 'collection-1',
    name: 'Work',
    createdAt: '2026-09-01T00:00:00Z',
    updatedAt: '2026-09-01T00:00:00Z',
    items: [{ vaultItemId: 'item-2', createdAt: '2026-09-01T00:00:00Z' }],
  }]);
  await render(<VaultScreen onProfile={noop} />);
  fireEvent.press(screen.getByRole('radio', { name: 'Collections' }));
  fireEvent.press(await screen.findByRole('button', { name: 'Work' }));
  expect(await screen.findByRole('button', { name: 'Trousers' })).toBeTruthy();
  expect(screen.queryByRole('button', { name: 'Silk top' })).toBeNull();
});

test('regenerate polls fresh server state and opens the completed Look', async () => {
  jest.mocked(looksApi.list).mockResolvedValue([look]);
  jest.mocked(looksApi.regenerate).mockResolvedValue({ id: 'generation-2', status: 'QUEUED', phase: 'QUEUED', statusVersion: 1, createdAt: '', updatedAt: '' });
  jest.mocked(generationApi.get)
    .mockResolvedValueOnce({ id: 'generation-2', status: 'GENERATING', phase: 'GENERATING', statusVersion: 2, createdAt: '', updatedAt: '' })
    .mockResolvedValueOnce({ id: 'generation-2', status: 'COMPLETED', phase: 'READY', statusVersion: 3, createdAt: '', updatedAt: '', lookId: 'look-2' });
  const open = jest.fn();
  await render(
    <NavigationContainer>
      <LooksScreen onModify={noop} onOpenLook={open} onProfile={noop} pollDelay={async () => undefined} />
    </NavigationContainer>
  );
  await fireEvent.press(await screen.findByRole('button', { name: 'Look from 30 Aug 2026' }));
  await fireEvent.press(screen.getByRole('button', { name: 'Regenerate' }));
  await waitFor(() => expect(open).toHaveBeenCalledWith('look-2'));
  expect(generationApi.get).toHaveBeenCalledTimes(2);
  expect(screen.getByRole('button', { name: 'Regenerate' })).toBeEnabled();
});
test('regenerate surfaces truthful failure feedback on terminal FAILED and allows retry', async () => {
  jest.mocked(looksApi.list).mockResolvedValue([look]);
  jest.mocked(looksApi.regenerate).mockResolvedValue({
    id: 'generation-failed',
    status: 'QUEUED',
    phase: 'QUEUED',
    statusVersion: 1,
    createdAt: '',
    updatedAt: '',
  });
  jest.mocked(generationApi.get).mockResolvedValue({
    id: 'generation-failed',
    status: 'FAILED',
    phase: 'FAILED',
    statusVersion: 2,
    createdAt: '',
    updatedAt: '',
    failureCode: 'PROVIDER_ERROR',
  });
  const open = jest.fn();
  await render(
    <NavigationContainer>
      <LooksScreen onModify={noop} onOpenLook={open} onProfile={noop} pollDelay={async () => undefined} />
    </NavigationContainer>
  );
  await fireEvent.press(await screen.findByRole('button', { name: 'Look from 30 Aug 2026' }));
  await fireEvent.press(screen.getByRole('button', { name: 'Regenerate' }));

  expect(await screen.findByText(/generation failed/i)).toBeTruthy();
  expect(open).not.toHaveBeenCalled();

  expect(screen.getByRole('button', { name: 'Regenerate' })).toBeEnabled();
  await fireEvent.press(screen.getByRole('button', { name: 'Regenerate' }));
  await waitFor(() => expect(looksApi.regenerate).toHaveBeenCalledTimes(2));
});

test('regenerate handles response loss during polling and resumes without minting a second generation', async () => {
  jest.mocked(looksApi.list).mockResolvedValue([look]);
  jest.mocked(looksApi.regenerate).mockResolvedValue({
    id: 'generation-loss-1',
    status: 'QUEUED',
    phase: 'QUEUED',
    statusVersion: 1,
    createdAt: '',
    updatedAt: '',
  });
  jest.mocked(generationApi.get)
    .mockRejectedValueOnce(new Error('Network response lost'))
    .mockResolvedValueOnce({
      id: 'generation-loss-1',
      status: 'COMPLETED',
      phase: 'READY',
      statusVersion: 3,
      createdAt: '',
      updatedAt: '',
      lookId: 'look-resumed',
    });
  const open = jest.fn();
  await render(
    <NavigationContainer>
      <LooksScreen onModify={noop} onOpenLook={open} onProfile={noop} pollDelay={async () => undefined} />
    </NavigationContainer>
  );
  await fireEvent.press(await screen.findByRole('button', { name: 'Look from 30 Aug 2026' }));
  await fireEvent.press(screen.getByRole('button', { name: 'Regenerate' }));

  expect(await screen.findByText(/could not check the accepted generation/i)).toBeTruthy();
  const resumeButton = await screen.findByRole('button', { name: 'Resume regeneration' });
  expect(resumeButton).toBeEnabled();

  await fireEvent.press(resumeButton);
  await waitFor(() => expect(open).toHaveBeenCalledWith('look-resumed'));
  expect(looksApi.regenerate).toHaveBeenCalledTimes(1);
  expect(generationApi.get).toHaveBeenCalledTimes(2);
});

test('Apply command calls the Studio client with trimmed text', async () => {
  // C22 defaults NATURAL_LANGUAGE_STUDIO off; this test exercises the NL
  // command input, so enable just that capability here rather than changing
  // the shipped default.
  const flagSpy = jest
    .spyOn(featureFlags, 'isFeatureEnabled')
    .mockImplementation(name => name === 'NATURAL_LANGUAGE_STUDIO');
  try {
    jest.mocked(studioApi.command).mockResolvedValue({ sessionId: 'session-1', itemIds: [] });
    await render(<StudioScreen studioSessionId="session-1" onAddFromVault={noop} onAvatar={noop} onGenerate={noop} onProfile={noop} />);
    await fireEvent.changeText(screen.getByLabelText('Natural-language command'), '  remove the hat  ');
    await fireEvent.press(screen.getByRole('button', { name: 'Apply command' }));
    await waitFor(() => expect(studioApi.command).toHaveBeenCalledWith('session-1', 'remove the hat'));
    expect(screen.getByLabelText('Natural-language command').props.value).toBe('');
  } finally {
    flagSpy.mockRestore();
  }
});

test('Home loads recent Looks and opens the selected Look', async () => {
  jest.mocked(looksApi.list).mockResolvedValue([look]);
  const open = jest.fn();
  await render(
    <NavigationContainer>
      <HomeScreen onImport={noop} onOpenLook={open} onProfile={noop} onQuickTry={noop} />
    </NavigationContainer>
  );
  fireEvent.press(await screen.findByRole('button', { name: 'Look from 30 Aug 2026' }));
  expect(open).toHaveBeenCalledWith('look-1');
  expect(screen.queryByText('No recent Looks yet.')).toBeNull();
});
