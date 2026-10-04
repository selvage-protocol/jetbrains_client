# Checks

The gate runs inside `nix develop`:

```sh
SELVAGE_SELVAGED=/path/to/selvaged scripts/ci-local.sh all
```

| Job | What it runs |
|---|---|
| `lint` | ktlint over the Kotlin sources, shellcheck over the scripts, actionlint over the workflows. CI runs all but actionlint, since the runner has no nix. |
| `checks` | The release workflow's `dry_run` guard and the version bump's test, below; the engine's suite, with the differential test against real `yjs`; the engine against a real `selvaged`, alone and beside the TypeScript engine; the specification's peer runner, its corpus and its mutation census; the plugin's suite in a test IDE; the plugin in a test IDE against a real `selvaged`; the Plugin Verifier; the flake's sandboxed checks. |
| `links` | lychee over `README.md` and `docs/`, with `lychee.toml`. |
| `e2e` | Two real IDEs and the TypeScript engine against a real `selvaged`, below. |
| `all` | All four. |

CI (`.github/workflows/ci.yml`) runs the same steps on `ubuntu-24.04`, with the specification,
the server and the VS Code client at their published `main`.

## The release workflow's guards

`scripts/check_dry_run_gating.py` reads `.github/workflows` back and refuses a workflow that declares
a `dry_run` input and leaves a step after its plan step without a condition that excludes a dry
run: the defect `actionlint` cannot see, because it is the condition a step does not carry. A
condition is judged by what it evaluates to on a dry run rather than by the text it contains, so
`!(inputs.dry_run != true)` is refused even though it contains a gate. The file and its suite,
`scripts/test_check_dry_run_gating.py`, are this repository's, and the copies in
`reference_server`, `specification`, `vscode_client`, `nvim_client` and `web_client` are the same
two files byte for byte.
`checks` runs both as the flake check `dry-run-gating`, and CI runs the same two files with pip's
PyYAML. `scripts/test-bump-version.sh` runs `scripts/bump-version.sh` on a scratch copy of the tree
and asserts that a bump writes the build's version and the client identity and no other file.

## Where the tests are

- `engine/src/test`: the engine. `CrossImplementationTest` puts this engine and the TypeScript one
  in one room.
- `plugin/src/test/kotlin/.../bridge`: the ported rules. `BridgeParityTest` runs the VS Code
  client's TypeScript through Node and compares; `VocabularyTest` checks every sentence against the
  VS Code client's pin; `GrantFolderTest` tries to read out of the folder through links, `..` and
  absolute paths.
- `plugin/src/test/kotlin/.../session`: `DocumentSyncTest` (the sync, echoes, merges, line endings,
  a viewer's read-only documents, saves and failed saves), `CommandsTest` (the commands with no
  session), and `LiveSessionTest`, which runs against a real `selvaged` with `-Pselvage.live`:
  hosting, joining, edits and carets both ways, follow, a viewer, a guest's notices, people lists
  that follow the room, and a guest whose socket is cut and reconnects.

## The two-IDE end-to-end test

```sh
SELVAGE_SELVAGED=/path/to/selvaged scripts/e2e/run-two-instance.sh
```

It builds the plugin's zip and a small driver plugin (`plugin/src/e2e`, never part of the zip),
then starts two real IntelliJ IDEA instances, the one the plugin is built against. Each has its own
config, system, plugins, log and temporary directories under `.tmp/e2e/`, its own Xvfb display and
at most 1.5 GB of heap. The driver answers on a loopback socket: it runs the plugin's own actions on
the event thread, answers their questions from the test, and reports what the IDE shows.

The scenario is the VS Code and Neovim clients' own: host, join from the invite into a new window,
edits both ways, each caret drawn in the other IDE, a path the host never opened, rename, follow,
leave, rejoin, the host's leave ending the room, then a TypeScript guest in the IDE's room beside
the IDE guest, the host's IDE killed until the room ends, and an IDE guest in a TypeScript host's
room. Every spawn is bounded and killed on the way out, every wait polls with a deadline and says
what it last saw, and a watchdog bounds the run. A failure saves each screen as a PNG when
ImageMagick is installed.

Real IDEs are driven this way rather than through the Robot server or the Starter framework because
the commands' questions are the plugin's own, so answering them through the plugin's prompt seam is
exact, and the test needs nothing from the network once the IDE is in `~/.gradle`.

## The Marketplace screenshots

```sh
SELVAGE_SELVAGED=/path/to/selvaged scripts/screenshots/capture.sh
```

A manual step, run inside `nix develop` when the plugin's look changes, and never part of the
gate. It reuses the end-to-end test's pieces: two real IDEs at 1280×800 on their own Xvfb
displays, sandboxes under `.tmp/screenshots/`, and the driver, which also stages a scene (a frame
filling its display, balloons gone, a tool window, a menu or the settings page). Ada hosts a small
Kotlin project the script writes, with the IDE's own runtime as its JDK; Grace joins from the
invite. A scene is captured with `import -window root` once the IDE is in smart mode with nothing
in the background and two captures in a row agree. The five images go to
`docs/images/marketplace/`, recompressed losslessly with optipng, each under 1 MB.

An IDE error the plugin logged on the way is cleared from the status bar before a capture, and the
script prints it as a warning; a warning is a defect to fix, not a step of the run.
