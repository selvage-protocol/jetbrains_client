#!/usr/bin/env bash
#
# The local gate, run inside `nix develop`:
#
#   scripts/ci-local.sh lint     # ktlint over the Kotlin sources, shellcheck over the scripts, and
#                                # a syntax check of the end-to-end test's orchestrator
#   scripts/ci-local.sh checks   # the release workflow's dry_run gating, the version bump's
#                                # test, the engine's suite (with the differential test against real
#                                # yjs), the live tests against a real selvaged, the
#                                # specification's peer runner's own tests, its peer corpus and
#                                # the corpus's mutation census, the plugin's suite in a test IDE,
#                                # the plugin hosting and joining in a test IDE against a real
#                                # selvaged, the Plugin Verifier, and the flake's sandboxed checks
#   scripts/ci-local.sh links    # lychee over README.md and docs/
#   scripts/ci-local.sh e2e      # two real IDEs and the TypeScript engine against a real selvaged
#   scripts/ci-local.sh all      # lint + checks + links + e2e
#
# The differential test needs yjs 13.x and y-protocols on disk. By default it looks for a sibling
# `vscode_client/node_modules`, then `web_client/node_modules`; set SELVAGE_YJS_NODE_MODULES (or
# the Gradle property selvage.yjsNodeModules) to point elsewhere. It fails when none is found.
#
# The live tests spawn the selvaged SELVAGE_SELVAGED names and fail, saying so, without one. The
# cross-implementation live test also loads the TypeScript engine from the vscode_client checkout
# SELVAGE_VSCODE_CLIENT names, by default the sibling one, whose packages `npm ci` has installed.
# The plugin's pins compare against the same VS Code client checkout. The peer corpus reads the
# specification at SELVAGE_SPECIFICATION, by default the sibling checkout. The first plugin run
# downloads the IntelliJ IDEA it is tested and verified against into ~/.gradle (about 1.5 GB).
set -euo pipefail

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo_root"

# `/tmp` is a RAM-backed tmpfs on some hosts, and building there has taken a machine down
# before; keep every artefact inside the checkout.
export TMPDIR="$repo_root/.tmp"
mkdir -p "$TMPDIR"
# The JVM ignores TMPDIR; tell it too, and keep its performance data out of the system's.
export JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$TMPDIR -XX:-UsePerfData"

say() { printf '\n=== %s ===\n' "$*"; }

job_lint() {
  say "lint: ktlint"
  ktlint --relative "engine/**/*.kt" "plugin/**/*.kt" "*.kts" "engine/*.kts" "plugin/*.kts"
  say "lint: shellcheck"
  shellcheck scripts/ci-local.sh scripts/run-peer-vectors.sh scripts/e2e/run-two-instance.sh \
    scripts/bump-version.sh scripts/test-bump-version.sh
  python3 -c 'import ast, sys; ast.parse(open(sys.argv[1]).read())' scripts/e2e/two_instance.py
}

job_links() {
  say "links: README.md and docs/"
  lychee --config lychee.toml --no-progress README.md docs
}

job_e2e() {
  say "e2e: two IDEs and the TypeScript engine against a real selvaged"
  scripts/e2e/run-two-instance.sh
}

job_checks() {
  local system
  system=$(nix eval --raw --impure --expr builtins.currentSystem)
  # The guard around the release workflow's `dry_run` input reads `.github/workflows`, so none of
  # the suites below covers it. The flake check runs the same two files `ci.yml` runs, with the
  # flake's Python supplying the PyYAML that job installs.
  say "checks: the release workflow's dry_run gating"
  nix build ".#checks.${system}.dry-run-gating" --no-link --print-build-logs
  say "checks: the version bump, on a scratch copy of the tree"
  scripts/test-bump-version.sh
  say "checks: the engine's suite"
  ./gradlew --max-workers=4 --console=plain :engine:test
  say "checks: the engine against a real selvaged"
  ./gradlew --max-workers=4 --console=plain :engine:liveTest
  say "checks: the specification's peer runner, by its own tests"
  scripts/run-peer-vectors.sh --self-test
  say "checks: the specification's peer corpus"
  scripts/run-peer-vectors.sh
  say "checks: the peer corpus's mutation census"
  scripts/run-peer-vectors.sh --mutation-census
  say "checks: the plugin's suite in a test IDE"
  ./gradlew --max-workers=4 --console=plain :plugin:test
  say "checks: the plugin in a test IDE against a real selvaged"
  ./gradlew --max-workers=4 --console=plain :plugin:test -Pselvage.live
  say "checks: the Plugin Verifier against the targeted IDE"
  ./gradlew --max-workers=4 --console=plain :plugin:verifyPlugin
  say "checks: the flake's checks"
  nix build ".#checks.${system}.ktlint" ".#checks.${system}.scripts" ".#checks.${system}.devshell" \
    --no-link --print-build-logs
}

case "${1:-}" in
  lint) job_lint ;;
  checks) job_checks ;;
  links) job_links ;;
  e2e) job_e2e ;;
  all)
    # One after another, not chained with `&&`: a function called inside such a chain runs with
    # `set -e` off, so a failing step in it would not stop the gate.
    job_lint
    job_checks
    job_links
    job_e2e
    ;;
  *)
    printf 'usage: %s [lint|checks|links|e2e|all]\n' "$0" >&2
    exit 2
    ;;
esac
