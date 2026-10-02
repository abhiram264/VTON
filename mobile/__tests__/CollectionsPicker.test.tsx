import { act, fireEvent, render, screen } from '@testing-library/react-native';
import CollectionsPicker from '../src/features/collections/CollectionsPicker';
import type {
  Collection,
  CollectionsApi,
} from '../src/features/collections/collectionsApi';

const ITEM_ID = '33333333-3333-3333-3333-333333333333';
const SUMMER: Collection = {
  id: '11111111-1111-1111-1111-111111111111',
  name: 'Summer',
  createdAt: '2026-09-01T00:00:00Z',
  updatedAt: '2026-09-01T00:00:00Z',
  items: [],
};
const WORK: Collection = {
  id: '22222222-2222-2222-2222-222222222222',
  name: 'Work',
  createdAt: '2026-09-01T00:00:00Z',
  updatedAt: '2026-09-01T00:00:00Z',
  items: [{ vaultItemId: ITEM_ID, createdAt: '2026-09-01T00:00:00Z' }],
};

function fakeApi(overrides: Partial<CollectionsApi> = {}): CollectionsApi {
  return {
    list: jest.fn().mockResolvedValue([SUMMER, WORK]),
    create: jest.fn(),
    rename: jest.fn(),
    remove: jest.fn().mockResolvedValue(undefined),
    addItem: jest.fn().mockResolvedValue({
      ...SUMMER,
      items: [{ vaultItemId: ITEM_ID, createdAt: '2026-09-01T00:00:00Z' }],
    }),
    removeItem: jest.fn().mockResolvedValue(undefined),
    ...overrides,
  };
}

/** Fire a UI event and drain the async work it starts, inside act(). */
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

describe('CollectionsPicker', () => {
  test('membership mode toggles membership through the API and never deletes a garment', async () => {
    const api = fakeApi();
    await mount(<CollectionsPicker vaultItemId={ITEM_ID} api={api} />);

    // Work already contains the garment; Summer does not.
    expect(screen.getByLabelText('Membership Work')).toBeChecked();
    expect(screen.getByLabelText('Membership Summer')).not.toBeChecked();

    await interact(() =>
      fireEvent.press(screen.getByLabelText('Membership Summer')),
    );
    expect(api.addItem).toHaveBeenCalledWith(SUMMER.id, ITEM_ID);
    expect(screen.getByLabelText('Membership Summer')).toBeChecked();

    await interact(() =>
      fireEvent.press(screen.getByLabelText('Membership Work')),
    );
    expect(api.removeItem).toHaveBeenCalledWith(WORK.id, ITEM_ID);
    expect(screen.getByLabelText('Membership Work')).not.toBeChecked();
    expect(screen.getByLabelText('Membership Summer')).toBeTruthy();
  });

  test('selection mode reports ids without touching membership', async () => {
    const api = fakeApi();
    const onChange = jest.fn();
    await mount(
      <CollectionsPicker
        vaultItemId={null}
        api={api}
        selectedIds={[WORK.id]}
        onChange={onChange}
      />,
    );

    expect(screen.getByLabelText('Membership Work')).toBeChecked();
    expect(screen.getByLabelText('Membership Summer')).not.toBeChecked();

    await interact(() =>
      fireEvent.press(screen.getByLabelText('Membership Summer')),
    );

    expect(onChange).toHaveBeenCalledWith([WORK.id, SUMMER.id]);
    expect(api.addItem).not.toHaveBeenCalled();
    expect(api.removeItem).not.toHaveBeenCalled();
  });

  test('deleting a collection removes only the collection', async () => {
    const api = fakeApi();
    await mount(<CollectionsPicker vaultItemId={ITEM_ID} api={api} />);

    await interact(() =>
      fireEvent.press(screen.getByLabelText('Delete Summer')),
    );

    expect(api.remove).toHaveBeenCalledWith(SUMMER.id);
    expect(screen.queryByLabelText('Membership Summer')).toBeNull();
    expect(screen.getByLabelText('Membership Work')).toBeTruthy();
  });

  test('create adds a new collection and joins it when a garment is present', async () => {
    const created: Collection = {
      id: '44444444-4444-4444-4444-444444444444',
      name: 'Travel',
      createdAt: '2026-09-01T00:00:00Z',
      updatedAt: '2026-09-01T00:00:00Z',
      items: [],
    };
    const api = fakeApi({
      create: jest.fn().mockResolvedValue(created),
      addItem: jest.fn().mockResolvedValue({
        ...created,
        items: [{ vaultItemId: ITEM_ID, createdAt: '2026-09-01T00:00:00Z' }],
      }),
    });
    await mount(<CollectionsPicker vaultItemId={ITEM_ID} api={api} />);

    await interact(() =>
      fireEvent.changeText(
        screen.getByLabelText('New collection name'),
        'Travel',
      ),
    );
    await interact(() =>
      fireEvent.press(screen.getByLabelText('Create collection')),
    );

    expect(api.create).toHaveBeenCalledWith('Travel');
    expect(api.addItem).toHaveBeenCalledWith(created.id, ITEM_ID);
    expect(screen.getByLabelText('Membership Travel')).toBeChecked();
  });

  test('renaming a collection persists through the API', async () => {
    const api = fakeApi({
      rename: jest.fn().mockResolvedValue({ ...SUMMER, name: 'Autumn' }),
    });
    await mount(<CollectionsPicker vaultItemId={ITEM_ID} api={api} />);

    await interact(() =>
      fireEvent.changeText(screen.getByLabelText('Rename Summer'), 'Autumn'),
    );
    await interact(() =>
      fireEvent(screen.getByLabelText('Rename Summer'), 'submitEditing'),
    );

    expect(api.rename).toHaveBeenCalledWith(SUMMER.id, 'Autumn');
    expect(screen.getByLabelText('Rename Autumn')).toBeTruthy();
  });

  test('a load failure surfaces a retry and reports the error', async () => {
    const api = fakeApi({
      list: jest.fn().mockRejectedValue(new Error('offline')),
    });
    const onError = jest.fn();
    await mount(
      <CollectionsPicker vaultItemId={ITEM_ID} api={api} onError={onError} />,
    );

    expect(screen.getByText('Could not load collections.')).toBeTruthy();
    expect(onError).toHaveBeenCalled();
    expect(screen.getByLabelText('Retry collections')).toBeTruthy();
  });
});
