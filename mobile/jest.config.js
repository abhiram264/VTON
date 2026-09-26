module.exports = {
  preset: '@react-native/jest-preset',
  // @firebase/* publish ESM-only entry points; jest's default `require`
  // condition fails on them. The browser builds are CJS, so selecting that
  // export condition is what makes the auth surface loadable under jest.
  testEnvironmentOptions: { customExportConditions: ['browser'] },
  transformIgnorePatterns: [
    'node_modules/(?!((jest-)?react-native|@react-native(-community)?|@react-navigation|react-native-screens|react-native-safe-area-context)/)',
  ],
};
