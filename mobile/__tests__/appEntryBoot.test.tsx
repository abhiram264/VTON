import { render, screen } from '@testing-library/react-native';
import type { ComponentType } from 'react';

// The app entry must boot under a placeholder/absent Firebase config: the
// module-scope initializeFirebase() in AppShell resolves an explicitly
// unavailable surface and navigation still mounts. This covers the boot
// contract only; surface semantics live in firebaseSurface.test.ts.
const ENV_KEYS = [
  'EXPO_PUBLIC_FIREBASE_API_KEY',
  'EXPO_PUBLIC_FIREBASE_AUTH_DOMAIN',
  'EXPO_PUBLIC_FIREBASE_PROJECT_ID',
  'EXPO_PUBLIC_FIREBASE_APP_ID',
] as const;

test('boots without throwing or console errors when Firebase env is absent', async () => {
  const saved = ENV_KEYS.map(key => [key, process.env[key]] as const);
  for (const key of ENV_KEYS) delete process.env[key];
  // Pass-through spy: records calls without silencing real failures.
  const consoleError = jest.spyOn(console, 'error');
  try {
    // require (not top-level import) is deliberate: AppShell's module-scope
    // initializeFirebase() must first run against the cleared env seam.
    const App = require('../App').default as ComponentType;
    await render(<App />);
    expect(screen.getByText('Home')).toBeTruthy();
  } finally {
    for (const [key, value] of saved) {
      if (value === undefined) delete process.env[key];
      else process.env[key] = value;
    }
  }
  expect(consoleError).not.toHaveBeenCalled();
});
