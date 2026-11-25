# Packaging and releases

This folder holds what ships with a release. `release.yml` in `.github/workflows/` builds it on a `v*` tag.

- `rafiq-bridge`: the start wrapper inside the self-contained bridge download. It points `JAVA_HOME` at the bundled runtime.
- `homebrew/`: the Homebrew formula template. See its README.

## Cutting a release

1. Open a release pull request that sets `rafiq.version` in `gradle.properties` and updates `CHANGELOG.md` (see [CONTRIBUTING.md](../CONTRIBUTING.md)). Merge it.
2. Tag the merged commit on `main` as `v<version>` and push the tag. The workflow refuses a tag whose commit is not on `main`, or whose version differs from `gradle.properties`.
3. Approve the `sign-apk` job when GitHub asks (see below).
4. For a final version, the `update-tap` job commits the Homebrew formula to the tap by itself (see below). A prerelease, any tag with a `-` suffix such as `-rc.1`, is not sent to the tap.

## One-time setup for signing

The signing key must never reach a job that a stray tag could start on its own. Two settings keep it that way, and both are made in GitHub, not in the repository.

**The `release` environment.** The `sign-apk` job runs in an environment named `release`. No other job uses it or holds the secrets.

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

## One-time setup for the Homebrew tap

The `update-tap` job runs in the `release` environment, so a reviewer approves it too. It downloads only the rendered `rafiq-bridge.rb`, with no checkout of this repository, and has no `GITHUB_TOKEN` scopes. It writes to [tatawurAI/homebrew-tap](https://github.com/tatawurAI/homebrew-tap) over SSH with a deploy key that can write to that one repository and nothing else. GitHub's SSH host key is pinned in the workflow. The job copies the file to `Formula/rafiq-bridge.rb`, checks it with `ruby -c`, commits as `github-actions[bot]` with the message `rafiq-bridge <version>` and pushes to `main`. If the tap already has the same file, such as on a rerun, it commits nothing. It deletes the key file at the end.

Set it up once, in an empty folder:

```sh
ssh-keygen -t ed25519 -N "" -C rafiq-release-tap -f tap_key
gh repo deploy-key add tap_key.pub -R tatawurAI/homebrew-tap --allow-write --title "rafiq release workflow"
gh secret set HOMEBREW_TAP_DEPLOY_KEY --env release -R tatawurAI/rafiq < tap_key
rm tap_key tap_key.pub
```

If the tap's `main` branch has a protection rule that blocks direct pushes, allow the deploy key to bypass it, or the push fails.

## How the APK is signed

Signing is split from building, so code that runs during the build never shares a machine with the key.

1. The `apk` job runs Gradle and builds `:android:assembleRelease`. It has no secrets and no environment, and it uploads the unsigned APK as an artifact.
2. The `sign-apk` job starts on a fresh runner. It has no checkout and runs no Gradle. It downloads the unsigned APK, decodes the keystore, signs with `apksigner`, verifies the result, deletes the keystore and uploads the signed APK.
3. The `release` job attaches the signed APK.

The passwords reach `apksigner` as environment variables named on its command line, so they are not in a process listing. `apksigner` takes the key alias only as an argument, so the alias is visible there. It is a name, not a secret.
