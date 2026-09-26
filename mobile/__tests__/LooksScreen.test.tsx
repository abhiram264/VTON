import { render, screen } from '@testing-library/react-native';
import { NavigationContainer } from '@react-navigation/native';
import LooksScreen from '../src/screens/LooksScreen';
import { looksApi } from '../src/features/looks/looksApi';
import type { LookResource } from '../src/features/generation/types';

jest.mock('../src/features/looks/looksApi', () => ({
  looksApi: { list: jest.fn(), regenerate: jest.fn(), share: jest.fn() },
}));

const noop = () => undefined;

const firstLook: LookResource = {
  id: 'look-1',
  generationId: 'generation-1',
  createdAt: '2026-08-30T10:00:00Z',
  image: {
    url: 'https://signed.example.invalid/read?X-Amz-Signature=read-1',
    expiresAt: '2026-08-30T10:05:00Z',
  },
};

const secondLook: LookResource = {
  id: 'look-2',
  generationId: 'generation-2',
  createdAt: '2026-08-31T10:00:00Z',
  image: {
    url: 'https://signed.example.invalid/read?X-Amz-Signature=read-2',
    expiresAt: '2026-08-31T10:05:00Z',
  },
};

beforeEach(() => jest.clearAllMocks());

async function renderLooksScreen() {
  await render(
    <NavigationContainer>
      <LooksScreen
        onModify={noop}
        onOpenLook={noop}
        onProfile={noop}
        pollDelay={async () => undefined}
      />
    </NavigationContainer>,
  );
}

test('shows every saved Look the API returns as a viewable image', async () => {
  jest.mocked(looksApi.list).mockResolvedValue([firstLook, secondLook]);
  await renderLooksScreen();

  for (const look of [firstLook, secondLook]) {
    const image = await screen.findByRole('image', {
      name: `Look image ${look.id}`,
    });
    expect(image.props.source).toEqual({ uri: look.image.url });
  }
  expect(screen.queryByText('No saved Looks yet.')).toBeNull();
});

test('shows an honest empty state when no Looks are saved', async () => {
  jest.mocked(looksApi.list).mockResolvedValue([]);
  await renderLooksScreen();

  expect(await screen.findByText('No saved Looks yet.')).toBeTruthy();
  expect(screen.queryAllByRole('image')).toHaveLength(0);
});
