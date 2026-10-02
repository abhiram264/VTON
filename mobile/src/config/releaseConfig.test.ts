import * as fs from 'fs';
import * as path from 'path';
import {transformSync, type PluginItem} from '@babel/core';
import {resolveApiBaseUrl} from './apiConfig';
import {
  initializeFirebase,
  isFirebaseConfigured,
  isReleaseBuild,
  type FirebaseConfig,
} from './firebase';

const MOBILE_ROOT = path.resolve(__dirname, '..', '..');

// Every Firebase value is a template placeholder, so isFirebaseConfigured is
// false without depending on any real project configuration.
const PLACEHOLDER_CONFIG: FirebaseConfig = {
  apiKey: 'REPLACE_WITH_API_KEY',
  authDomain: 'REPLACE_WITH_AUTH_DOMAIN',
  projectId: 'REPLACE_WITH_PROJECT_ID',
  appId: 'REPLACE_WITH_APP_ID',
};

describe('release API base URL guard (C02)', () => {
  test('a release build refuses a missing, placeholder, non-https, localhost or LAN target', () => {
    expect(() => resolveApiBaseUrl('', true)).toThrow(/not configured/i);
    expect(() => resolveApiBaseUrl('REPLACE_WITH_API_URL', true)).toThrow(
      /placeholder/i,
    );
    expect(() => resolveApiBaseUrl('http://api.example.invalid', true)).toThrow(
      /https/i,
    );
    expect(() => resolveApiBaseUrl('https://localhost:8443', true)).toThrow(
      /localhost or a LAN/i,
    );
    expect(() => resolveApiBaseUrl('https://127.0.0.1', true)).toThrow(
      /localhost or a LAN/i,
    );
    expect(() => resolveApiBaseUrl('https://10.0.2.2:8081', true)).toThrow(
      /localhost or a LAN/i,
    );
    expect(() => resolveApiBaseUrl('https://192.168.1.20', true)).toThrow(
      /localhost or a LAN/i,
    );
    expect(() => resolveApiBaseUrl('https://172.16.5.5', true)).toThrow(
      /localhost or a LAN/i,
    );
  });

  test('a release build accepts a public https target and strips the trailing slash', () => {
    expect(resolveApiBaseUrl('https://api.example.invalid/', true)).toBe(
      'https://api.example.invalid',
    );
  });

  test('a development build still allows the emulator/LAN backend', () => {
    expect(resolveApiBaseUrl('http://10.0.2.2:8081', false)).toBe(
      'http://10.0.2.2:8081',
    );
  });
});

describe('release Firebase guard (C02)', () => {
  test('a release build throws on a placeholder Firebase config', () => {
    const globals = globalThis as {__DEV__?: boolean};
    const original = globals.__DEV__;
    globals.__DEV__ = false;
    try {
      expect(isReleaseBuild()).toBe(true);
      expect(isFirebaseConfigured(PLACEHOLDER_CONFIG)).toBe(false);
      expect(() => initializeFirebase(PLACEHOLDER_CONFIG)).toThrow(
        /release build/i,
      );
    } finally {
      globals.__DEV__ = original;
    }
  });

  test('a development build keeps the bootable unavailable surface', () => {
    expect(isReleaseBuild()).toBe(false);
    const surface = initializeFirebase(PLACEHOLDER_CONFIG);
    expect(surface.status).toBe('unavailable');
  });
});

describe('EXPO_PUBLIC_* release inlining (C02)', () => {
  function transformWithProjectConfig(filename: string, source: string): string {
    const result = transformSync(source, {
      filename: path.join(MOBILE_ROOT, filename),
      cwd: MOBILE_ROOT,
      babelrc: false,
      configFile: path.join(MOBILE_ROOT, 'babel.config.js'),
      caller: {name: 'metro'},
    });
    if (result === null || result.code == null) {
      throw new Error(`babel produced no output for ${filename}`);
    }
    return result.code;
  }

  test('the firebase config source has no computed process.env lookup left to survive bundling', () => {
    // Regression: with the previous `process.env[key]` form the release bundle
    // kept the computed read and shipped without Firebase configuration.
    const source = fs.readFileSync(
      path.join(MOBILE_ROOT, 'src', 'config', 'firebase.ts'),
      'utf8',
    );
    const output = transformWithProjectConfig('src/config/firebase.ts', source);
    expect(output).not.toMatch(/process\.env\[/);
  });

  test('the inliner replaces a static EXPO_PUBLIC_* read with the configured literal', () => {
    // mobile/.env is gitignored and absent in CI, so drive the plugin through a
    // synthetic env seam instead of relying on a checked-out .env file.
    // `import * as fs` is a property copy, so patch the core module object that
    // babel.config.js itself requires.
    const fsModule = require('fs') as {
      existsSync: (target: string) => boolean;
      readFileSync: (target: string, encoding?: string) => string;
    };
    const savedExistsSync = fsModule.existsSync;
    const savedReadFileSync = fsModule.readFileSync;
    fsModule.existsSync = target =>
      String(target).endsWith('.env') ? true : savedExistsSync(target);
    fsModule.readFileSync = (target, encoding) =>
      String(target).endsWith('.env')
        ? 'EXPO_PUBLIC_PROBE_KEY=inlined-probe-value\n'
        : savedReadFileSync(target, encoding);
    try {
      let plugin: PluginItem | undefined;
      jest.isolateModules(() => {
        // Fresh copy so the plugin captures the synthetic .env above.
        const babelConfig = require('../../babel.config.js') as {
          plugins: PluginItem[];
        };
        plugin = babelConfig.plugins[0];
      });
      const result = transformSync(
        'export const probe = process.env.EXPO_PUBLIC_PROBE_KEY;',
        {
          filename: path.join(MOBILE_ROOT, 'probe.js'),
          cwd: MOBILE_ROOT,
          babelrc: false,
          configFile: false,
          caller: {name: 'metro'},
          plugins: plugin === undefined ? [] : [plugin],
        },
      );
      if (result === null || result.code == null) {
        throw new Error('babel produced no output for the probe');
      }
      expect(result.code).toContain('inlined-probe-value');
    } finally {
      fsModule.existsSync = savedExistsSync;
      fsModule.readFileSync = savedReadFileSync;
    }
  });
});
