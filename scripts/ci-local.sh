#!/usr/bin/env bash
#
# The local gate, run inside `nix develop`:
#
#   scripts/ci-local.sh lint     # ktlint over the Kotlin sources, shellcheck over this script
#   scripts/ci-local.sh checks   # the engine's suite (with the differential test against real
#                                # yjs), the live tests against a real selvaged, the
#                                # specification's peer runner's own tests, its peer corpus and
#                                # the corpus's mutation census, and the
#                                # flake's sandboxed checks
#   scripts/ci-local.sh all      # lint + checks
#
# The differential test needs yjs 13.x and y-protocols on disk. By default it looks for a sibling
# `vscode_client/node_modules`, then `web_client/node_modules`; set SELVAGE_YJS_NODE_MODULES (or
# the Gradle property selvage.yjsNodeModules) to point elsewhere. It fails when none is found.
#
# The live tests spawn the selvaged SELVAGE_SELVAGED names and fail, saying so, without one. The
# cross-implementation live test also loads the TypeScript engine from the vscode_client checkout
# SELVAGE_VSCODE_CLIENT names, by default the sibling one, whose packages `npm ci` has installed.
# The peer corpus reads the specification at SELVAGE_SPECIFICATION, by default the sibling checkout.
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
  ktlint --relative "engine/**/*.kt" "*.kts" "engine/*.kts"
  say "lint: shellcheck"
  shellcheck scripts/ci-local.sh scripts/run-peer-vectors.sh
}

job_checks() {
  local system
  system=$(nix eval --raw --impure --expr builtins.currentSystem)
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
  say "checks: the flake's checks"
  nix build ".#checks.${system}.ktlint" ".#checks.${system}.scripts" ".#checks.${system}.devshell" \
    --no-link --print-build-logs
}

case "${1:-}" in
  lint) job_lint ;;
  checks) job_checks ;;
  all) job_lint && job_checks ;;
  *)
    printf 'usage: %s [lint|checks|all]\n' "$0" >&2
    exit 2
    ;;
esac
