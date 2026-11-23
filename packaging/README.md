# Packaging and releases

This folder holds what ships with a release. `release.yml` in `.github/workflows/` builds it on a `v*` tag.

- `rafiq-bridge`: the start wrapper inside the self-contained bridge download. It points `JAVA_HOME` at the bundled runtime.
- `homebrew/`: the Homebrew formula template. See its README.

## Cutting a release

1. Open a release pull request that sets `rafiq.version` in `gradle.properties` and updates `CHANGELOG.md` (see [CONTRIBUTING.md](../CONTRIBUTING.md)). Merge it.
2. Tag the merged commit on `main` as `v<version>` and push the tag. The workflow refuses a tag whose commit is not on `main`, or whose version differs from `gradle.properties`.
3. Approve the `apk` job when GitHub asks (see below).
4. Copy `rafiq-bridge.rb` from the release to the Homebrew tap.

## One-time setup for signing

The signing key must never reach a job that a stray tag could start on its own. Two settings keep it that way, and both are made in GitHub, not in the repository.

**The `release` environment.** The `apk` job runs in an environment named `release`.

1. In the repository settings, open Environments and create `release`.
2. Turn on Required reviewers and add the maintainers who may approve a release.
3. Under Deployment branches and tags, allow only tags matching `v*`.
4. Add the four secrets to this environment, not to the repository. A repository secret is available to every job and skips the review.

   ```sh
   base64 < rafiq-release.jks | tr -d '\n' | gh secret set RAFIQ_RELEASE_KEYSTORE_BASE64 --env release
   gh secret set RAFIQ_RELEASE_KEYSTORE_PASSWORD --env release
   gh secret set RAFIQ_RELEASE_KEY_ALIAS --env release
   gh secret set RAFIQ_RELEASE_KEY_PASSWORD --env release
   ```

   Run these inside a checkout, or add `-R <owner>/rafiq`. The last three ask for the value.

**Who may push tags.** Under Rules, add a tag ruleset for `v*` that only maintainers can create. The workflow's check that the tagged commit is on `main` stops mistakes. It cannot stop someone who can push a tag together with a changed workflow file. The ruleset and the required reviewers can.

Make the keystore once, with the JDK's keytool:

```sh
keytool -genkeypair -keystore rafiq-release.jks -storetype PKCS12 -alias rafiq -keyalg RSA -keysize 4096 -validity 10000
```

Keep a backup. A phone that installed an APK signed with this key only accepts updates signed with the same key.

## How the APK is signed

Gradle builds `:android:assembleRelease` with no secrets in its environment, which gives an unsigned APK. A later step signs it with `apksigner`, using only the decoded keystore and the passwords, then verifies the result and deletes the keystore.
