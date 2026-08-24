const { withProjectBuildGradle } = require('@expo/config-plugins');
const {
  createGeneratedHeaderComment,
  removeGeneratedContents,
} = require('@expo/config-plugins/build/utils/generateCode');

// Gertec's EasyLayer SDK isn't on Maven -- it's vendored in this package as a local
// Maven repo (android/local-maven-repo) instead of a raw `files(...)` .aar dependency,
// because AGP refuses to bundle a release AAR that has a direct local .aar dependency.
// A repo declared only inside this package's own android/build.gradle isn't visible
// when a consuming app's :app project resolves its own release classpath, so it has
// to be registered at the root `allprojects` level too. Mirrors how expo-camera
// registers its own local maven repo (see expo-camera/plugin/src/withCamera.ts).
const TAG = 'react-native-gertec-printer-import';

const gradleMaven = [
  `def gertecPrinterMavenPath = new File(["node", "--print", "require.resolve('@gander-ltd/react-native-gertec-printer/package.json')"].execute(null, rootDir).text.trim(), "../android/local-maven-repo")`,
  `allprojects { repositories { maven { url(gertecPrinterMavenPath) } } }`,
].join('\n');

function appendContents({ src, newSrc, tag, comment }) {
  const header = createGeneratedHeaderComment(newSrc, tag, comment);
  if (!src.includes(header)) {
    const sanitizedTarget = removeGeneratedContents(src, tag);
    const contentsToAdd = [header, newSrc, `${comment} @generated end ${tag}`].join('\n');
    return {
      contents: sanitizedTarget ?? src + contentsToAdd,
      didMerge: true,
      didClear: !!sanitizedTarget,
    };
  }
  return { contents: src, didClear: false, didMerge: false };
}

const withGertecPrinterGradle = (config) => {
  return withProjectBuildGradle(config, (config) => {
    if (config.modResults.language !== 'groovy') {
      throw new Error(
        'Cannot add the Gertec printer maven repo because android/build.gradle is not groovy'
      );
    }
    config.modResults.contents = appendContents({
      src: config.modResults.contents,
      newSrc: gradleMaven,
      tag: TAG,
      comment: '//',
    }).contents;
    return config;
  });
};

module.exports = withGertecPrinterGradle;
