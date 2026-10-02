import { fireEvent, render, screen, waitFor, within } from '@testing-library/react-native';
import StudioScreen from '../src/screens/StudioScreen';
import { studioApi } from '../src/features/studio/studioApi';
import { vaultApi } from '../src/features/vault/vaultApi';
import { avatarApi } from '../src/features/my-model/avatarApi';
import { pollGeneration } from '../src/features/generation/generationApi';
import type { GenerationResource } from '../src/features/generation/types';

jest.mock('../src/features/studio/studioApi', () => ({
  ...jest.requireActual('../src/features/studio/studioApi'),
  studioApi: {
    get: jest.fn(),
    activeDraft: jest.fn(),
    create: jest.fn(),
    addItem: jest.fn(),
    removeItem: jest.fn(),
    replaceItem: jest.fn(),
    command: jest.fn(),
    generate: jest.fn(),
  },
}));
jest.mock('../src/features/vault/vaultApi', () => ({ vaultApi: { list: jest.fn() } }));
jest.mock('../src/features/my-model/avatarApi', () => ({ avatarApi: { list: jest.fn() } }));
jest.mock('../src/features/generation/generationApi', () => ({
  generationApi: { get: jest.fn() },
  pollGeneration: jest.fn(),
}));

const noop = () => undefined;

const acceptedGeneration: GenerationResource = {
  id: 'gen-1',
  status: 'SUBMITTED',
  phase: 'GENERATING',
  statusVersion: 1,
  createdAt: '2026-10-03T00:00:00Z',
  updatedAt: '2026-10-03T00:00:00Z',
};

beforeEach(() => {
  jest.clearAllMocks();
  jest.mocked(vaultApi.list).mockResolvedValue([]);
  jest.mocked(avatarApi.list).mockResolvedValue([]);
});

test('renders the owned outfit as garment names and images, never raw item ids', async () => {
  jest.mocked(studioApi.get).mockResolvedValue({
    sessionId: 'session-1',
    avatarProfileId: 'avatar-1',
    updatedAt: '2026-10-03T00:00:00Z',
    itemIds: ['item-top', 'item-bottom', 'item-footwear'],
  });
  jest.mocked(vaultApi.list).mockResolvedValue([
    { id: 'item-top', category: 'TOP', name: 'Silk top', displayUrl: 'https://cdn/top.jpg' },
    { id: 'item-bottom', category: 'BOTTOM', name: 'Trousers', displayUrl: 'https://cdn/bottom.jpg' },
    { id: 'item-footwear', category: 'FOOTWEAR', name: 'Sneakers', displayUrl: 'https://cdn/shoes.jpg' },
  ] as never);
  jest.mocked(avatarApi.list).mockResolvedValue([
    { id: 'avatar-1', displayUrl: 'https://cdn/avatar.jpg' },
  ] as never);

  await render(
    <StudioScreen
      studioSessionId="session-1"
      onAddFromVault={noop}
      onAvatar={noop}
      onOpenLook={noop}
      onProfile={noop}
    />,
  );

  await waitFor(() => expect(within(screen.getByTestId('slot-UPPER_BODY')).getByTestId('item-item-top')).toHaveTextContent('Silk top'));
  expect(within(screen.getByTestId('slot-LOWER_BODY')).getByTestId('item-item-bottom')).toHaveTextContent('Trousers');
  expect(within(screen.getByTestId('slot-FEET')).getByTestId('item-item-footwear')).toHaveTextContent('Sneakers');
  expect(within(screen.getByTestId('slot-UPPER_BODY')).queryByTestId('item-item-bottom')).toBeNull();
  expect(screen.getByTestId('item-image-item-top')).toBeTruthy();
  expect(screen.getByTestId('studio-avatar')).toBeTruthy();
  // The internal item identifier must never reach the rendered surface.
  expect(screen.queryByText('item-top')).toBeNull();
  expect(screen.getByRole('button', { name: 'Generate' })).toBeEnabled();
});

test("loads the owner's active draft when no session id is supplied", async () => {
  jest.mocked(studioApi.activeDraft).mockResolvedValue({ sessionId: 'draft-1', updatedAt: '2026-10-03T00:00:00Z', itemIds: [] });

  await render(
    <StudioScreen onAddFromVault={noop} onAvatar={noop} onOpenLook={noop} onProfile={noop} />,
  );

  await waitFor(() => expect(screen.getByTestId('studio-summary')).toHaveTextContent(/Your Studio outfit is empty/));
  expect(studioApi.activeDraft).toHaveBeenCalledTimes(1);
  expect(studioApi.create).not.toHaveBeenCalled();
  expect(studioApi.get).not.toHaveBeenCalled();
});

test('creates an owned draft when the owner has none yet', async () => {
  jest.mocked(studioApi.activeDraft).mockResolvedValue(null);
  jest.mocked(studioApi.create).mockResolvedValue({ sessionId: 'new-1', updatedAt: '2026-10-03T00:00:00Z', itemIds: [] });

  await render(
    <StudioScreen onAddFromVault={noop} onAvatar={noop} onOpenLook={noop} onProfile={noop} />,
  );

  await waitFor(() => expect(studioApi.create).toHaveBeenCalledTimes(1));
  expect(screen.getByTestId('studio-summary')).toHaveTextContent(/Your Studio outfit is empty/);
});

test('removing a garment calls the owned command with the session revision token', async () => {
  jest.mocked(studioApi.get).mockResolvedValue({ sessionId: 'session-1', updatedAt: '2026-10-03T00:00:00Z', itemIds: ['item-top'] });
  jest.mocked(vaultApi.list).mockResolvedValue([
    { id: 'item-top', category: 'TOP', name: 'Silk top' },
  ] as never);
  jest.mocked(studioApi.removeItem).mockResolvedValue({ sessionId: 'session-1', updatedAt: '2026-10-03T00:01:00Z', itemIds: [] });

  await render(
    <StudioScreen studioSessionId="session-1" onAddFromVault={noop} onAvatar={noop} onOpenLook={noop} onProfile={noop} />,
  );

  await waitFor(() => expect(screen.getByRole('button', { name: 'Remove Silk top' })).toBeTruthy());
  fireEvent.press(screen.getByRole('button', { name: 'Remove Silk top' }));

  await waitFor(() => expect(studioApi.removeItem).toHaveBeenCalledWith('session-1', 'item-top', '2026-10-03T00:00:00Z'));
  await waitFor(() => expect(screen.queryByTestId('item-item-top')).toBeNull());
});

test('a Vault selection into an occupied slot replaces the item instead of adding a second one', async () => {
  jest.mocked(studioApi.get).mockResolvedValue({ sessionId: 'session-1', updatedAt: '2026-10-03T00:00:00Z', itemIds: ['item-top'] });
  jest.mocked(vaultApi.list).mockResolvedValue([
    { id: 'item-top', category: 'TOP', name: 'Silk top' },
    { id: 'item-top-2', category: 'TOP', name: 'Linen top' },
  ] as never);
  jest.mocked(studioApi.replaceItem).mockResolvedValue({ sessionId: 'session-1', updatedAt: '2026-10-03T00:01:00Z', itemIds: ['item-top-2'] });

  await render(
    <StudioScreen studioSessionId="session-1" selectedVaultItemId="item-top-2" onAddFromVault={noop} onAvatar={noop} onOpenLook={noop} onProfile={noop} />,
  );

  await waitFor(() => expect(studioApi.replaceItem).toHaveBeenCalledWith('session-1', 'item-top-2', 'UPPER_BODY', '2026-10-03T00:00:00Z'));
  expect(studioApi.addItem).not.toHaveBeenCalled();
});

test('a Vault selection into an empty slot adds the item', async () => {
  jest.mocked(studioApi.get).mockResolvedValue({ sessionId: 'session-2', updatedAt: '2026-10-03T00:00:00Z', itemIds: [] });
  jest.mocked(vaultApi.list).mockResolvedValue([
    { id: 'item-top', category: 'TOP', name: 'Silk top' },
  ] as never);
  jest.mocked(studioApi.addItem).mockResolvedValue({ sessionId: 'session-2', updatedAt: '2026-10-03T00:01:00Z', itemIds: ['item-top'] });

  await render(
    <StudioScreen studioSessionId="session-2" selectedVaultItemId="item-top" onAddFromVault={noop} onAvatar={noop} onOpenLook={noop} onProfile={noop} />,
  );

  await waitFor(() => expect(studioApi.addItem).toHaveBeenCalledWith('session-2', 'item-top', 'UPPER_BODY', '2026-10-03T00:00:00Z'));
  expect(studioApi.replaceItem).not.toHaveBeenCalled();
});

test('Generate freezes the owned session item order and opens the completed Look', async () => {
  jest.mocked(studioApi.get).mockResolvedValue({ sessionId: 'session-1', updatedAt: '2026-10-03T00:00:00Z', itemIds: ['item-top', 'item-bottom', 'item-footwear'] });
  jest.mocked(vaultApi.list).mockResolvedValue([
    { id: 'item-top', category: 'TOP', name: 'Silk top' },
    { id: 'item-bottom', category: 'BOTTOM', name: 'Trousers' },
    { id: 'item-footwear', category: 'FOOTWEAR', name: 'Sneakers' },
  ] as never);
  jest.mocked(studioApi.generate).mockResolvedValue(acceptedGeneration);
  jest.mocked(pollGeneration).mockResolvedValue({
    ...acceptedGeneration,
    status: 'COMPLETED',
    phase: 'READY',
    lookId: 'look-9',
  });
  const onOpenLook = jest.fn();

  await render(
    <StudioScreen studioSessionId="session-1" onAddFromVault={noop} onAvatar={noop} onOpenLook={onOpenLook} onProfile={noop} />,
  );

  await waitFor(() => expect(screen.getByRole('button', { name: 'Generate' })).toBeEnabled());
  fireEvent.press(screen.getByRole('button', { name: 'Generate' }));

  await waitFor(() => expect(onOpenLook).toHaveBeenCalledWith('look-9'));
  expect(studioApi.generate).toHaveBeenCalledWith('session-1', ['item-top', 'item-bottom', 'item-footwear'], 'standard');
});

test('Generate reports a failed generation honestly and opens no Look', async () => {
  jest.mocked(studioApi.get).mockResolvedValue({ sessionId: 'session-1', updatedAt: '2026-10-03T00:00:00Z', itemIds: ['item-top'] });
  jest.mocked(vaultApi.list).mockResolvedValue([{ id: 'item-top', category: 'TOP', name: 'Silk top' }] as never);
  jest.mocked(studioApi.generate).mockResolvedValue(acceptedGeneration);
  jest.mocked(pollGeneration).mockResolvedValue({
    ...acceptedGeneration,
    status: 'FAILED',
    phase: 'FAILED',
    failureCode: 'PROVIDER_ERROR',
  });
  const onOpenLook = jest.fn();

  await render(
    <StudioScreen studioSessionId="session-1" onAddFromVault={noop} onAvatar={noop} onOpenLook={onOpenLook} onProfile={noop} />,
  );

  await waitFor(() => expect(screen.getByRole('button', { name: 'Generate' })).toBeEnabled());
  fireEvent.press(screen.getByRole('button', { name: 'Generate' }));

  await waitFor(() => expect(screen.getByText(/Generation failed \(PROVIDER_ERROR\)/)).toBeTruthy());
  expect(onOpenLook).not.toHaveBeenCalled();
});
