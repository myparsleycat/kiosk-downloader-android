# Kiosk Downloader Android

## Release automation

Pushes to `main` run `.github/workflows/release.yml`. The workflow uses
[semantic-release](https://semantic-release.gitbook.io/) to inspect Conventional
Commits, builds a signed release APK only when a new version is required, and
creates a GitHub Release with the APK attached.

The APK receives the semantic version as `versionName`. Its monotonically
increasing `versionCode` comes from the GitHub Actions run number.

### Configure Android signing

A stable keystore is required so that installed builds can be upgraded. Create
and back up a release keystore; losing it prevents future updates to the app:

```shell
keytool -genkeypair -v -keystore release.jks -alias kiosk-downloader \
  -keyalg RSA -keysize 2048 -validity 10000
```

Add the following repository Actions secrets:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64-encoded contents of `release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Key alias, for example `kiosk-downloader` |
| `ANDROID_KEY_PASSWORD` | Key password |

With GitHub CLI on PowerShell, the keystore can be uploaded without writing an
encoded copy to disk:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks")) |
  gh secret set ANDROID_KEYSTORE_BASE64
gh secret set ANDROID_KEYSTORE_PASSWORD
gh secret set ANDROID_KEY_ALIAS
gh secret set ANDROID_KEY_PASSWORD
```

The last three commands prompt for their values. The workflow stops before the
build with a clear error if any signing secret is missing.

### Commit convention

semantic-release uses Conventional Commits to choose the next version:

- `fix: ...` and `perf: ...` create a patch release.
- `feat: ...` creates a minor release.
- A `BREAKING CHANGE:` footer or `!` after the type creates a major release.
- Other commit types such as `docs:` and `chore:` do not publish a release.

If no previous semantic-release tag exists, the first releasable commit creates
`v1.0.0`. The workflow can also be started manually, but it still publishes only
when the commit history requires a new version.
