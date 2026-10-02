import { act, fireEvent, render, screen } from '@testing-library/react-native';
import VaultScreen from '../src/screens/VaultScreen';
import { collectionsApi } from '../src/features/collections/collectionsApi';
import { vaultApi } from '../src/features/vault/vaultApi';

jest.mock('../src/features/vault/vaultApi', () => ({
  vaultApi: {
    list: jest.fn(),
    get: jest.fn(),
    update: jest.fn(),
    remove: jest.fn(),
    image: jest.fn(),
    createFromPhoto: jest.fn(),
  },
}));
jest.mock('../src/features/collections/collectionsApi', () => ({
  collectionsApi: {
    list: jest.fn(),
    create: jest.fn(),
    rename: jest.fn(),
    remove: jest.fn(),
    addItem: jest.fn(),
    removeItem: jest.fn(),
  },
}));

const noop = () => undefined;

const COLLECTION = {
  id: 'collection-1',
  name: 'Work',
  createdAt: '2026-09-01T00:00:00Z',
  updatedAt: '2026-09-01T00:00:00Z',
  items: [{ vaultItemId: 'item-2', createdAt: '2026-09-01T00:00:00Z' }],
};

async function interact(work: () => void) {
  await act(async () => {
    work();
    await Promise.resolve();
    await Promise.resolve();
  });
}

async function mount(element: React.ReactElement) {
  await act(async () => {
    render(element);
    await Promise.resolve();
    await Promise.resolve();
  });
}

beforeEach(() => {
  jest.clearAllMocks();
  jest.mocked(vaultApi.list).mockResolvedValue([
    { id: 'item-1', category: 'TOP', name: 'Silk top' },
    { id: 'item-2', category: 'BOTTOM', name: 'Trousers' },
  ]);
  jest.mocked(collectionsApi.list).mockResolvedValue([COLLECTION]);
});

describe('VaultScreen collections', () => {
  test('filters items by collection membership read from the collection items', async () => {
    await mount(<VaultScreen onProfile={noop} />);

    await interact(() =>
      fireEvent.press(screen.getByRole('radio', { name: 'Collections' })),
    );
    await interact(() =>
      fireEvent.press(screen.getByRole('button', { name: 'Work' })),
    );

    expect(screen.getByRole('button', { name: 'Trousers' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Silk top' })).toBeNull();
  });

  test('collection management is reachable: create, rename and delete call the API', async () => {
    jest.mocked(collectionsApi.create).mockResolvedValue({
      id: 'collection-2',
      name: 'Travel',
      createdAt: '2026-09-01T00:00:00Z',
      updatedAt: '2026-09-01T00:00:00Z',
      items: [],
    });
    jest.mocked(collectionsApi.rename).mockResolvedValue({
      ...COLLECTION,
      name: 'Autumn',
    });
    jest.mocked(collectionsApi.remove).mockResolvedValue(undefined);

    await mount(<VaultScreen onProfile={noop} />);
    await interact(() =>
      fireEvent.press(screen.getByRole('radio', { name: 'Collections' })),
    );

    await interact(() =>
      fireEvent.press(
        screen.getByRole('button', { name: 'Manage collections' }),
      ),
    );

    await interact(() =>
      fireEvent.changeText(
        screen.getByLabelText('New collection name'),
        'Travel',
      ),
    );
    await interact(() =>
      fireEvent.press(screen.getByLabelText('Create collection')),
    );
    expect(collectionsApi.create).toHaveBeenCalledWith('Travel');

    await interact(() =>
      fireEvent.changeText(screen.getByLabelText('Rename Work'), 'Autumn'),
    );
    await interact(() =>
      fireEvent(screen.getByLabelText('Rename Work'), 'submitEditing'),
    );
    expect(collectionsApi.rename).toHaveBeenCalledWith(
      'collection-1',
      'Autumn',
    );

    await interact(() =>
      fireEvent.press(screen.getByLabelText('Delete Autumn')),
    );
    expect(collectionsApi.remove).toHaveBeenCalledWith('collection-1');
    // Deleting a collection is not a garment delete.
    expect(vaultApi.remove).not.toHaveBeenCalled();
  });
});
