import { fireEvent, render, screen, waitFor, within } from '@testing-library/react-native';
import StudioScreen from '../src/screens/StudioScreen';
import { studioApi } from '../src/features/studio/studioApi';
import { vaultApi } from '../src/features/vault/vaultApi';
import { avatarApi } from '../src/features/my-model/avatarApi';
import { pollGeneration } from '../src/features/generation/generationApi';
import type { GenerationResource } from '../src/features/generation/types';
import * as featureFlags from '../src/config/featureFlags';

jest.mock('../src/features/studio/studioApi', () => ({
  ...jest.requireActual('../src/features/studio/studioApi'),
  studioApi: {
    get: jest.fn(),
    activeDraft: jest.fn(),
    create: jest.fn(),
    createFromLook: jest.fn(),
    addItem: jest.fn(),
    removeItem: jest.fn(),
    replaceItem: jest.fn(),
    command: jest.fn(),
    generate: jest.fn(),
  },
}));
jest.mock('../src/features/vault/vaultApi', () => ({ vaultApi: { list: jest.fn(), image: jest.fn() } }));
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
  jest.mocked(vaultApi.image).mockImplementation(async (itemId: string) => ({ url: `https://cdn/${itemId}.jpg`, expiresAt: '2026-10-03T00:05:00Z' }));
  jest.mocked(avatarApi.list).mockResolvedValue([]);
  // C22 ships NATURAL_LANGUAGE_STUDIO off; these cases exercise that surface,
  // so enable just that capability rather than changing the shipped default.
  jest.spyOn(featureFlags, 'isFeatureEnabled').mockImplementation(name => name === 'NATURAL_LANGUAGE_STUDIO');
});

test('renders the owned outfit as garment names and images, never raw item ids', async () => {
  jest.mocked(studioApi.get).mockResolvedValue({
    sessionId: 'session-1',
    avatarProfileId: 'avatar-1',
    updatedAt: '2026-10-03T00:00:00Z',
    itemIds: ['item-top', 'item-bottom', 'item-footwear'],
  });
  jest.mocked(vaultApi.list).mockResolvedValue([
    { id: 'item-top', category: 'TOP', name: 'Silk top' },
    { id: 'item-bottom', category: 'BOTTOM', name: 'Trousers' },
    { id: 'item-footwear', category: 'FOOTWEAR', name: 'Sneakers' },
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
  expect(vaultApi.image).toHaveBeenCalledWith('item-top');
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

test('a source Look clones into a draft and reports unresolved source garments explicitly', async () => {
  jest.mocked(studioApi.createFromLook).mockResolvedValue({
    sessionId: 'cloned-1',
    avatarProfileId: 'avatar-1',
    updatedAt: '2026-10-03T00:00:00Z',
    itemIds: ['item-top'],
    unresolved: [{ vaultItemId: 'gone-bottom', reason: 'GARMENT_DELETED' }],
    avatarUnavailable: false,
  });
  jest.mocked(vaultApi.list).mockResolvedValue([
    { id: 'item-top', category: 'TOP', name: 'Silk top' },
  ] as never);
  jest.mocked(avatarApi.list).mockResolvedValue([] as never);

  await render(
    <StudioScreen sourceLookId="look-1" onAddFromVault={noop} onAvatar={noop} onOpenLook={noop} onProfile={noop} />,
  );

  await waitFor(() => expect(studioApi.createFromLook).toHaveBeenCalledWith('look-1'));
  expect(studioApi.activeDraft).not.toHaveBeenCalled();
  expect(studioApi.create).not.toHaveBeenCalled();
  await waitFor(() => expect(screen.getByTestId('studio-unresolved')).toHaveTextContent(/1 source garment\(s\)/));
  expect(within(screen.getByTestId('slot-UPPER_BODY')).getByTestId('item-item-top')).toHaveTextContent('Silk top');
});

test('a source Look with no avatar says so instead of silently generating without one', async () => {
  jest.mocked(studioApi.createFromLook).mockResolvedValue({
    sessionId: 'cloned-2',
    updatedAt: '2026-10-03T00:00:00Z',
    itemIds: ['item-top'],
    unresolved: [],
    avatarUnavailable: true,
  });
  jest.mocked(vaultApi.list).mockResolvedValue([{ id: 'item-top', category: 'TOP', name: 'Silk top' }] as never);

  await render(
    <StudioScreen sourceLookId="look-2" onAddFromVault={noop} onAvatar={noop} onOpenLook={noop} onProfile={noop} />,
  );

  await waitFor(() => expect(screen.getByTestId('studio-notice')).toHaveTextContent(/no My Model/));
});

test('a source Look that cannot be loaded reports it without falling back to an unrelated draft', async () => {
  jest.mocked(studioApi.createFromLook).mockRejectedValue(new Error('404'));

  await render(
    <StudioScreen sourceLookId="foreign-look" onAddFromVault={noop} onAvatar={noop} onOpenLook={noop} onProfile={noop} />,
  );

  await waitFor(() => expect(screen.getByTestId('studio-notice')).toHaveTextContent(/Could not load that Look/));
  expect(studioApi.activeDraft).not.toHaveBeenCalled();
  expect(studioApi.create).not.toHaveBeenCalled();
  expect(screen.queryByTestId('studio-summary')).toBeNull();
});

test('an expired garment preview refreshes once, then falls back to the name', async () => {
  jest.mocked(studioApi.get).mockResolvedValue({ sessionId: 'session-1', updatedAt: '2026-10-03T00:00:00Z', itemIds: ['item-top'] });
  jest.mocked(vaultApi.list).mockResolvedValue([{ id: 'item-top', category: 'TOP', name: 'Silk top' }] as never);
  jest.mocked(vaultApi.image)
    .mockResolvedValueOnce({ url: 'https://cdn/stale.jpg', expiresAt: '2026-10-03T00:05:00Z' })
    .mockResolvedValueOnce({ url: 'https://cdn/fresh.jpg', expiresAt: '2026-10-03T00:10:00Z' })
    .mockRejectedValueOnce(new Error('expired'));

  await render(
    <StudioScreen studioSessionId="session-1" onAddFromVault={noop} onAvatar={noop} onOpenLook={noop} onProfile={noop} />,
  );

  await waitFor(() => expect(screen.getByTestId('item-image-item-top')).toBeTruthy());
  fireEvent(screen.getByTestId('item-image-item-top'), 'error');
  await waitFor(() => expect(vaultApi.image).toHaveBeenCalledTimes(2));

  // A second failure drops the capability instead of leaving a broken preview,
  // and the garment is still named and removable in its slot.
  fireEvent(screen.getByTestId('item-image-item-top'), 'error');
  await waitFor(() => expect(screen.queryByTestId('item-image-item-top')).toBeNull());
  expect(within(screen.getByTestId('slot-UPPER_BODY')).getByTestId('item-item-top')).toHaveTextContent('Silk top');
  expect(screen.getByRole('button', { name: 'Remove Silk top' })).toBeTruthy();
});

test('a consumed Vault selection is reported once and not re-applied after removal', async () => {
  jest.mocked(studioApi.get).mockResolvedValue({ sessionId: 'session-1', updatedAt: '2026-10-03T00:00:00Z', itemIds: [] });
  jest.mocked(vaultApi.list).mockResolvedValue([{ id: 'item-top', category: 'TOP', name: 'Silk top' }] as never);
  jest.mocked(studioApi.addItem).mockResolvedValue({ sessionId: 'session-1', updatedAt: '2026-10-03T00:01:00Z', itemIds: ['item-top'] });
  const onConsumed = jest.fn();

  const { rerender } = await render(
    <StudioScreen studioSessionId="session-1" selectedVaultItemId="item-top" onAddFromVault={noop} onAvatar={noop} onOpenLook={noop} onProfile={noop} onConsumed={onConsumed} />,
  );

  await waitFor(() => expect(onConsumed).toHaveBeenCalledTimes(1));

  // The navigator clears the param once consumed; a re-render must not re-apply it.
  rerender(
    <StudioScreen studioSessionId="session-1" onAddFromVault={noop} onAvatar={noop} onOpenLook={noop} onProfile={noop} onConsumed={onConsumed} />,
  );
  await waitFor(() => expect(screen.getByTestId('item-item-top')).toBeTruthy());
  expect(onConsumed).toHaveBeenCalledTimes(1);
  expect(studioApi.addItem).toHaveBeenCalledTimes(1);
});

test('a consumed source Look is reported once so re-entering Studio does not clone it again', async () => {
  jest.mocked(studioApi.createFromLook).mockResolvedValue({
    sessionId: 'cloned-1',
    updatedAt: '2026-10-03T00:00:00Z',
    itemIds: [],
    unresolved: [],
    avatarUnavailable: false,
  });
  const onConsumed = jest.fn();

  const { rerender } = await render(
    <StudioScreen sourceLookId="look-1" onAddFromVault={noop} onAvatar={noop} onOpenLook={noop} onProfile={noop} onConsumed={onConsumed} />,
  );

  await waitFor(() => expect(onConsumed).toHaveBeenCalledTimes(1));
  rerender(
    <StudioScreen onAddFromVault={noop} onAvatar={noop} onOpenLook={noop} onProfile={noop} onConsumed={onConsumed} />,
  );
  await waitFor(() => expect(studioApi.activeDraft).toHaveBeenCalled());
  expect(studioApi.createFromLook).toHaveBeenCalledTimes(1);
});

test('a source Look that cannot be loaded still reports the spent param', async () => {
  jest.mocked(studioApi.createFromLook).mockRejectedValue(new Error('404'));
  const onConsumed = jest.fn();

  await render(
    <StudioScreen sourceLookId="foreign-look" onAddFromVault={noop} onAvatar={noop} onOpenLook={noop} onProfile={noop} onConsumed={onConsumed} />,
  );

  await waitFor(() => expect(onConsumed).toHaveBeenCalledTimes(1));
});

test('an ambiguous command shows the server prompt and the owned candidates, and confirming re-sends the structured command', async () => {
  jest.mocked(studioApi.get).mockResolvedValue({ sessionId: 'session-1', updatedAt: '2026-10-03T00:00:00Z', itemIds: [] });
  jest.mocked(vaultApi.list).mockResolvedValue([] as never);
  jest.mocked(studioApi.command).mockResolvedValue({
    state: { sessionId: 'session-1', updatedAt: '2026-10-03T00:00:00Z', itemIds: [] },
    outcome: 'CONFIRM_REQUIRED',
    message: 'Which top did you mean?',
    candidates: [
      { vaultItemId: 'item-top', label: 'Silk top', slot: 'UPPER_BODY' },
      { vaultItemId: 'item-top-2', label: 'Linen top', slot: 'UPPER_BODY' },
    ],
  });
  jest.mocked(studioApi.addItem).mockResolvedValue({ sessionId: 'session-1', updatedAt: '2026-10-03T00:01:00Z', itemIds: ['item-top'] });

  await render(
    <StudioScreen studioSessionId="session-1" onAddFromVault={noop} onAvatar={noop} onOpenLook={noop} onProfile={noop} />,
  );

  await fireEvent.changeText(await screen.findByLabelText('Natural-language command'), 'add a top');
  await fireEvent.press(screen.getByRole('button', { name: 'Apply command' }));

  // Nothing was mutated, so the draft is untouched and the typed command is kept.
  await waitFor(() => expect(screen.getByText('Which top did you mean?')).toBeTruthy());
  expect(screen.getByLabelText('Natural-language command').props.value).toBe('add a top');
  expect(screen.queryByTestId('item-item-top')).toBeNull();

  await fireEvent.press(screen.getByRole('button', { name: 'Linen top' }));

  // Confirmation uses the structured manual command with the planner's own slot.
  await waitFor(() => expect(studioApi.addItem).toHaveBeenCalledWith('session-1', 'item-top-2', 'UPPER_BODY', '2026-10-03T00:00:00Z'));
  expect(studioApi.command).toHaveBeenCalledTimes(1);
});

test('an explained command shows the server answer and leaves the draft and the typed text intact', async () => {
  jest.mocked(studioApi.get).mockResolvedValue({ sessionId: 'session-1', updatedAt: '2026-10-03T00:00:00Z', itemIds: ['item-top'] });
  jest.mocked(vaultApi.list).mockResolvedValue([{ id: 'item-top', category: 'TOP', name: 'Silk top' }] as never);
  jest.mocked(studioApi.command).mockResolvedValue({
    state: { sessionId: 'session-1', updatedAt: '2026-10-03T00:00:00Z', itemIds: ['item-top'] },
    outcome: 'EXPLAINED',
    message: 'A full-body garment cannot be combined with a separate top.',
    candidates: [],
  });

  await render(
    <StudioScreen studioSessionId="session-1" onAddFromVault={noop} onAvatar={noop} onOpenLook={noop} onProfile={noop} />,
  );

  await fireEvent.changeText(await screen.findByLabelText('Natural-language command'), 'add a saree');
  await fireEvent.press(screen.getByRole('button', { name: 'Apply command' }));

  await waitFor(() => expect(screen.getByText('A full-body garment cannot be combined with a separate top.')).toBeTruthy());
  expect(screen.getByLabelText('Natural-language command').props.value).toBe('add a saree');
  expect(within(screen.getByTestId('slot-UPPER_BODY')).getByTestId('item-item-top')).toHaveTextContent('Silk top');
  expect(studioApi.addItem).not.toHaveBeenCalled();
});

test('a refused command says so and does not claim a change', async () => {
  jest.mocked(studioApi.get).mockResolvedValue({ sessionId: 'session-1', updatedAt: '2026-10-03T00:00:00Z', itemIds: [] });
  jest.mocked(vaultApi.list).mockResolvedValue([] as never);
  jest.mocked(studioApi.command).mockResolvedValue({
    state: { sessionId: 'session-1', updatedAt: '2026-10-03T00:00:00Z', itemIds: [] },
    outcome: 'REFUSED',
    message: 'That is not something I can change.',
    candidates: [],
  });

  await render(
    <StudioScreen studioSessionId="session-1" onAddFromVault={noop} onAvatar={noop} onOpenLook={noop} onProfile={noop} />,
  );

  await fireEvent.changeText(await screen.findByLabelText('Natural-language command'), 'delete my account');
  await fireEvent.press(screen.getByRole('button', { name: 'Apply command' }));

  await waitFor(() => expect(screen.getByText('That is not something I can change.')).toBeTruthy());
  expect(screen.getByTestId('studio-summary')).toHaveTextContent(/Your Studio outfit is empty/);
  expect(screen.getByLabelText('Natural-language command').props.value).toBe('delete my account');
});

test('an applied command replaces the draft and clears the input', async () => {
  jest.mocked(studioApi.get).mockResolvedValue({ sessionId: 'session-1', updatedAt: '2026-10-03T00:00:00Z', itemIds: [] });
  jest.mocked(vaultApi.list).mockResolvedValue([{ id: 'item-top', category: 'TOP', name: 'Silk top' }] as never);
  jest.mocked(studioApi.command).mockResolvedValue({
    state: { sessionId: 'session-1', updatedAt: '2026-10-03T00:01:00Z', itemIds: ['item-top'] },
    outcome: 'APPLIED',
    message: null,
    candidates: [],
  });

  await render(
    <StudioScreen studioSessionId="session-1" onAddFromVault={noop} onAvatar={noop} onOpenLook={noop} onProfile={noop} />,
  );

  await fireEvent.changeText(await screen.findByLabelText('Natural-language command'), 'add the silk top');
  await fireEvent.press(screen.getByRole('button', { name: 'Apply command' }));

  await waitFor(() => expect(within(screen.getByTestId('slot-UPPER_BODY')).getByTestId('item-item-top')).toHaveTextContent('Silk top'));
  expect(screen.getByLabelText('Natural-language command').props.value).toBe('');
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
