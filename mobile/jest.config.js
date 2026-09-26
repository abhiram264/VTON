module.exports = {
  preset: '@react-native/jest-preset',
  // @firebase/* select their build through the export conditions. Without an
  // override jest's default `require` condition resolves @firebase/util to its
  // ESM file and every firebase import dies with "Cannot use import statement
  // outside a module" (measured, not assumed). Listing `react-native` FIRST is
  // what matters: the browser build is CJS and loads, but Metro ships the
  // react-native build, so testing against it keeps the tests on the artifact
  // the app actually runs. `browser` stays as the fallback condition for the
  // packages that publish no react-native entry.
  testEnvironmentOptions: {
    customExportConditions: ['react-native', 'browser'],
  },
  transformIgnorePatterns: [
    'node_modules/(?!((jest-)?react-native|@react-native(-community)?|@react-navigation|react-native-screens|react-native-safe-area-context)/)',
  ],
};
