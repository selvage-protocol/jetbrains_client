# Releasing

[`.github/workflows/release.yml`](../.github/workflows/release.yml) is the button. It is dispatched
at `main` with `bump` (`patch`, `minor` or `major`; required, no default) and `dry_run`:

```sh
gh workflow run release.yml -R selvage-protocol/jetbrains_client --ref main -f bump=patch -f dry_run=true
```

A dispatch refuses unless it is at `main`, unless the version `plugin/build.gradle.kts` carries is
already tagged `v<version>` on the remote, unless the version the bump computes is not, and unless
the change notes open with that version (below). A dry run stops there and prints the plan. A real
run then:

1. moves the version with [`scripts/bump-version.sh`](../scripts/bump-version.sh), which writes the
   build's `version` and the `selvage-jetbrains/<version>` identity the plugin sends the server;
2. runs the gate `ci.yml` runs on the bumped tree, because a commit pushed with the workflow's own
   token starts no CI run;
3. commits that as the owner and pushes it to `main`, never forced, so a `main` that moved under
   the run turns it red rather than landing an ungated commit;
4. builds `plugin/build/distributions/selvage-<version>.zip`, tags `v<version>` (annotated), and
   creates the GitHub Release with the zip attached and the change notes as its notes;
5. runs `./gradlew :plugin:publishPlugin`, which uploads the zip to the JetBrains Marketplace with
   the token from the repository secret `JETBRAINS_MARKETPLACE_TOKEN`, read from the environment by
   `plugin/build.gradle.kts` and never written into a file.

## Change notes

The Marketplace shows the `<change-notes>` in
[`plugin.xml`](../plugin/src/main/resources/META-INF/plugin.xml) for a version, and nothing can
derive them, so they are written first, in a pull request of their own. Their first paragraph
opens with the version they describe, as in `<p>0.2.0, …</p>`; a dispatch whose bump computes
another version refuses, which also catches a wrong `bump` word.

## Before the first dispatch

- The Marketplace takes a plugin's first upload only by hand, on its website, and `publishPlugin`
  publishes updates only, so the first version is uploaded there.
- The version that upload carries is tagged at the commit it was built from, since the button
  refuses to compute a version from one that was never released:
  `git tag -a v<version> <commit> -m v<version>`, then `git push origin v<version>`.
- The repository secret `JETBRAINS_MARKETPLACE_TOKEN` holds a Marketplace token with upload rights
  to the plugin.

## When a run stops part way

A dispatch only ever cuts the version after the one the build carries, so it never repairs one.

- **Stopped before the push:** nothing changed; dispatch again.
- **Pushed, then stopped before the tag:** `main` carries a version nobody released, and the next
  dispatch refuses. Tag that commit and push the tag as above, then create its Release and publish
  it by hand as in the next case, or put the build back to the released version in a pull request.
- **Tagged and released, then the publish failed:** upload the Release's zip on the plugin's
  Marketplace page, or run `./gradlew :plugin:publishPlugin` from a checkout at the tag with the
  token in `JETBRAINS_MARKETPLACE_TOKEN`.

The guard that keeps a dry run from doing any of this is in [Checks](checks.md).
