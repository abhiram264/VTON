import { render, screen, waitFor } from '@testing-library/react-native';
import StudioScreen from '../src/screens/StudioScreen';
import { studioApi } from '../src/features/studio/studioApi';
import { vaultApi } from '../src/features/vault/vaultApi';

jest.mock('../src/features/studio/studioApi', () => ({
  studioApi: {
    get: jest.fn(),
    addItem: jest.fn(),
    command: jest.fn(),
  },
}));
jest.mock('../src/features/vault/vaultApi', () => ({ vaultApi: { list: jest.fn() } }));

const noop = () => undefined;

beforeEach(() => {
  jest.clearAllMocks();
  jest.mocked(vaultApi.list).mockResolvedValue([]);
});

test('Studio renders the real multi-item session into top, bottom and footwear slots', async () => {
  jest.mocked(studioApi.get).mockResolvedValue({
    sessionId: 'session-1',
    itemIds: ['item-top', 'item-bottom', 'item-footwear'],
  });
  jest.mocked(vaultApi.list).mockResolvedValue([
    { id: 'item-top', category: 'TOP', name: 'Silk top' },
    { id: 'item-bottom', category: 'BOTTOM', name: 'Trousers' },
    { id: 'item-footwear', category: 'FOOTWEAR', name: 'Sneakers' },
  ]);

  const generate = jest.fn();
  await render(
    <StudioScreen
      studioSessionId="session-1"
      onAddFromVault={noop}
      onAvatar={noop}
      onGenerate={generate}
      onProfile={noop}
    />,
  );

  await waitFor(() => expect(screen.getByTestId('slot-TOP')).toHaveTextContent('item-top'));
  expect(screen.getByTestId('slot-BOTTOM')).toHaveTextContent('item-bottom');
  expect(screen.getByTestId('slot-FOOTWEAR')).toHaveTextContent('item-footwear');
  expect(screen.getByTestId('slot-TOP')).not.toHaveTextContent('item-bottom');
  expect(screen.getByRole('button', { name: 'Generate' })).toBeEnabled();
});
