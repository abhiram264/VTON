import { productionApiConfig } from '../src/config/apiConfig';

describe('productionApiConfig (fail-closed runtime API config)', () => {
  test('does not silently fall back to an empty baseUrl', () => {
    // Eight sites in mobile/src set baseUrl: '' with a null or throwing
    // access token; the shared default must never be one of them. With no
    // configured base URL it fails closed rather than issuing a
    // relative-path request.
    const globalEnv = globalThis as {EXPO_PUBLIC_API_URL?: string};
    const original = globalEnv.EXPO_PUBLIC_API_URL;
    const originalEnv = process.env.EXPO_PUBLIC_API_URL;
    try {
      delete globalEnv.EXPO_PUBLIC_API_URL;
      delete process.env.EXPO_PUBLIC_API_URL;
      expect(() => productionApiConfig.baseUrl).toThrow(/not configured/i);
    } finally {
      globalEnv.EXPO_PUBLIC_API_URL = original;
      if (originalEnv !== undefined) {
        process.env.EXPO_PUBLIC_API_URL = originalEnv;
      }
    }
  });

  test('surfaces a missing token instead of silently omitting Authorization', async () => {
    // http.ts omits Authorization when getAccessToken returns null, so a
    // null-token default silently sends bearer-less requests. The shared
    // default must throw at the point of use instead.
    await expect(
      Promise.resolve().then(() => productionApiConfig.getAccessToken()),
    ).rejects.toThrow(/not configured/i);
  });

  test('surfaces a missing base URL instead of issuing a relative-path request', () => {
    const globalEnv = globalThis as {EXPO_PUBLIC_API_URL?: string};
    const original = globalEnv.EXPO_PUBLIC_API_URL;
    const originalEnv = process.env.EXPO_PUBLIC_API_URL;
    try {
      delete globalEnv.EXPO_PUBLIC_API_URL;
      delete process.env.EXPO_PUBLIC_API_URL;
      expect(() => productionApiConfig.baseUrl).toThrow(/not configured/i);
    } finally {
      globalEnv.EXPO_PUBLIC_API_URL = original;
      if (originalEnv !== undefined) {
        process.env.EXPO_PUBLIC_API_URL = originalEnv;
      }
    }
  });

  test('accepts an explicitly provided base URL through the env seam', () => {
    const globalEnv = globalThis as {EXPO_PUBLIC_API_URL?: string};
    const original = globalEnv.EXPO_PUBLIC_API_URL;
    const originalEnv = process.env.EXPO_PUBLIC_API_URL;
    try {
      globalEnv.EXPO_PUBLIC_API_URL = 'https://api.example.invalid/';
      process.env.EXPO_PUBLIC_API_URL = 'https://api.example.invalid/';
      expect(productionApiConfig.baseUrl).toBe('https://api.example.invalid');
    } finally {
      globalEnv.EXPO_PUBLIC_API_URL = original;
      if (originalEnv !== undefined) {
        process.env.EXPO_PUBLIC_API_URL = originalEnv;
      } else {
        delete process.env.EXPO_PUBLIC_API_URL;
      }
    }
  });
});
