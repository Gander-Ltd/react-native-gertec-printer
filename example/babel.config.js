// react-native-builder-bob/babel-config (the scaffold's original choice here) ships
// ESM-only in the installed version, which a CommonJS babel.config.js can't require() --
// unrelated to this printer package, just a stale scaffold/dependency mismatch (same
// issue as metro.config.js). This app doesn't need bob's source-aliasing since the
// package resolves through its built lib/ output like any other installed dependency.
module.exports = {
  presets: ['module:@react-native/babel-preset'],
};
