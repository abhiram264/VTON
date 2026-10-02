import * as fs from 'fs';
import * as path from 'path';
import { transformSync } from '@babel/core';

import {
  assertReleaseApiTarget,
  describeReleaseApiTargetProblem,
} from '../releaseEnvGuard';

const MOBILE_ROOT = path.resolve(__dirname, '..');
const FIREBASE_SOURCE = path.join(MOBILE_ROOT, 'src', 'config', 'firebase.ts');
const ENV_PATH = path.join(MOBILE_ROOT, '.env');

const FIREBASE_PUBLIC_KEYS = [
  'EXPO_PUBLIC_FIREBASE_API_KEY',
  'EXPO_PUBLIC_FIREBASE_AUTH_DOMAIN',
  'EXPO_PUBLIC_FIREBASE_PROJECT_ID',
  'EXPO_PUBLIC_FIREBASE_APP_ID',
] as const;

/** Read the same EXPO_PUBLIC_* map the babel plugin reads from mobile/.env. */
function readPublicEnv(): Record<string, string> {
  const map: Record<string, string> = {};
  if (!fs.existsSync(ENV_PATH)) {
    return map;
  }
  for (const line of fs.readFileSync(ENV_PATH, 'utf8').split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) {
      continue;
    }
    const eq = trimmed.indexOf('=');
    if (eq <= 0) {
      continue;
    }
    map[trimmed.slice(0, eq).trim()] = trimmed.slice(eq + 1).trim();
  }
  return map;
}

/** Bundle src/config/firebase.ts with the real project Babel configuration. */
function bundleFirebaseConfig(): string {
  const result = transformSync(fs.readFileSync(FIREBASE_SOURCE, 'utf8'), {
    cwd: MOBILE_ROOT,
    filename: FIREBASE_SOURCE,
    configFile: path.join(MOBILE_ROOT, 'babel.config.js'),
    babelrc: false,
    caller: { name: 'metro' },
    sourceType: 'module',
  });
  return result?.code ?? '';
}

describe('C02 mobile build configuration', () => {
  describe('EXPO_PUBLIC_* inlining of the bundled Firebase config', () => {
    const publicEnv = readPublicEnv();
    const code = bundleFirebaseConfig();

    test('the bundle carries no computed process.env lookup', () => {
      // Regression: firebase.ts read `process.env[key]`. The inliner
      // substitutes only literal member expressions, so that computed form
      // was invisible to it and a release bundle shipped with no Firebase
      // configuration — the auth surface stayed unavailable.
      expect(code).not.toMatch(/process\.env\[/);
    });

    test('every Firebase public value is inlined or left as the supported member form', () => {
      for (const key of FIREBASE_PUBLIC_KEYS) {
        const literal = publicEnv[key];
        const inlined = literal !== undefined && code.includes(literal);
        const memberForm = code.includes(`process.env.${key}`);
        expect(inlined || memberForm).toBe(true);
      }
    });
  });

  describe('release API target guard', () => {
    test('rejects missing, placeholder, plain-HTTP, loopback, emulator and LAN targets', () => {
      const rejected: Array<string | undefined> = [
        undefined,
        '',
        '   ',
        'REPLACE_WITH_API_URL',
        'https://api.example.invalid/PLACEHOLDER',
        'api.example.invalid',
        'http://api.example.invalid',
        'https://localhost:8081',
        'https://127.0.0.1',
        'http://10.0.2.2:8081',
        'https://10.1.2.3',
        'https://192.168.1.20',
        'https://172.16.4.4',
        'https://172.31.255.1',
        'https://169.254.1.1',
        'https://vault.local',
      ];
      for (const target of rejected) {
        expect(describeReleaseApiTargetProblem(target)).not.toBeNull();
        expect(() => assertReleaseApiTarget(target)).toThrow(
          /Release build configuration rejected/,
        );
      }
    });

    test('accepts an absolute https public target and returns it unchanged', () => {
      const target = 'https://api.aifashionvault.example';
      expect(describeReleaseApiTargetProblem(target)).toBeNull();
      expect(assertReleaseApiTarget(target)).toBe(target);
      expect(describeReleaseApiTargetProblem(`${target}/`)).toBeNull();
    });
  });
});
