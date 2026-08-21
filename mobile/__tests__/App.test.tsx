import { render, screen } from '@testing-library/react-native';
import App from '../App';

test('renders the startup screen', async () => {
  await render(<App />);
  expect(screen.getByText('Application ready')).toBeTruthy();
});
