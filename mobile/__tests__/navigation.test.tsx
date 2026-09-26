import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import App from '../App';

jest.mock('../src/features/my-model/photoSource', () => ({
  choosePhoto: jest.fn().mockResolvedValue(null),
  takePhoto: jest.fn().mockResolvedValue(null),
  isCameraPermissionDenied: jest.fn().mockReturnValue(false),
}));

jest.mock('../src/features/vault/vaultApi', () => ({ vaultApi: { list: jest.fn().mockResolvedValue([]) } }));
jest.mock('../src/features/vault/collectionApi', () => ({ collectionApi: { list: jest.fn().mockResolvedValue([]) } }));
jest.mock('../src/features/looks/looksApi', () => ({ looksApi: { list: jest.fn().mockResolvedValue([]) } }));
test('launches into the four primary tabs in documented order', async () => {
  await render(<App />);

  await screen.findByRole('header', { name: 'Home' });
  const tabs = screen.getAllByRole('tab');
  expect(tabs.map(tab => tab.props.accessibilityLabel)).toEqual([
    'HOME, tab, 1 of 4',
    'VAULT, tab, 2 of 4',
    'STUDIO, tab, 3 of 4',
    'LOOKS, tab, 4 of 4',
  ]);
  expect(screen.getByRole('tab', { name: /HOME/ }).props.accessibilityState).toMatchObject({ selected: true });
  expect(screen.queryByRole('tab', { name: 'Profile and settings' })).toBeNull();
});

test('each workspace exposes its required accessible surface', async () => {
  await render(<App />);
  await screen.findByRole('header', { name: 'Home' });
  expect(screen.getByRole('button', { name: 'Import Item' })).toBeEnabled();
  expect(screen.getByRole('button', { name: 'Quick Try' })).toBeEnabled();
  expect(screen.getByText('No recent shares yet.')).toBeTruthy();
  expect(screen.getByText('No recent Looks yet.')).toBeTruthy();

  fireEvent.press(screen.getByRole('tab', { name: /VAULT/ }));
  await screen.findByRole('header', { name: 'Vault' });
  for (const label of [
    'All', 'Tops', 'Bottoms', 'One Pieces', 'Ethnic', 'Footwear',
    'Headwear', 'Accessories', 'Collections',
  ]) {
    expect(screen.getByRole('radio', { name: label })).toBeTruthy();
  }
  expect(screen.getByRole('radio', { name: 'All' }).props.accessibilityState).toMatchObject({ checked: true });
  fireEvent.press(screen.getByRole('radio', { name: 'Tops' }));
  await waitFor(() => expect(screen.getByRole('radio', { name: 'Tops' }).props.accessibilityState).toMatchObject({ checked: true }));
  expect(screen.getByRole('radio', { name: 'All' }).props.accessibilityState).toMatchObject({ checked: false });

  fireEvent.press(screen.getByRole('tab', { name: /STUDIO/ }));
  await screen.findByRole('header', { name: 'Studio' });
  expect(screen.getByRole('button', { name: 'Avatar' })).toBeEnabled();
  expect(screen.getByRole('button', { name: 'Add from Vault' })).toBeDisabled();
  expect(screen.getByRole('button', { name: 'Generate' })).toBeDisabled();
  expect(screen.getByLabelText('Natural-language command')).toBeTruthy();

  fireEvent.press(screen.getByRole('tab', { name: /LOOKS/ }));
  await screen.findByRole('header', { name: 'Looks' });
  expect(await screen.findByText('No saved Looks yet.')).toBeTruthy();
  expect(screen.getByText('No recent generations yet.')).toBeTruthy();
  for (const action of ['Regenerate', 'Modify', 'Share']) {
    expect(screen.getByRole('button', { name: action })).toBeDisabled();
  }
});

test('profile and settings is a stack destination that preserves the tab', async () => {
  await render(<App />);
  await screen.findByRole('header', { name: 'Home' });
  fireEvent.press(screen.getByRole('tab', { name: /VAULT/ }));
  await screen.findByRole('header', { name: 'Vault' });
  fireEvent.press(screen.getByRole('button', { name: 'Profile and settings' }));
  expect(await screen.findByText('My Model')).toBeTruthy();
  fireEvent.press(screen.getByLabelText('Back'));
  await waitFor(() => expect(screen.getByRole('header', { name: 'Vault' })).toBeTruthy());
});

test('Quick Try opens the established generation route', async () => {
  await render(<App />);
  await screen.findByRole('header', { name: 'Home' });
  fireEvent.press(screen.getByRole('button', { name: 'Quick Try' }));
  expect(await screen.findByText('Create a Look')).toBeTruthy();
});
