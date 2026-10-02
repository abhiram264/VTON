import { fireEvent, render, screen } from '@testing-library/react-native';
import { NavigationContainer } from '@react-navigation/native';
import HomeScreen, { lookLabel } from '../src/screens/HomeScreen';
import { looksApi } from '../src/features/looks/looksApi';
import { ApiProblemError } from '../src/features/my-model/http';

jest.mock('../src/features/looks/looksApi', () => ({ looksApi: { list: jest.fn() } }));
jest.mock('../src/features/my-model/photoSource', () => ({ choosePhoto: jest.fn() }));

const look = {
  id: '11111111-2222-3333-4444-555555555555',
  generationId: 'gen-1',
  createdAt: '2026-08-30T10:00:00Z',
  image: { url: 'https://example/look', expiresAt: '2026-08-30T10:05:00Z' },
};
const noop = () => undefined;

beforeEach(() => jest.clearAllMocks());

function renderHome() {
  return render(
    <NavigationContainer>
      <HomeScreen onImport={noop} onOpenLook={noop} onProfile={noop} onQuickTry={noop} />
    </NavigationContainer>,
  );
}

test('labels a Look by its creation date, never by its opaque id', () => {
  expect(lookLabel('2026-08-30T10:00:00Z')).toBe('Look from 30 Aug 2026');
  expect(lookLabel('not-a-date')).toBe('Look from earlier');
});

test('a revoked session shows a sign-in message and offers no Retry', async () => {
  jest.mocked(looksApi.list).mockRejectedValue(new ApiProblemError('UNAUTHORIZED'));

  await renderHome();

  expect(await screen.findByText(/session has expired/i)).toBeTruthy();
  expect(screen.queryByRole('button', { name: 'Retry loading Looks' })).toBeNull();
});

test('a transient failure offers a Retry that reloads and renders the Look without its raw id', async () => {
  jest.mocked(looksApi.list)
    .mockRejectedValueOnce(new ApiProblemError('HTTP_500'))
    .mockResolvedValueOnce([look]);

  await renderHome();

  expect(await screen.findByText('Could not load recent Looks.')).toBeTruthy();
  await fireEvent.press(screen.getByRole('button', { name: 'Retry loading Looks' }));

  expect(await screen.findByRole('button', { name: 'Look from 30 Aug 2026' })).toBeTruthy();
  expect(screen.queryByText(look.id)).toBeNull();
  expect(looksApi.list).toHaveBeenCalledTimes(2);
});
