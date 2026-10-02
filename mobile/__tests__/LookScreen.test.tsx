import { act, fireEvent, render, screen } from '@testing-library/react-native';
import LookScreen from '../src/screens/LookScreen';
import { looksApi, type LookDetail } from '../src/features/looks/looksApi';

jest.mock('../src/features/looks/looksApi', () => ({
  looksApi: {
    list: jest.fn(),
    get: jest.fn(),
    remove: jest.fn(),
    regenerate: jest.fn(),
  },
}));

const LOOK_ID = '55555555-5555-5555-5555-555555555555';
const GENERATION_ID = '44444444-4444-4444-4444-444444444444';
const SHIRT_ID = '11111111-1111-1111-1111-111111111111';
const TROUSER_ID = '22222222-2222-2222-2222-222222222222';
const SIGNED_URL = 'https://signed.example.invalid/read?token=look';
const CREATED_AT = '2026-08-23T12:05:00Z';
const EXPIRES_AT = '2026-08-23T12:10:00Z';

const STORED_LOOK: LookDetail = {
  id: LOOK_ID,
  generationId: GENERATION_ID,
  createdAt: CREATED_AT,
  image: { url: SIGNED_URL, expiresAt: EXPIRES_AT },
  items: [
    { vaultItemId: SHIRT_ID, slot: 'UPPER_BODY' },
    { vaultItemId: TROUSER_ID, slot: 'LOWER_BODY' },
  ],
  provider: 'fashn',
  providerModel: 'tryon-v1.6',
  qualityStatus: 'PASS',
};

const get = looksApi.get as jest.Mock;
const remove = looksApi.remove as jest.Mock;

describe('LookScreen', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  test('a saved look renders the stored look it resolved, not a placeholder', async () => {
    get.mockResolvedValue({ ...STORED_LOOK });

    await render(<LookScreen lookId={LOOK_ID} />);

    // The signed URL that came back for THIS look is the one on screen, so the
    // screen is bound to the stored record rather than rendering prose.
    const image = await screen.findByRole('image');
    expect(image.props.source).toEqual({ uri: SIGNED_URL });

    // The stored expiry is surfaced verbatim, not re-invented.
    expect(screen.getByText(new RegExp(EXPIRES_AT))).toBeTruthy();
  });

  test('provenance names the exact source garments, the outcome and the timestamp', async () => {
    get.mockResolvedValue({ ...STORED_LOOK });

    await render(<LookScreen lookId={LOOK_ID} />);
    await screen.findByRole('image');

    // Both garments that produced the render are named, with their slot.
    expect(screen.getByText(new RegExp(`Top — ${SHIRT_ID}`))).toBeTruthy();
    expect(screen.getByText(new RegExp(`Bottom — ${TROUSER_ID}`))).toBeTruthy();
    expect(screen.getByText(new RegExp(`Created ${CREATED_AT}`))).toBeTruthy();
    expect(screen.getByText(/Quality check passed/)).toBeTruthy();
    expect(screen.getByText(/Generated with fashn/)).toBeTruthy();
  });

  test('an unresolvable look id shows an honest error, not a blank screen', async () => {
    get.mockRejectedValue(new Error('HTTP_404'));

    await render(<LookScreen lookId={LOOK_ID} />);

    expect(await screen.findByText('Could not open the look.')).toBeTruthy();
    expect(screen.queryByRole('image')).toBeNull();
  });

  test('a look the caller does not own discloses no image and no provenance', async () => {
    // The server answers 404 UNAUTHORIZED_OR_NOT_FOUND for a foreign Look, so
    // nothing owner-only ever reaches the screen.
    get.mockRejectedValue(new Error('UNAUTHORIZED_OR_NOT_FOUND'));

    await render(<LookScreen lookId={LOOK_ID} />);

    expect(await screen.findByText('Could not open the look.')).toBeTruthy();
    expect(screen.queryByRole('image')).toBeNull();
    expect(screen.queryByText(/Made from/)).toBeNull();
  });

  test('a broken preview offers a retry the user can actually press, keeping the honest error copy', async () => {
    get.mockResolvedValue({ ...STORED_LOOK });

    await render(<LookScreen lookId={LOOK_ID} />);
    const image = await screen.findByRole('image');

    // The signed URL failed to load. The user must be told, in words.
    await act(async () => {
      fireEvent(image, 'error');
    });
    expect(screen.getByText('Signed preview unavailable.')).toBeTruthy();

    // ...and must be able to do something about it. Reachability is the bug:
    // the control existed but could never be pressed.
    const retry = screen.getByRole('button', { name: 'Retry preview' });
    await act(async () => {
      fireEvent.press(retry);
    });

    // Pressing it must actually re-request the look.
    expect(get).toHaveBeenCalledTimes(2);
    expect(await screen.findByRole('image')).toBeTruthy();
  });

  test('deleting requires an explicit confirmation and never deletes on the first press', async () => {
    get.mockResolvedValue({ ...STORED_LOOK });
    remove.mockResolvedValue(undefined);

    await render(<LookScreen lookId={LOOK_ID} />);
    await screen.findByRole('image');

    // First press only asks; nothing is destroyed yet.
    await act(async () => {
      fireEvent.press(screen.getByRole('button', { name: 'Delete look' }));
    });
    expect(remove).not.toHaveBeenCalled();
    expect(screen.getByText(/garments it used stay in your Vault/)).toBeTruthy();

    // Backing out leaves the look untouched.
    await act(async () => {
      fireEvent.press(screen.getByRole('button', { name: 'Cancel delete look' }));
    });
    expect(remove).not.toHaveBeenCalled();
    expect(screen.getByRole('image')).toBeTruthy();
  });

  test('confirming delete calls the mounted delete route once and reports the vault garments survive', async () => {
    get.mockResolvedValue({ ...STORED_LOOK });
    remove.mockResolvedValue(undefined);
    const onDeleted = jest.fn();

    await render(<LookScreen lookId={LOOK_ID} onDeleted={onDeleted} />);
    await screen.findByRole('image');

    await act(async () => {
      fireEvent.press(screen.getByRole('button', { name: 'Delete look' }));
    });
    await act(async () => {
      fireEvent.press(screen.getByRole('button', { name: 'Confirm delete look' }));
    });

    expect(remove).toHaveBeenCalledTimes(1);
    expect(remove).toHaveBeenCalledWith(LOOK_ID);
    expect(onDeleted).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('image')).toBeNull();
  });

  test('a delete that fails keeps the look on screen and says it is still saved', async () => {
    get.mockResolvedValue({ ...STORED_LOOK });
    remove.mockRejectedValue(new Error('HTTP_500'));
    const onDeleted = jest.fn();

    await render(<LookScreen lookId={LOOK_ID} onDeleted={onDeleted} />);
    await screen.findByRole('image');

    await act(async () => {
      fireEvent.press(screen.getByRole('button', { name: 'Delete look' }));
    });
    await act(async () => {
      fireEvent.press(screen.getByRole('button', { name: 'Confirm delete look' }));
    });

    expect(screen.getByText('Could not delete the look. It is still saved.')).toBeTruthy();
    expect(screen.getByRole('image')).toBeTruthy();
    expect(onDeleted).not.toHaveBeenCalled();
  });
});
