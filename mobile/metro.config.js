const {getDefaultConfig, mergeConfig} = require('@react-native/metro-config');

/**
 * Metro configuration
 * https://reactnative.dev/docs/metro
 *
 * Build-time env inlining is implemented in babel.config.js (EXPO_PUBLIC_*
 * keys from mobile/.env are inlined as string literals by a local Babel
 * plugin). This file stays stock.
 *
 * @type {import('@react-native/metro-config').MetroConfig}
 */
const config = {};

module.exports = mergeConfig(getDefaultConfig(__dirname), config);
