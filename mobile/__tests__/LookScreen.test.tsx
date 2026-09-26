import { render, screen } from '@testing-library/react-native';
import LookScreen from '../src/screens/LookScreen';
import { generationApi } from '../src/features/generation/generationApi';
import type { LookResource } from '../src/features/generation/types';

const LOOK_ID = '55555555-5555-5555-5555-555555555555';
const GENERATION_ID = '44444444-4444-4444-4444-444444444444';
const SIGNED_URL = 'https://signed.example.invalid/read?token=look';
const CREATED_AT = '2026-08-23T12:05:00Z';
const EXPIRES_AT = '2026-08-23T12:10:00Z';

const STORED_LOOK: LookResource = {
  id: LOOK_ID,
  generationId: GENERATION_ID,
  createdAt: CREATED_AT,
  image: { url: SIGNED_URL, expiresAt: EXPIRES_AT },
};

describe('LookScreen', () => {
  afterEach(() => {
    jest.restoreAllMocks();
  });

  test('a saved look renders the stored look it resolved, not a placeholder', async () => {
    jest
      .spyOn(generationApi, 'getLook')
      .mockResolvedValue({ ...STORED_LOOK });

    await render(<LookScreen lookId={LOOK_ID} />);

    // The signed URL that came back for THIS look is the one on screen, so the
    // screen is bound to the stored record rather than rendering prose.
    const image = await screen.findByRole('image');
    expect(image.props.source).toEqual({ uri: SIGNED_URL });

    // The stored expiry is surfaced verbatim, not re-invented.
    expect(screen.getByText(new RegExp(EXPIRES_AT))).toBeTruthy();
  });

  test('an unresolvable look id shows an honest error, not a blank screen', async () => {
    jest
      .spyOn(generationApi, 'getLook')
      .mockRejectedValue(new Error('HTTP_404'));

    await render(<LookScreen lookId={LOOK_ID} />);

    expect(await screen.findByText('Could not open the look.')).toBeTruthy();
    expect(screen.queryByRole('image')).toBeNull();
  });
});
