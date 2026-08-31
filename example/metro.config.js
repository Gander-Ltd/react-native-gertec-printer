const path = require('path');
const { getDefaultConfig, mergeConfig } = require('@react-native/metro-config');

const root = path.resolve(__dirname, '..');

// react-native-monorepo-config (the scaffold's original choice here) ships ESM-only in
// the installed version, which a CommonJS metro.config.js can't require() -- unrelated
// to this printer package, just a stale scaffold/dependency mismatch. This is the
// standard plain watchFolders + nodeModulesPaths pattern for an example app consuming
// its parent package via a yarn/npm workspace symlink, without that extra dependency.
/**
 * @type {import('metro-config').MetroConfig}
 */
const config = {
  watchFolders: [root],
  resolver: {
    nodeModulesPaths: [
      path.resolve(__dirname, 'node_modules'),
      path.resolve(root, 'node_modules'),
    ],
    // The yarn workspace symlink for the package itself was never set up in this
    // environment (only its dependencies got installed under example/node_modules),
    // so point Metro at the package root directly rather than relying on that symlink.
    extraNodeModules: {
      '@gander-ltd/react-native-gertec-printer': root,
    },
  },
};

module.exports = mergeConfig(getDefaultConfig(__dirname), config);
