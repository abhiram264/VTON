import { act, fireEvent, render, screen } from '@testing-library/react-native';
import LookScreen from '../src/screens/LookScreen';
import { looksApi, type LookDetail } from '../src/features/looks/looksApi';
import { createLookExporter } from '../src/features/export/lookExport';
import { vaultApi } from '../src/features/vault/vaultApi';
import { ApiProblemError } from '../src/features/my-model/http';

jest.mock('../src/features/looks/looksApi', () => ({
  looksApi: {
    list: jest.fn(),
    get: jest.fn(),
    remove: jest.fn(),
    regenerate: jest.fn(),
  },
}));

// The export bridge is a native seam; the screen is tested against the real
// orchestration contract (request shape + outcome copy) with the bridge faked.
jest.mock('../src/features/export/lookExport', () => ({
  createLookExporter: jest.fn(),
}));

jest.mock('../src/features/vault/vaultApi', () => ({
  vaultApi: { list: jest.fn(), get: jest.fn(), update: jest.fn(), remove: jest.fn(), image: jest.fn() },
}));

const vaultList = jest.mocked(vaultApi.list);
const exportLook = jest.fn();
const cleanupExpiredStaging = jest.fn();
jest.mocked(createLookExporter).mockReturnValue({ exportLook, cleanupExpiredStaging });

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
    exportLook.mockResolvedValue({ status: 'SHARED' });
    cleanupExpiredStaging.mockResolvedValue(undefined);
    vaultList.mockResolvedValue([]);
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
    vaultList.mockResolvedValue([
      { id: SHIRT_ID, category: 'TOPS', name: 'Linen shirt' },
      { id: TROUSER_ID, category: 'BOTTOMS', name: 'Grey trousers' },
    ]);

    await render(<LookScreen lookId={LOOK_ID} />);
    await screen.findByRole('image');

    // Both garments that produced the render are named, with their slot, and
    // the raw vault id is never shown.
    expect(await screen.findByText('Top — Linen shirt')).toBeTruthy();
    expect(screen.getByText('Bottom — Grey trousers')).toBeTruthy();
    expect(screen.queryByText(new RegExp(SHIRT_ID))).toBeNull();
    expect(screen.getByText(new RegExp(`Created ${CREATED_AT}`))).toBeTruthy();
    expect(screen.getByText(/Quality check passed/)).toBeTruthy();
    expect(screen.getByText(/Generated with fashn/)).toBeTruthy();
  });

  test('a source garment that is gone is named as unavailable, not as a blank or a raw id', async () => {
    get.mockResolvedValue({ ...STORED_LOOK });
    // The Vault no longer holds the shirt: only the trousers resolve.
    vaultList.mockResolvedValue([{ id: TROUSER_ID, category: 'BOTTOMS', name: 'Grey trousers' }]);

    await render(<LookScreen lookId={LOOK_ID} />);
    await screen.findByRole('image');

    expect(
      await screen.findByText(
        'Top — no longer in your Vault — use Modify to build a new look without it',
      ),
    ).toBeTruthy();
    expect(screen.getByText('Bottom — Grey trousers')).toBeTruthy();
    expect(screen.queryByText(new RegExp(SHIRT_ID))).toBeNull();
  });

  test('a Vault lookup that fails says so instead of claiming the garment is gone', async () => {
    get.mockResolvedValue({ ...STORED_LOOK });
    vaultList.mockRejectedValue(new Error('HTTP_503'));

    await render(<LookScreen lookId={LOOK_ID} />);
    await screen.findByRole('image');

    expect(await screen.findByText('Top — name unavailable right now')).toBeTruthy();
    expect(screen.queryByText(/no longer in your Vault/)).toBeNull();
  });

  test('a missing or foreign look reads as gone, never as a retryable connection problem', async () => {
    // The server answers 404 UNAUTHORIZED_OR_NOT_FOUND for a Look that was
    // deleted or is not the caller's, so nothing owner-only reaches the screen
    // and retrying is not offered: it can never succeed.
    get.mockRejectedValue(new ApiProblemError('UNAUTHORIZED_OR_NOT_FOUND'));

    await render(<LookScreen lookId={LOOK_ID} />);

    expect(
      await screen.findByText('This look is not available. It may have been deleted.'),
    ).toBeTruthy();
    expect(screen.queryByRole('image')).toBeNull();
    expect(screen.queryByText(/Made from/)).toBeNull();
    expect(screen.queryByRole('button', { name: 'Retry opening the look' })).toBeNull();
  });

  test('a signed-out session says so instead of blaming the connection', async () => {
    get.mockRejectedValue(new ApiProblemError('UNAUTHORIZED'));

    await render(<LookScreen lookId={LOOK_ID} />);

    expect(await screen.findByText('Sign in again to open this look.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Retry opening the look' })).toBeNull();
  });

  test('a transient failure offers a retry that actually re-reads the look', async () => {
    get.mockRejectedValueOnce(new Error('Network request failed'));
    get.mockResolvedValue({ ...STORED_LOOK });

    await render(<LookScreen lookId={LOOK_ID} />);

    expect(
      await screen.findByText('Could not open the look. Check your connection and try again.'),
    ).toBeTruthy();

    await act(async () => {
      fireEvent.press(screen.getByRole('button', { name: 'Retry opening the look' }));
    });

    expect(get).toHaveBeenCalledTimes(2);
    expect(await screen.findByRole('image')).toBeTruthy();
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

  test('nothing is exported until the user acknowledges the copy leaves the app', async () => {
    get.mockResolvedValue({ ...STORED_LOOK });

    await render(<LookScreen lookId={LOOK_ID} />);
    await screen.findByRole('image');

    // Staging is swept on mount, but no export happens by merely opening the look.
    expect(cleanupExpiredStaging).toHaveBeenCalledTimes(1);
    expect(exportLook).not.toHaveBeenCalled();

    // Asking to share only raises the disclosure; the bytes are not touched yet.
    await act(async () => {
      fireEvent.press(screen.getByRole('button', { name: 'Share look' }));
    });
    expect(exportLook).not.toHaveBeenCalled();
    expect(screen.getByText(/copy of this look outside the app/)).toBeTruthy();

    // Backing out exports nothing at all.
    await act(async () => {
      fireEvent.press(screen.getByRole('button', { name: 'Cancel export' }));
    });
    expect(exportLook).not.toHaveBeenCalled();
    expect(screen.queryByText(/copy of this look outside the app/)).toBeNull();
  });

  test('confirming an export hands the acknowledged signed preview to the exporter and reports the result', async () => {
    get.mockResolvedValue({ ...STORED_LOOK });

    await render(<LookScreen lookId={LOOK_ID} />);
    await screen.findByRole('image');

    await act(async () => {
      fireEvent.press(screen.getByRole('button', { name: 'Share look' }));
    });
    await act(async () => {
      fireEvent.press(screen.getByRole('button', { name: 'Confirm export copy' }));
    });

    expect(exportLook).toHaveBeenCalledTimes(1);
    expect(exportLook).toHaveBeenCalledWith(
      expect.objectContaining({
        lookId: LOOK_ID,
        imageUrl: SIGNED_URL,
        disclosureAcknowledged: true,
      }),
      'share',
    );
    expect(
      await screen.findByText(
        'Shared. The copy you sent is outside this app and cannot be deleted from here.',
      ),
    ).toBeTruthy();
  });

  test('a device without the export bridge is told sharing is unavailable, not that it shared', async () => {
    get.mockResolvedValue({ ...STORED_LOOK });
    exportLook.mockResolvedValue({ status: 'SHARE_UNAVAILABLE' });

    await render(<LookScreen lookId={LOOK_ID} />);
    await screen.findByRole('image');
    await act(async () => {
      fireEvent.press(screen.getByRole('button', { name: 'Share look' }));
    });
    await act(async () => {
      fireEvent.press(screen.getByRole('button', { name: 'Confirm export copy' }));
    });

    expect(await screen.findByText('Sharing is not available on this device yet.')).toBeTruthy();
    expect(screen.queryByText(/^Shared\./)).toBeNull();
  });

  test('save-to-photos reports a denied permission distinctly from a saved copy', async () => {
    get.mockResolvedValue({ ...STORED_LOOK });
    exportLook.mockResolvedValue({ status: 'PERMISSION_DENIED' });

    await render(<LookScreen lookId={LOOK_ID} />);
    await screen.findByRole('image');
    await act(async () => {
      fireEvent.press(screen.getByRole('button', { name: 'Save look to photos' }));
    });
    await act(async () => {
      fireEvent.press(screen.getByRole('button', { name: 'Confirm save to photos' }));
    });

    expect(exportLook).toHaveBeenCalledWith(expect.anything(), 'save');
    expect(
      await screen.findByText('Export needs photo permission, which was denied. Nothing was sent.'),
    ).toBeTruthy();
    expect(screen.queryByText('Saved to your photos.')).toBeNull();
  });
});
