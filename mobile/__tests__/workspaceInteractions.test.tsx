import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import HomeScreen from '../src/screens/HomeScreen';
import LooksScreen from '../src/screens/LooksScreen';
import StudioScreen from '../src/screens/StudioScreen';
import VaultScreen from '../src/screens/VaultScreen';
import { vaultApi } from '../src/features/vault/vaultApi';
import { collectionApi } from '../src/features/vault/collectionApi';
import { looksApi } from '../src/features/looks/looksApi';
import { studioApi } from '../src/features/studio/studioApi';
import { generationApi } from '../src/features/generation/generationApi';

jest.mock('../src/features/vault/vaultApi', () => ({ vaultApi: { list: jest.fn() } }));
jest.mock('../src/features/vault/collectionApi', () => ({ collectionApi: { list: jest.fn() } }));
jest.mock('../src/features/looks/looksApi', () => ({ looksApi: { list: jest.fn(), regenerate: jest.fn(), share: jest.fn() } }));
jest.mock('../src/features/studio/studioApi', () => ({ studioApi: { command: jest.fn() } }));
jest.mock('../src/features/generation/generationApi', () => ({ generationApi: { get: jest.fn() }, pollGeneration: jest.requireActual('../src/features/generation/generationApi').pollGeneration }));

const look = { id: 'look-1', generationId: 'old-generation', createdAt: '2026-08-30T10:00:00Z', image: { url: 'https://example/look', expiresAt: '2026-08-30T10:05:00Z' } };
const noop = () => undefined;

beforeEach(() => jest.clearAllMocks());

test.each([
  ['Tops', 'TOP'], ['Bottoms', 'BOTTOM'], ['One Pieces', 'ONE_PIECE'], ['Ethnic', 'ETHNIC_FULL_BODY'],
  ['Footwear', 'FOOTWEAR'], ['Headwear', 'HEADWEAR'], ['Accessories', 'ACCESSORIES'],
])('maps %s to the backend taxonomy', async (label, category) => {
  jest.mocked(vaultApi.list).mockResolvedValue([]);
  await render(<VaultScreen onProfile={noop} />);
  fireEvent.press(screen.getByRole('button', { name: label }));
  await waitFor(() => expect(vaultApi.list).toHaveBeenLastCalledWith(category));
});

test('browses collection membership from the collection and vault sources', async () => {
  jest.mocked(vaultApi.list).mockResolvedValue([
    { id: 'item-1', category: 'TOP', name: 'Silk top' },
    { id: 'item-2', category: 'BOTTOM', name: 'Trousers' },
  ]);
  jest.mocked(collectionApi.list).mockResolvedValue([{ id: 'collection-1', name: 'Work', vaultItemIds: ['item-2'] }]);
  await render(<VaultScreen onProfile={noop} />);
  fireEvent.press(screen.getByRole('button', { name: 'Collections' }));
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
  await render(<LooksScreen onModify={noop} onOpenLook={open} onProfile={noop} pollDelay={async () => undefined} />);
  await fireEvent.press(await screen.findByRole('button', { name: 'Look look-1' }));
  await fireEvent.press(screen.getByRole('button', { name: 'Regenerate' }));
  await waitFor(() => expect(open).toHaveBeenCalledWith('look-2'));
  expect(generationApi.get).toHaveBeenCalledTimes(2);
  expect(screen.getByRole('button', { name: 'Regenerate' })).toBeEnabled();
});

test('Apply command calls the Studio client with trimmed text', async () => {
  jest.mocked(studioApi.command).mockResolvedValue({ sessionId: 'session-1', itemIds: [] });
  await render(<StudioScreen studioSessionId="session-1" onAddFromVault={noop} onAvatar={noop} onGenerate={noop} onProfile={noop} />);
  await fireEvent.changeText(screen.getByLabelText('Natural-language command'), '  remove the hat  ');
  await fireEvent.press(screen.getByRole('button', { name: 'Apply command' }));
  await waitFor(() => expect(studioApi.command).toHaveBeenCalledWith('session-1', 'remove the hat'));
  expect(screen.getByLabelText('Natural-language command').props.value).toBe('');
});

test('Home loads recent Looks and opens the selected Look', async () => {
  jest.mocked(looksApi.list).mockResolvedValue([look]);
  const open = jest.fn();
  await render(<HomeScreen onImport={noop} onOpenLook={open} onProfile={noop} onQuickTry={noop} />);
  fireEvent.press(await screen.findByRole('button', { name: 'Recent Look look-1' }));
  expect(open).toHaveBeenCalledWith('look-1');
  expect(screen.queryByText('No recent Looks yet.')).toBeNull();
});
