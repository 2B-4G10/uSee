# Maintaining the uSee fork

This repository is a fork of [ruvnet/RuView](https://github.com/ruvnet/RuView)
with the uSee Android app added under `android/`.

## Weekly upstream update

`.github/workflows/upstream-sync.yml` runs **every Monday at 04:47 UTC** (or
manually from the Actions tab). It:

1. fetches `ruvnet/RuView` `main` and stops if nothing is new;
2. merges it into the `upstream-sync` branch (a merge, never a rebase);
3. re-derives the app's wire contract (`tools/upstream_contract.py`) and, when
   ports/paths/magics changed, regenerates `UpstreamContract.kt` and commits it;
4. runs the app's unit tests and lint, builds the APK and uploads it as an artifact;
5. opens or refreshes **one pull request** into `main`. The title starts with
   `[needs review]` when a decoded format changed or the build failed.

Nothing is merged automatically. Review the PR and merge it with a **merge
commit**. Merge conflicts make the run fail, and the job summary lists the
conflicting files.

## Releasing

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Merge to `main`, then tag and push:
   ```bash
   git tag usee-v<versionName> && git push origin usee-v<versionName>
   ```
3. `.github/workflows/usee-release.yml` tests, builds and publishes a GitHub
   release with `uSee-<version>.apk` and its SHA-256.

## One-time repository setup (owner)

These are GitHub settings and must be set in the web UI.

1. **Enable Actions in the fork.** In the *Actions* tab, click "I understand my
   workflows, go ahead and enable them". This also enables upstream's
   workflows. Disable the ones you don't need (for example scheduled
   agents) from each workflow's "…" menu.
2. **Let Actions open PRs:** *Settings → Actions → General → Workflow
   permissions*: tick "Allow GitHub Actions to create and approve pull
   requests".
3. **Token for upstream workflow changes.** GitHub refuses pushes that change
   `.github/workflows/*` with the default token. Create a fine-grained PAT for
   this repository (Contents, Pull requests, Workflows: read & write) and save it
   as the secret `UPSTREAM_SYNC_TOKEN`.
4. **Stable signing key**, so updates install over each other:
   ```bash
   keytool -genkeypair -v -keystore usee-release.jks -alias usee -keyalg RSA -keysize 4096 -validity 10000
   base64 -w0 usee-release.jks   # copy the output
   ```
   Add the secrets `USEE_KEYSTORE_BASE64`, `USEE_KEYSTORE_PASSWORD`,
   `USEE_KEY_ALIAS` (`usee`) and `USEE_KEY_PASSWORD`. Keep the `.jks` file
   backed up offline. Losing it means users must reinstall.
5. **Lock the repository.** *Settings → Rules → Rulesets → New ruleset →
   Import a ruleset*, then import both files in `.github/rulesets/`:
   - `protect-main.json`: no direct pushes, force-pushes or deletion of `main`.
     Every change goes through a pull request.
   - `protect-release-tags.json`: `usee-v*` tags cannot be moved or deleted.

   `.github/CODEOWNERS` makes the owner the reviewer of every change.
