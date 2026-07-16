/** @type {import('semantic-release').GlobalConfig} */
export default {
  branches: ["main"],
  tagFormat: "v${version}",
  plugins: [
    "@semantic-release/commit-analyzer",
    "@semantic-release/release-notes-generator",
    [
      "@semantic-release/exec",
      {
        // The dry-run job uses this file to decide whether an APK should be built.
        verifyReleaseCmd: 'printf "%s" "${nextRelease.version}" > .next-version',
      },
    ],
    [
      "@semantic-release/github",
      {
        assets: [
          {
            path: "release-artifacts/*.apk",
            label: "Kiosk Downloader Android APK",
          },
        ],
      },
    ],
  ],
};
