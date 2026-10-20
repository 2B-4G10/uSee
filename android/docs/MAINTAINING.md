# Maintaining the uSee fork

This repository is a fork of [ruvnet/RuView](https://github.com/ruvnet/RuView)
with the uSee Android app added under `android/`.

## Weekly upstream update

`.github/workflows/upstream-sync.yml` runs **every Monday at 04:47 UTC** (or
manually from the Actions tab). It:

1. fetches `ruvnet/RuView` `main`; if nothing is new, it only does step 4
   and opens a PR when that changed the lockfile;
2. merges it into the `upstream-sync` branch (a merge, never a rebase);
3. re-derives the app's wire contract (`tools/upstream_contract.py`) and, when
   ports/paths/magics changed, regenerates `UpstreamContract.kt` and commits it;
4. updates `v2/Cargo.lock` for open RustSec advisories that have a
   semver-compatible fix, so upstream's Security Scanning passes on the PR.
   Advisories with no such fix that do not reach this code are listed, with
   the reason, in the root `.cargo/audit.toml`;
5. when anything that goes into the APK changed since the current version was
   released (`android/app` except unit tests, the Gradle files and wrapper),
   bumps the patch version (`versionCode` + 1, `x.y.z` → `x.y.z+1`);
6. runs the app's unit tests and lint, builds the APK and uploads it as an artifact;
7. opens or refreshes **one pull request** into `main`. The title starts with
   `[needs review]` when a decoded format changed or the build failed, and
   ends with `release uSee x.y.z` when step 5 bumped the version.

`.github/workflows/upstream-sync-merge.yml` checks that PR every hour and
merges it with a merge commit when at least one check ran, every check passed
or was skipped, the newest check is 20 minutes old, and the title does not
start with `[needs review]`. A merge that bumped the version publishes the
release (see below). When a check fails, it adds `[needs review]` to the
title and comments with the failed checks; the PR then waits for you. Fix it
and merge by hand with a **merge commit**.

The run stays green unless the workflow itself breaks. When something needs
you (a merge conflict, a rejected push, a pull request that could not be
opened), it opens an issue labelled `upstream-sync` with the details and a
one-click link, and closes that issue once a sync PR is open again. Forks
have Issues disabled by default; the report is then posted as a comment on
the latest `main` commit instead (once per upstream revision). Pushes and PRs
try `UPSTREAM_SYNC_TOKEN` first and fall back to the built-in token.

## Releasing

The weekly sync releases on its own when the APK changed (see above). To
release by hand:

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Merge to `main`. `.github/workflows/usee-release.yml` notices the new
   version, runs the tests, builds the APK and publishes the GitHub release
   `usee-v<versionName>` with `uSee-<version>.apk` and its SHA-256.

To rebuild an existing release, open *Actions → uSee release → Run workflow*,
or push the matching `usee-v*` tag.

### Only app releases

The Releases page holds uSee releases only, each with an APK attached:

- Upstream jobs that publish server releases (`ci.yml`), open submodule PRs
  every 6 hours (`update-submodules.yml`), push traffic snapshots
  (`clone-tracking.yml`), publish `ruvnet` Docker images
  (`sensing-server-docker.yml`, `nvsim-server-docker.yml`) or deploy demo
  sites and API docs to `gh-pages` (`*-pages.yml`, `ci.yml`) run only in
  `ruvnet/RuView`, as does the *daily*
  Rust audit in `security-scan.yml` (it still runs on every push and PR).
  Submodule bumps and advisory fixes reach this fork through the weekly
  upstream sync.
- `.github/workflows/release-hygiene.yml` runs after CI and release runs on
  `main` and daily. It deletes every published release without an `.apk`
  asset, together with its tag, and keeps the newest `usee-v*` release marked
  as latest. *Run workflow* with **dry run** lists what it would delete;
  **delete release** removes one named release and its tag, such as a
  superseded `usee-v*` build.

### Runners

The app's workflows run on `ubuntu-26.04`. Upstream jobs follow
`ubuntu-latest` (Ubuntu 26.04 from November 2026), except jobs that fail on
26.04 and stay on `ubuntu-24.04`: the Espressif QEMU build and the two jobs
that use it in `firmware-qemu.yml`, and `mqtt-integration.yml`. Move them back
once upstream fixes them.

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
