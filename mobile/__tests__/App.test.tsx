import { render, screen } from '@testing-library/react-native';
import App from '../App';

// Auth gating changed the precondition for this file's intent (the app
// renders and navigates, not auth itself): inject a signed-in session while
// leaving the real firebase surface module in play.
jest.mock('../src/config/firebase', () => ({
  ...jest.requireActual('../src/config/firebase'),
  subscribeToAuthSession: (listener: (session: unknown) => void) => {
    listener({ status: 'signed-in', uid: 'test-uid', getIdToken: async () => 'test-token' });
    return () => {};
  },
}));

test('renders the startup screen', async () => {
  await render(<App />);
  expect(screen.getByText('Home')).toBeTruthy();
});
