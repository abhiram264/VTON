/**
 * Build-time env inlining (owner decision 2026-09-27, U19 §3).
 *
 * Expo's EXPO_PUBLIC_* convention does not exist in bare React Native, so
 * this Babel plugin is the inlining mechanism: reads of
 * `process.env.EXPO_PUBLIC_*` are replaced with string literals sourced
 * from mobile/.env at bundle time. Only EXPO_PUBLIC_-prefixed keys are
 * inlined; every value there is public-by-design (Firebase client config,
 * API base URL — see src/config/firebase.ts header (a)). No secret may
 * ever appear in mobile/.env.
 */
const fs = require('fs');
const path = require('path');

const {assertReleaseApiTarget} = require('./releaseEnvGuard');

function readPublicEnv(envDir) {
  const envPath = path.join(envDir, '.env');
  const map = {};
  if (!fs.existsSync(envPath)) {
    return map;
  }
  for (const line of fs.readFileSync(envPath, 'utf8').split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) {
      continue;
    }
    const eq = trimmed.indexOf('=');
    if (eq <= 0) {
      continue;
    }
    const key = trimmed.slice(0, eq).trim();
    if (key.startsWith('EXPO_PUBLIC_')) {
      map[key] = trimmed.slice(eq + 1).trim();
    }
  }
  return map;
}

const publicEnv = readPublicEnv(__dirname);

module.exports = {
  presets: ['module:@react-native/babel-preset'],
  plugins: [
    function inlineExpoPublicEnv(api) {
      const {types: t} = api;
      // Under jest the env seam is owned by the tests at runtime (they assign
      // and delete process.env.EXPO_PUBLIC_* — see apiConfig.ts globalThis
      // fallback); inlining there would freeze the literal and break
      // fail-closed tests. babel-jest declares caller name 'babel-jest'
      // (node_modules/babel-jest/build/index.js); Metro declares 'metro'.
      const isJest = api.caller(c => c != null && c.name === 'babel-jest');
      // Release guard (C02): Metro runs release bundles with
      // BABEL_ENV=production. Refuse to build a bundle whose inlined public
      // API target is missing, a placeholder, plain HTTP, or a
      // loopback/emulator/LAN host. Dev-server and jest transforms are not
      // release builds and stay unguarded.
      if (!isJest && api.env('production')) {
        assertReleaseApiTarget(publicEnv.EXPO_PUBLIC_API_URL);
      }
      return {
        visitor: {
          MemberExpression(nodePath) {
            if (isJest) {
              return;
            }
            const node = nodePath.node;
            // Match process.env.EXPO_PUBLIC_*
            if (!t.isIdentifier(node.object, {name: 'process'})) {
              return;
            }
            if (!t.isIdentifier(node.property, {name: 'env'})) {
              return;
            }
            // Parent must be process.env.KEY to narrow to one level
            const parent = nodePath.parentPath.node;
            if (
              nodePath.parentPath.isMemberExpression(parent) &&
              t.isIdentifier(parent.property) &&
              parent.computed === false
            ) {
              const key = parent.property.name;
              if (key.startsWith('EXPO_PUBLIC_') && key in publicEnv) {
                // Do not inline assignment targets / delete operands:
                // tests legitimately do process.env.EXPO_PUBLIC_X = v
                // (or delete) to simulate unset config, and a string
                // literal there is not a valid LVal.
                const gp = nodePath.parentPath.parentPath;
                const isAssignTarget =
                  gp.isAssignmentExpression() &&
                  gp.node.left === nodePath.parentPath.node;
                const isDeleteOperand =
                  gp.isUnaryExpression({operator: 'delete'});
                if (!isAssignTarget && !isDeleteOperand) {
                  nodePath.parentPath.replaceWith(
                    t.stringLiteral(publicEnv[key]),
                  );
                }
              }
            }
          },
        },
      };
    },
  ],
};
